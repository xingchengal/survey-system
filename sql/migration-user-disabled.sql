-- =====================================================================
-- 迁移：用户「禁用」标记（管理端批量禁用 / 解禁）
-- 性质：给 t_user 加一列（可重复执行）
-- 回滚：ALTER TABLE t_user DROP COLUMN disabled;
--       ★ 回滚会丢掉"谁被禁用过"这一状态，账号本身与其它数据不受影响。
--
-- ★ 为什么不是复用 deleted_at（注销）：
--   注销是**用户自己的**决定（走 DELETE /api/user/account，还要输密码），语义是"这个账号我不用了"；
--   禁用是**管理员**的处置（内容违规、异常操作），语义是"这个账号暂时不许登录，但数据都还在"。
--   两者共用一列会让列表里分不清"他自己注销的"还是"被我禁用的"，恢复时也无法区分该走哪条路径。
--
-- ★ 为什么不用 tinyint NULL：NOT NULL DEFAULT 0 让"未禁用"只有一种表示（0），
--   不必在查询与前端里同时处理 NULL 和 0 两种"正常"。
--
-- ★ 生效链路（改这一列之前先读）：
--   1) 登录：UserController.login → 密码验过后判 disabled=1 → 400「账号已被管理员禁用」；
--   2) 已登录用户：JwtAuthFilter 每个请求查库，见到 disabled=1 立即 401；
--   3) 禁用/解禁时 UserService 同时写 token_valid_after（把该账号此刻之前签发的票全部作废），
--      否则"禁用"要等最长 12 小时才真正生效 —— 与注销/改密码同一个道理。
-- =====================================================================

SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'disabled'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_user` ADD COLUMN `disabled` tinyint NOT NULL DEFAULT 0 COMMENT ''禁用标记：0=正常，1=已禁用（禁止登录；数据保留；管理员可解禁）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 自检：应为 1
SELECT COUNT(*) AS disabled_column FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'disabled';

-- 自检：现有账号应全部为 0（本迁移不改动任何存量数据）
SELECT COUNT(*) AS disabled_users FROM t_user WHERE disabled <> 0;
