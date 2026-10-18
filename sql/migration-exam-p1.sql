-- =====================================================================
-- 迁移：考试判分 P1（限时 / 提交后显示答案 / 逐题对错）
-- 性质：给 2 张表加 3 列（**可重复执行**：先查 information_schema 再决定加不加）
-- 回滚：
--   ALTER TABLE t_survey DROP COLUMN time_limit_seconds, DROP COLUMN show_answer_after_submit;
--   ALTER TABLE t_answer_detail DROP COLUMN is_correct;
--   ★ 回滚会丢"限时/是否展示答案"的配置与"逐题对错"数据，但不动答卷内容与分数本身。
--
-- ★ P0（判分主体）见 sql/migration-exam-grading.sql，本脚本只加 P1 的三列。
-- =====================================================================

-- 1) t_survey.time_limit_seconds（答题限时，秒）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey' AND COLUMN_NAME = 'time_limit_seconds'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `time_limit_seconds` int DEFAULT NULL COMMENT ''答题限时（秒）：NULL=不限；前端倒计时，服务端按上报 duration_ms + 10s 宽限校验（非防作弊）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) t_survey.show_answer_after_submit（提交后是否展示正确答案）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey' AND COLUMN_NAME = 'show_answer_after_submit'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `show_answer_after_submit` tinyint NOT NULL DEFAULT 0 COMMENT ''提交后是否展示正确答案：0=否（只出分），1=是；只在 grade_enabled=1 时有意义''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) t_answer_detail.is_correct（逐题对错）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_answer_detail' AND COLUMN_NAME = 'is_correct'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_answer_detail` ADD COLUMN `is_correct` tinyint DEFAULT NULL COMMENT ''该题是否答对：1=对，0=错，NULL=未判分。★ 口径按题：多选一选项一行，同题所有行同值''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- =====================================================================
-- 自检
-- =====================================================================

SELECT COUNT(*) AS added_columns FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ( (TABLE_NAME = 't_survey'        AND COLUMN_NAME IN ('time_limit_seconds', 'show_answer_after_submit'))
     OR (TABLE_NAME = 't_answer_detail' AND COLUMN_NAME = 'is_correct') );

-- 存量数据未被改动（应为 0）：老明细没有逐题对错
SELECT COUNT(*) AS graded_details FROM t_answer_detail WHERE is_correct IS NOT NULL;

-- 现有配置（应为 0）：还没人设置过限时
SELECT COUNT(*) AS limited_surveys FROM t_survey WHERE time_limit_seconds IS NOT NULL;
