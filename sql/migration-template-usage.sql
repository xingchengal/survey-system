-- =====================================================================
-- 迁移：模板使用次数（看哪些模板热门）
-- 性质：给 t_survey 加一列（可重复执行）
-- 回滚：ALTER TABLE t_survey DROP COLUMN template_use_count;
--       ★ 只是热度统计，回滚不影响问卷/模板内容。
--
-- ★ 为什么是"模板行上的计数器"而不是新表：
--   要回答的问题只有"这个模板被用过几次"。计数落回模板自己的行，
--   列表接口天然带出来（SELECT *），不需要 JOIN/额外查询。
-- ★ 累加用 `template_use_count = template_use_count + 1`（数据库自增），
--   避免"读出来 +1 再写回"在并发下互相覆盖。
-- ★ 只在「从模板创建」时 +1；复制自己已发布的问卷不算模板使用。
-- =====================================================================

SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey' AND COLUMN_NAME = 'template_use_count'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `template_use_count` int NOT NULL DEFAULT 0 COMMENT ''该模板被"从模板创建"使用的次数（热度；非模板恒为 0）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 自检
SELECT COUNT(*) AS template_use_count_column FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey' AND COLUMN_NAME = 'template_use_count';
