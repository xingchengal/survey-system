-- =====================================================================
-- 迁移：进阶题型（矩阵 8 / 排序 9 / NPS 10）
-- 性质：给 t_question 加 2 列、给 t_answer_detail 加 2 列（可重复执行）
-- 回滚：ALTER TABLE t_question DROP COLUMN matrix_rows, DROP COLUMN matrix_cols;
--       ALTER TABLE t_answer_detail DROP COLUMN row_key, DROP COLUMN rank_no;
--       ★ 回滚会丢矩阵题的行列标题与排序题的名次，但不会动题干/选项/答卷本身。
--
-- ★ 为什么矩阵的行列标题放在 t_question 而不是 t_option：
--   t_option 是"一维选项"语义（单选/多选/下拉/排序的候选项），
--   矩阵需要的是"行 × 列"两个相互独立的维度。塞进 t_option 只能靠再加一列
--   区分"这是行还是列"，之后**每一处**读选项的代码都要先过滤 —— 漏一处就把列当成答案。
--   放成两列 JSON 文本后，矩阵完全不碰 t_option，现有选择题链路零改动。
-- ★ 为什么 answer_detail 要加 row_key / rank_no 而不是把答案拼成 JSON：
--   统计口径是 SQL `GROUP BY`（矩阵按行分组、排序按选项算平均名次）。
--   拼成 JSON 就只能把整题答案拉进内存再解析，答卷一多统计页就成瓶颈。
-- ★ 两列都可空：历史答卷与其它题型不写它们，默认 NULL，完全向后兼容。
-- =====================================================================

-- 1) t_question.matrix_rows（矩阵行标题，JSON 数组文本）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_question' AND COLUMN_NAME = 'matrix_rows'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_question` ADD COLUMN `matrix_rows` varchar(2000) DEFAULT NULL COMMENT ''矩阵题行标题：JSON 数组文本（仅 type=8），如 ["服务态度","响应速度"]''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) t_question.matrix_cols（矩阵列标题，JSON 数组文本）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_question' AND COLUMN_NAME = 'matrix_cols'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_question` ADD COLUMN `matrix_cols` varchar(2000) DEFAULT NULL COMMENT ''矩阵题列标题：JSON 数组文本（仅 type=8），如 ["很满意","满意","一般"]''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3) t_answer_detail.row_key（矩阵题的行标题）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_answer_detail' AND COLUMN_NAME = 'row_key'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_answer_detail` ADD COLUMN `row_key` varchar(500) DEFAULT NULL COMMENT ''矩阵题：该答案属于哪一行（行标题原文）；其它题型为 NULL''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4) t_answer_detail.rank_no（排序题的名次，1 起）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_answer_detail' AND COLUMN_NAME = 'rank_no'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_answer_detail` ADD COLUMN `rank_no` smallint DEFAULT NULL COMMENT ''排序题：名次（1 起）；其它题型为 NULL''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 自检
SELECT COUNT(*) AS question_axis_columns FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_question'
  AND COLUMN_NAME IN ('matrix_rows', 'matrix_cols');
SELECT COUNT(*) AS answer_detail_columns FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_answer_detail'
  AND COLUMN_NAME IN ('row_key', 'rank_no');
