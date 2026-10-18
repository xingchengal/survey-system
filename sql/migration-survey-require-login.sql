-- =====================================================================
-- 迁移：问卷「仅限登录用户填写」
-- 性质：给 t_survey 加一列（**可重复执行**：先查 information_schema 再决定加不加）
-- 回滚：ALTER TABLE t_survey DROP COLUMN require_login;
--       ★ 回滚会让"实名问卷"退回匿名（已提交的答卷里 user_id 仍在，不受影响）。
--
-- ★ 为什么不是全局开关：匿名是当前所有问卷的默认行为，
--   全局改成"登录才记身份"会让所有历史问卷的数据语义悄悄变化；
--   由作者按问卷选择，默认 0，老问卷零影响。
--
-- ★ 生效链路（改这一列之前先读）：
--   1) 填写页：requireLogin=1 且未登录 → 只渲染登录引导、不加载题目；
--   2) 服务端：AnswerService.doSubmit 见到 requireLogin=1 + userId==null 直接 400
--      （submit-share 的 userId 恒为 null，所以匿名提交一律被拒）；
--   3) 实名答卷受 uk_survey_user 约束：同一账号对同一问卷只能交一份；
--   4) 统计页的"填写者"列本来就会显示「用户 #id / 匿名」，无需改前端。
--   ★ 已知局限：题目接口仍是公开的（分享链接本来就能读题），本列**不挡读题**，只挡提交。
-- =====================================================================

SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey' AND COLUMN_NAME = 'require_login'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `require_login` tinyint NOT NULL DEFAULT 0 COMMENT ''是否仅限登录用户填写：0=匿名可填（默认），1=必须先登录；开启后只收带 token 的 POST /answer/submit''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 自检：应为 1
SELECT COUNT(*) AS require_login_column FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey' AND COLUMN_NAME = 'require_login';

-- 自检：存量问卷应全部为 0（本迁移不改动任何存量数据）
SELECT COUNT(*) AS login_required_surveys FROM t_survey WHERE require_login <> 0;
