-- ============================================================================
-- 问卷系统 · 个人中心改版所需结构（**Navicat 直接执行版**）
-- ============================================================================
-- 用法：在 Navicat 里打开 survey_db 的查询窗口，把**下面「二、执行语句」整段**粘进去，
--       点「运行」（Ctrl+R / F6）。执行**一次**即可。
--
-- ★ 为什么和 sql/migration-personal-center.sql 不一样：
--   那份是给命令行（mysql source）用的、**可重复执行**，里面用了存储过程 + DELIMITER；
--   而 Navicat 的查询窗口对 DELIMITER 的处理依赖版本，容易报错，所以这份**去掉存储过程**，
--   写成最朴素的三条 DDL。代价是：**重复执行会报错**（Duplicate column / table exists），
--   所以执行前先用「一、先查状态」确认一下。
--
-- 本机 MySQL 版本：8.0.46
--   ★ 它**不支持** `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（那是 MariaDB 的扩展语法），
--     别自己加上去，会直接报 1064。
-- ============================================================================


-- ============================================================================
-- 一、先查状态（先只运行这一条 SELECT，看清两个数字再决定要不要执行下面那段）
--   期望：t_login_log = 0（还没有）、new_cols = 0（两列都还没有）
--   如果已经是 1 / 2，说明已经建好了，**下面整段都不要执行**。
-- ============================================================================
SELECT
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_login_log')       AS t_login_log,
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user'
      AND COLUMN_NAME IN ('profile_public', 'deleted_at'))                AS new_cols;


-- ============================================================================
-- 二、执行语句（确认上面是 0 / 0 之后，把下面三条**全选一起运行**）
-- ============================================================================

-- ① 登录日志表 —— 对应需求「三.3 账号安全信息展示：最近登录时间、登录IP，数据取自系统登录日志」
CREATE TABLE `t_login_log` (
  `id`          int          NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `user_id`     int          NOT NULL                COMMENT '用户ID（关联t_user.id）',
  `username`    varchar(50)  DEFAULT NULL            COMMENT '登录账号（冗余一份，便于账号改名后历史可读）',
  `role`        varchar(20)  DEFAULT NULL            COMMENT '角色：admin/user',
  `ip`          varchar(50)  DEFAULT NULL            COMMENT '登录IP（与 t_operation_log.ip 同列宽）',
  `user_agent`  varchar(500) DEFAULT NULL            COMMENT '浏览器 UA（写入前按 500 截断）',
  `status`      tinyint      NOT NULL DEFAULT 1      COMMENT '结果：1=成功，0=失败（当前只写 1，为登录失败锁定预留）',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`, `create_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录日志表';

-- ② 资料公开开关 —— 对应需求「三.4 资料公开状态开关」
--    ⚠️ 该需求已被需求方撤销，应用代码已删除；这一列保留在库里（删列不可逆），**新库不必再加**
ALTER TABLE `t_user`
  ADD COLUMN `profile_public` tinyint DEFAULT 1
  COMMENT '资料是否公开：1=公开（问卷发布者信息对外可见），0=不公开';

-- ③ 注销时间（软删除）—— 对应需求「三.5 账号注销入口：采用软删除方案」
ALTER TABLE `t_user`
  ADD COLUMN `deleted_at` datetime DEFAULT NULL
  COMMENT '注销时间（软删除）：NULL=正常，非NULL=已注销';


-- ============================================================================
-- 三、执行完再跑一遍自检（期望：12 / 2 / 3 —— 表数 / 新列数 / 用户数）
-- ============================================================================
-- SELECT
--   (SELECT COUNT(*) FROM information_schema.TABLES
--     WHERE TABLE_SCHEMA = DATABASE())                                      AS 表总数,
--   (SELECT COUNT(*) FROM information_schema.COLUMNS
--     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user'
--       AND COLUMN_NAME IN ('profile_public', 'deleted_at'))                AS 新列数,
--   (SELECT COUNT(*) FROM t_user)                                           AS 用户数;


-- ============================================================================
-- 四、执行后的两条使用约定（★ 写代码时必须遵守，否则新结构等于白建）
-- ============================================================================
-- 1) deleted_at 的语义：NULL = 正常；非 NULL = 已注销。
--    **所有"查用户"的地方都要加 `WHERE deleted_at IS NULL`** ——
--    登录、用户列表、归属校验、问卷发布者信息、通知接收人……漏一处 = 已注销账号还能用。
--    ★ 另外 username 上有唯一索引，已注销账号**仍然占着**那个账号名（防止他人抢注，是有意的）。
-- 2) profile_public 的语义：1 = 公开（问卷发布者信息对外可见）；0 = 不公开。
--    已有用户的默认值 1，与改造前的行为一致（改造前本来就没有隐藏能力）。
-- ============================================================================
