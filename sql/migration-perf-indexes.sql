-- ============================================================
-- 性能索引（第二十八轮）：为"列表分页 + 排序"补两个复合索引
--
-- 用法（先确认在哪个库执行，脚本里的 TABLE_SCHEMA 用 DATABASE() 动态取）：
--   mysql.exe -uroot -p<口令> --default-character-set=utf8mb4 survey_db -e "source <此文件>"
--
-- ★ 可重复执行：每一步都先查 information_schema，已存在就跳过（不会报 Duplicate key name）。
-- ★ 撤销方式（会回到"全表扫描 + filesort"，不影响数据正确性）：
--     ALTER TABLE t_answer DROP KEY idx_survey_submit_time,
--                          ADD KEY idx_survey_id (survey_id);
--     ALTER TABLE t_survey DROP KEY idx_created_time;
--   ★ 注意：撤销 `t_answer` 时把单列 idx_survey_id **加回来**，
--     否则老版本的查询会少一个选择（新库的 init.sql 里已不再建这个单列索引，
--     因为它被 uk_survey_user(survey_id, user_id) 的最左前缀完全覆盖）。
--
-- 背景（为什么是这两个）：
--   1. `t_answer(survey_id, submit_time)`
--      被三条路径共用：答卷列表分页（ORDER BY submit_time DESC）、质控可疑样本、
--      导出前的份数/明细预检。原来只有单列 survey_id，排序要 filesort。
--   2. `t_survey(created_time)`
--      SurveyMapper 里 **5 条**查询都是 `ORDER BY created_time DESC`
--      （管理端列表 / 回收站 / 模板广场 / 我的问卷的父查询）。
--   3. `t_answer_detail` **不加新索引**：单题分布是
--      `WHERE question_id = ? GROUP BY content`，已有 `idx_question_id` 负责过滤；
--      content 是 varchar(1000)（utf8mb4 最长 4000 字节，超过 InnoDB 3072 字节上限），
--      建不了全列索引；前缀索引又无法让 MySQL 用它完成分组/覆盖，收益接近零。
-- ============================================================

-- ---------- 1) t_answer：加 (survey_id, submit_time) ----------
SET @has_answer_composite := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_answer'
    AND INDEX_NAME = 'idx_survey_submit_time'
);
SET @sql := IF(@has_answer_composite = 0,
  'ALTER TABLE t_answer ADD KEY idx_survey_submit_time (survey_id, submit_time)',
  'SELECT ''idx_survey_submit_time 已存在，跳过'' AS step1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2) t_answer：删掉冗余的单列 idx_survey_id ----------
-- ★ 只在复合索引已经就绪时才删：先保证"有替代者"，避免中间态缺索引。
-- ★ 这里必须**重新查一次**复合索引是否存在 —— 上面的变量是 step 1 执行**之前**的快照，
--   刚建好时它仍是 0（本轮第一版就是这里写错，导致"索引建了、旧索引没删"）。
SET @has_answer_composite := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_answer'
    AND INDEX_NAME = 'idx_survey_submit_time'
);
SET @has_answer_single := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_answer'
    AND INDEX_NAME = 'idx_survey_id'
);
SET @sql := IF(@has_answer_composite > 0 AND @has_answer_single > 0,
  'ALTER TABLE t_answer DROP KEY idx_survey_id',
  'SELECT ''idx_survey_id 无需删除（已不存在或复合索引未就绪）'' AS step2');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3) t_survey：加 created_time ----------
SET @has_survey_created := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey'
    AND INDEX_NAME = 'idx_created_time'
);
SET @sql := IF(@has_survey_created = 0,
  'ALTER TABLE t_survey ADD KEY idx_created_time (created_time)',
  'SELECT ''idx_created_time 已存在，跳过'' AS step3');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4) 校验：两条新索引应当都出现 ----------
SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND INDEX_NAME IN ('idx_survey_submit_time', 'idx_created_time')
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
