-- =====================================================================
-- 迁移：考试判分（P0）
-- 性质：给 4 张表加 7 列（**可重复执行**：先查 information_schema 再决定加不加）
-- 回滚：
--   ALTER TABLE t_survey   DROP COLUMN grade_enabled, DROP COLUMN pass_score;
--   ALTER TABLE t_question DROP COLUMN score;
--   ALTER TABLE t_option   DROP COLUMN is_correct;
--   ALTER TABLE t_answer   DROP COLUMN total_score, DROP COLUMN earned_score, DROP COLUMN is_passed;
--   ★ 回滚会丢"正确答案"与"历史分数"两类数据，但不动题目、选项文字与答卷内容本身。
--
-- ★ 为什么分数用 NULL 而不是默认 0：
--   判分上线前的历史答卷、以及普通问卷的答卷，都不该被统计成"考了 0 分"。
--   统计口径一律 `WHERE earned_score IS NOT NULL`（见 AnswerMapper.selectScoreSummary）。
--
-- ★ 为什么总分/得分要存快照：
--   作者事后改分值/改及格线，不能让"上周的 80 分"变成 76 分。考试成绩必须可复现。
--
-- ★ 为什么判分按 content 比对：
--   t_answer_detail 没有 option_id，只存选项原文。所以考试问卷发布后改选项文字，
--   会影响**之后**提交的判分；已落库的 earned_score 不变。
--
-- ★ 与 init.sql 的关系：本脚本是**增量**（已在跑的库用它）；新建库直接用 sql/init.sql。
-- =====================================================================

-- 1) t_survey.grade_enabled（考试模式开关）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey' AND COLUMN_NAME = 'grade_enabled'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `grade_enabled` tinyint NOT NULL DEFAULT 0 COMMENT ''考试模式：0=普通问卷（不判分），1=判分（提交时算分并写入 t_answer 三个分数字段）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) t_survey.pass_score（及格分）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey' AND COLUMN_NAME = 'pass_score'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `pass_score` int DEFAULT NULL COMMENT ''及格分：NULL=不判及格（只出分）；开启考试模式时取值必须在 0 ~ 本卷总分之间''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) t_question.score（分值）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_question' AND COLUMN_NAME = 'score'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_question` ADD COLUMN `score` int NOT NULL DEFAULT 0 COMMENT ''分值：0=不参与判分；>0 表示考试模式下计分（只有单选/多选/下拉允许 >0，保存时校验）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4) t_option.is_correct（是否正确答案）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_option' AND COLUMN_NAME = 'is_correct'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_option` ADD COLUMN `is_correct` tinyint NOT NULL DEFAULT 0 COMMENT ''是否正确答案：0=否，1=是。★ 只有单选/多选/下拉有判分意义；判分按 content 比对（明细表没有 option_id）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5) t_answer.total_score（本次总分，快照）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_answer' AND COLUMN_NAME = 'total_score'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_answer` ADD COLUMN `total_score` int DEFAULT NULL COMMENT ''本次答卷总分（提交那一刻的分值快照）；NULL=未判分''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6) t_answer.earned_score（本次得分）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_answer' AND COLUMN_NAME = 'earned_score'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_answer` ADD COLUMN `earned_score` int DEFAULT NULL COMMENT ''本次得分；NULL=未判分（普通问卷，或判分上线前的历史答卷）★ 统计一律只算非 NULL 的行''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 7) t_answer.is_passed（是否及格）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_answer' AND COLUMN_NAME = 'is_passed'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_answer` ADD COLUMN `is_passed` tinyint DEFAULT NULL COMMENT ''是否及格：NULL=未设及格线或未判分；0=不及格；1=及格（得分 >= 及格分）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- =====================================================================
-- 自检
-- =====================================================================

-- 自检 1：应有 7 列（第一次执行后为 7；重复执行为 7）
SELECT COUNT(*) AS added_columns FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ( (TABLE_NAME = 't_survey'   AND COLUMN_NAME IN ('grade_enabled', 'pass_score'))
     OR (TABLE_NAME = 't_question' AND COLUMN_NAME = 'score')
     OR (TABLE_NAME = 't_option'   AND COLUMN_NAME = 'is_correct')
     OR (TABLE_NAME = 't_answer'   AND COLUMN_NAME IN ('total_score', 'earned_score', 'is_passed')) );

-- 自检 2：存量数据未被改动（应为 0：老答卷 earned_score 都是 NULL）
SELECT COUNT(*) AS graded_answers FROM t_answer WHERE earned_score IS NOT NULL;

-- 自检 3：不应有"考试开关开着却没有及格线"之外的异常组合（0 或 1 都合法）
SELECT COUNT(*) AS exam_surveys FROM t_survey WHERE grade_enabled = 1;
