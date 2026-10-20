-- =====================================================================
-- 迁移：作答乱序 / 随机抽题（t_survey 加 3 列）
-- 性质：**加列**（可重复执行：列已存在则跳过）
-- 回滚：
--   ALTER TABLE `t_survey` DROP COLUMN `shuffle_questions`;
--   ALTER TABLE `t_survey` DROP COLUMN `shuffle_options`;
--   ALTER TABLE `t_survey` DROP COLUMN `questions_per_attempt`;
--
-- ★ 语义：
--   shuffle_questions=1 → 填写页题目顺序随机；编辑器 / 统计 / 回看仍按 sort_order 全量。
--   shuffle_options=1   → 填写页每题选项顺序随机；判分按选项原文，不受顺序影响。
--   questions_per_attempt=0 → 不抽题；N>0 → 每份答卷从全部题目中抽 N 道，判分总分按抽到的题算。
-- ★ 约束：存在逻辑跳转（t_option.next_question_id 非空）的问卷不允许开启，
--   保存设置时由 SurveyService.normalizeSurveySettings 拒绝（抽掉跳转目标会让流程失效）。
-- =====================================================================

-- ① 题目乱序
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey'
              AND COLUMN_NAME = 'shuffle_questions');
SET @ddl = IF(@col = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `shuffle_questions` tinyint NOT NULL DEFAULT ''0'' COMMENT ''题目乱序：0=按 sort_order，1=填写页随机''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ② 选项乱序
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey'
              AND COLUMN_NAME = 'shuffle_options');
SET @ddl = IF(@col = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `shuffle_options` tinyint NOT NULL DEFAULT ''0'' COMMENT ''选项乱序：0=按 sort_order，1=填写页随机''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ③ 随机抽题数量
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey'
              AND COLUMN_NAME = 'questions_per_attempt');
SET @ddl = IF(@col = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `questions_per_attempt` int NOT NULL DEFAULT ''0'' COMMENT ''每份答卷抽题数：0=不抽，N=从全部题目随机抽 N 道（判分总分按抽到的题算）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 自检 ----------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey'
  AND COLUMN_NAME IN ('shuffle_questions', 'shuffle_options', 'questions_per_attempt')
ORDER BY ORDINAL_POSITION;

-- 存量问卷都应是不抽题、不乱序
SELECT COUNT(*) AS enabled_surveys
FROM `t_survey`
WHERE `shuffle_questions` = 1 OR `shuffle_options` = 1 OR `questions_per_attempt` > 0;
