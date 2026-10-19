-- =====================================================================
-- 迁移：反馈中心 / 客服工单（批次 3）
-- 性质：**新建两张表**（可重复执行：CREATE TABLE IF NOT EXISTS + 索引补齐）
-- 回滚：DROP TABLE IF EXISTS `t_feedback_message`;
--       DROP TABLE IF EXISTS `t_feedback`;
--       ★ 回滚会丢失全部反馈/工单与消息，执行前先备份。
--
-- ★ 模型：一张工单头 + 一张消息线程。
--   - type：FEEDBACK=普通反馈（可多条）/ SERVICE=客服工单（同一用户同时只保留 1 个 OPEN）。
--   - status：OPEN=处理中 / CLOSED=已关闭；只关闭、不物理删除（保留审计）。
--   - handled_by：最近处理的管理员；通知按它精准发，不群发全部管理员。
--   - last_message_id / last_message_time / last_message_preview：列表排序与轮询增量用。
--   - user_last_read_message_id / admin_last_read_message_id：双方已读水位。
-- ★ 消息幂等：uk_feedback_client_msg (feedback_id, client_msg_id)。
--   client_msg_id 为 NULL 时唯一键不生效（MySQL 约定），管理端可以不传；前端发送一律带 UUID。
-- ★ 同一用户只能有一个 OPEN SERVICE 工单由生成列 `open_service_user_id` + 唯一索引保证：
--   SERVICE+OPEN 时取 user_id，其余情况为 NULL（唯一键允许多个 NULL），关闭后自动释放。
--   再点客服时 Service 直接返回已有工单，不新建。
-- ★ 本脚本只改结构；执行前需经用户确认，确认后再同步 sql/init.sql 并写依赖新表的代码。
-- =====================================================================

-- ① 工单头
CREATE TABLE IF NOT EXISTS `t_feedback` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '发起人（只允许本人读写）',
  `type` varchar(20) NOT NULL COMMENT 'FEEDBACK=反馈 / SERVICE=客服工单',
  `subject` varchar(200) NOT NULL COMMENT '主题（≤200 字）',
  `status` varchar(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN=处理中 / CLOSED=已关闭；只关闭不删除',
  `handled_by` int DEFAULT NULL COMMENT '最近处理的管理员（通知按它精准发）',
  `last_message_id` bigint DEFAULT NULL COMMENT '最新消息 id（列表排序与轮询用）',
  `last_message_time` datetime DEFAULT NULL COMMENT '最新消息时间',
  `last_message_preview` varchar(200) DEFAULT NULL COMMENT '最新消息预览（截断）',
  `user_last_read_message_id` bigint DEFAULT NULL COMMENT '用户已读水位',
  `admin_last_read_message_id` bigint DEFAULT NULL COMMENT '管理员已读水位',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `open_service_user_id` int GENERATED ALWAYS AS (
    CASE WHEN `type` = 'SERVICE' AND `status` = 'OPEN' THEN `user_id` ELSE NULL END
  ) STORED COMMENT '并发保护：同一用户最多一个 OPEN SERVICE 工单；其他情况为 NULL（唯一键允许多个 NULL）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_open_service` (`open_service_user_id`),
  KEY `idx_feedback_user` (`user_id`, `id`),
  KEY `idx_feedback_status` (`status`, `id`),
  KEY `idx_feedback_handled` (`handled_by`, `status`),
  KEY `idx_feedback_last` (`last_message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='反馈 / 客服工单头';

-- ② 消息线程
CREATE TABLE IF NOT EXISTS `t_feedback_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `feedback_id` int NOT NULL COMMENT '所属工单',
  `sender_id` int NOT NULL COMMENT '发送人 id',
  `sender_role` varchar(20) NOT NULL COMMENT 'user / admin',
  `content` varchar(2000) NOT NULL COMMENT '纯文本，≤2000 字；渲染禁止 v-html',
  `client_msg_id` varchar(64) DEFAULT NULL COMMENT '客户端幂等键（UUID）；NULL 时不参与唯一键',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feedback_client_msg` (`feedback_id`, `client_msg_id`),
  KEY `idx_feedback_msg` (`feedback_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='反馈 / 客服消息线程';

-- ③ 索引补齐：表已存在但索引缺失时补上（CREATE TABLE IF NOT EXISTS 不会补）
SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_feedback' AND INDEX_NAME = 'idx_feedback_user');
SET @ddl = IF(@idx_exists = 0,
  'ALTER TABLE `t_feedback` ADD INDEX `idx_feedback_user` (`user_id`, `id`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_feedback' AND INDEX_NAME = 'idx_feedback_status');
SET @ddl = IF(@idx_exists = 0,
  'ALTER TABLE `t_feedback` ADD INDEX `idx_feedback_status` (`status`, `id`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_feedback' AND INDEX_NAME = 'idx_feedback_handled');
SET @ddl = IF(@idx_exists = 0,
  'ALTER TABLE `t_feedback` ADD INDEX `idx_feedback_handled` (`handled_by`, `status`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_feedback' AND INDEX_NAME = 'idx_feedback_last');
SET @ddl = IF(@idx_exists = 0,
  'ALTER TABLE `t_feedback` ADD INDEX `idx_feedback_last` (`last_message_id`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_feedback_message' AND INDEX_NAME = 'idx_feedback_msg');
SET @ddl = IF(@idx_exists = 0,
  'ALTER TABLE `t_feedback_message` ADD INDEX `idx_feedback_msg` (`feedback_id`, `id`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ④ 生成列与唯一索引补齐：表已存在但缺并发保护时补上
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_feedback' AND COLUMN_NAME = 'open_service_user_id');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_feedback` ADD COLUMN `open_service_user_id` int GENERATED ALWAYS AS (CASE WHEN `type` = ''SERVICE'' AND `status` = ''OPEN'' THEN `user_id` ELSE NULL END) STORED COMMENT ''并发保护：同一用户最多一个 OPEN SERVICE 工单；其他情况为 NULL''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_feedback' AND INDEX_NAME = 'uk_open_service');
SET @ddl = IF(@idx_exists = 0,
  'ALTER TABLE `t_feedback` ADD UNIQUE KEY `uk_open_service` (`open_service_user_id`)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 自检 ----------
-- ① 两张表都在（应为 2）
SELECT COUNT(*) AS table_count FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('t_feedback', 't_feedback_message');

-- ② 列清单
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('t_feedback', 't_feedback_message')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

-- ③ 索引清单（uk_feedback_client_msg 应为 feedback_id + client_msg_id）
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_index
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('t_feedback', 't_feedback_message')
GROUP BY TABLE_NAME, INDEX_NAME
ORDER BY TABLE_NAME, INDEX_NAME;

-- ④ 生成列与唯一索引（并发保护）：EXTRA 应含 STORED GENERATED
SELECT COLUMN_NAME, EXTRA FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_feedback' AND COLUMN_NAME = 'open_service_user_id';

SELECT INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS columns_in_index
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_feedback' AND INDEX_NAME = 'uk_open_service'
GROUP BY INDEX_NAME, NON_UNIQUE;

-- ⑤ 行数（新表应为 0）
SELECT (SELECT COUNT(*) FROM `t_feedback`) AS feedback_rows,
       (SELECT COUNT(*) FROM `t_feedback_message`) AS message_rows;
