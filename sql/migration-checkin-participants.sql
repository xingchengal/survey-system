-- =====================================================================
-- 迁移：签到核销 + 接龙报名名单
-- 性质：给 2 张表加 4 列（**可重复执行**：先查 information_schema 再决定加不加）
-- 回滚：
--   ALTER TABLE t_survey DROP COLUMN checkin_enabled, DROP COLUMN show_participants;
--   ALTER TABLE t_answer DROP COLUMN checked_in, DROP COLUMN checked_in_at;
--   ★ 回滚会丢"哪些答卷已签到"，但答卷内容与分数不受影响。
--
-- ★ 为什么签到状态放在 t_answer 而不是新表：
--   签到是"这份答卷到没到场"的一对一状态，跟答卷同生命周期（删问卷/删答卷时自动没了），
--   加一列比多一张关联表简单，也不会产生孤儿行。
-- ★ 为什么报名名单用"第一道单行文本题"当称呼：
--   项目没有"姓名"字段（问卷内容是任意的），接龙/报名的第一题通常就是姓名。
--   这里只做展示，不落库、不改数据模型；取不到文本题时名单显示"匿名"。
-- =====================================================================

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_survey' AND COLUMN_NAME='checkin_enabled');
SET @ddl = IF(@c = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `checkin_enabled` tinyint NOT NULL DEFAULT 0 COMMENT ''是否启用签到核销：0=否（默认），1=是；作者在答卷列表里打勾''',
  'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_survey' AND COLUMN_NAME='show_participants');
SET @ddl = IF(@c = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `show_participants` tinyint NOT NULL DEFAULT 0 COMMENT ''是否公开报名名单：0=否（默认），1=是；开启后填写页显示前 50 位报名者''',
  'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_answer' AND COLUMN_NAME='checked_in');
SET @ddl = IF(@c = 0,
  'ALTER TABLE `t_answer` ADD COLUMN `checked_in` tinyint NOT NULL DEFAULT 0 COMMENT ''是否已签到：0=未签到（默认），1=已签到''',
  'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='t_answer' AND COLUMN_NAME='checked_in_at');
SET @ddl = IF(@c = 0,
  'ALTER TABLE `t_answer` ADD COLUMN `checked_in_at` datetime DEFAULT NULL COMMENT ''签到时间：取消签到时置回 NULL''',
  'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- 自检：应为 4
SELECT COUNT(*) AS added_columns FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ( (TABLE_NAME='t_survey' AND COLUMN_NAME IN ('checkin_enabled','show_participants'))
     OR (TABLE_NAME='t_answer' AND COLUMN_NAME IN ('checked_in','checked_in_at')) );

-- 自检：存量数据未被改动（均应为 0）
SELECT (SELECT COUNT(*) FROM t_answer WHERE checked_in <> 0) AS checked_answers,
       (SELECT COUNT(*) FROM t_survey WHERE checkin_enabled <> 0 OR show_participants <> 0) AS enabled_surveys;
