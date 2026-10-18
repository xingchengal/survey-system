-- =====================================================================
-- 迁移：问卷业务生命周期 lifecycle
-- 轮次：第三十四轮（§H-H1-1）
-- 性质：**加列 + 回填**（不可重复执行：先查列是否已存在，见文末自检）
-- 回滚：ALTER TABLE t_survey DROP COLUMN lifecycle;（会丢生命周期状态，不动问卷/答卷）
--
-- ★ 为什么不复用 status：status 的 0/1/2 被定时任务、前端、SQL 广泛依赖，
--   改它的数字语义 = 全站回归。lifecycle 是**并存的第二套语义**：
--     DRAFT / PUBLISHED / SUSPENDED（被举报下架，H1 用）/ ENDED / ARCHIVED（回收站）
--   ★ 两套必须**由 Service 同步维护**，不要只改一边（对账 SQL 见文末）。
-- =====================================================================

-- ① 加列（先查：SELECT COUNT(*) FROM information_schema.COLUMNS
--    WHERE TABLE_SCHEMA='survey_db' AND TABLE_NAME='t_survey' AND COLUMN_NAME='lifecycle';）
ALTER TABLE `t_survey`
  ADD COLUMN `lifecycle` varchar(20) DEFAULT 'DRAFT'
  COMMENT '业务生命周期：DRAFT/PUBLISHED/SUSPENDED/ENDED/ARCHIVED；与 status 并存，由 Service 同步';

-- ①.1 默认值：新建问卷（insert 不显式写 lifecycle）必须是 DRAFT，否则为 NULL
ALTER TABLE `t_survey` ALTER COLUMN `lifecycle` SET DEFAULT 'DRAFT';

-- ② 历史数据回填（一次性；与 status + deleted 的映射见 common/SurveyLifecycle）
UPDATE `t_survey`
SET `lifecycle` = CASE
    WHEN `deleted` = 1 THEN 'ARCHIVED'
    WHEN `status` = 1 THEN 'PUBLISHED'
    WHEN `status` = 2 THEN 'ENDED'
    ELSE 'DRAFT'
  END
WHERE `lifecycle` IS NULL;

-- ③ 索引：管理端"按生命周期筛选"会用到（当前数据量小，但筛选列该有）
CREATE INDEX `idx_lifecycle` ON `t_survey` (`lifecycle`);

-- ---------- 自检 ----------
-- 应为 0：回填后不应再有 NULL
SELECT COUNT(*) AS null_lifecycle FROM `t_survey` WHERE `lifecycle` IS NULL;
-- 分布（与 status/deleted 应对得上）
SELECT `lifecycle`, COUNT(*) AS c FROM `t_survey` GROUP BY `lifecycle`;
