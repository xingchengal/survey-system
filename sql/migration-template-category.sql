-- =====================================================================
-- 迁移：模板分类清单（独立于模板存在）
-- 性质：新增 1 张表 + 灌入现有分类（**可重复执行**：CREATE IF NOT EXISTS + INSERT IGNORE）
-- 回滚：
--   DROP TABLE IF EXISTS `t_template_category`;
--   ★ 回滚只丢"分类清单"本身，不动 t_survey.template_category —— 每份模板的分类仍在，
--     模板广场的筛选也照常；丢的是"暂时没有模板的空分类"。
--
-- ★ 为什么要这张表：
--   分类原来只存在 t_survey.template_category 上（每份模板一份），
--   前端只能从"现有模板里出现过哪些分类"反推可选列表 ——
--   于是"先建一个还没有模板的分类"没有任何入口。
--
-- ★ 与 init.sql 的关系：本脚本是**增量**（已在跑的库用它）；新建库直接用 sql/init.sql。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `t_template_category` (
  `id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL COMMENT '分类名（全表唯一）',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '展示顺序，小的在前；新增追加到末尾',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_category_name` (`name`),
  KEY `idx_template_category_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模板分类清单';

-- 1) 7 个预置分类（与 TemplateSeederService 的预置模板分类一致）
INSERT IGNORE INTO `t_template_category` (`name`, `sort_order`) VALUES
  ('满意度', 1), ('报名', 2), ('反馈', 3), ('调研', 4),
  ('健康', 5), ('教育', 6), ('其他', 7);

-- 2) 把老库里已经用过的分类补进来（包括自定义分类；已有同名则跳过）
INSERT IGNORE INTO `t_template_category` (`name`, `sort_order`)
SELECT DISTINCT `template_category`, 100
FROM `t_survey`
WHERE `is_template` = 1 AND `deleted` = 0
  AND `template_category` IS NOT NULL AND TRIM(`template_category`) <> '';

-- =====================================================================
-- 自检
-- =====================================================================
-- 分类数：至少 7（库里没有自定义分类时正好 7）
SELECT COUNT(*) AS category_count FROM `t_template_category`;

-- 应等于"库里现有模板分类去重后的数量"（含 7 个预置中未被使用的）
SELECT COUNT(DISTINCT `template_category`) AS used_categories
FROM `t_survey`
WHERE `is_template` = 1 AND `deleted` = 0
  AND `template_category` IS NOT NULL AND TRIM(`template_category`) <> '';

-- 分类清单里不应有空白名
SELECT COUNT(*) AS blank_categories FROM `t_template_category`
WHERE TRIM(`name`) = '' OR `name` IS NULL;
