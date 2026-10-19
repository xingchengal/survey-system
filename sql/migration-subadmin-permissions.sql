-- =====================================================================
-- 迁移：次级管理员权限地基（subadmin + 用户级权限点）
-- 性质：新增 1 张表 + 3 个角色种子 + 6 个模块级权限点（**可重复执行**）
-- 回滚：
--   DROP TABLE IF EXISTS `t_user_permission`;
--   DELETE FROM `t_role_permission` WHERE `permission_code` IN
--     ('survey:manage','template:manage','announcement:manage','log:view','feedback:handle','system:view');
--   DELETE FROM `t_role` WHERE `code` = 'subadmin';
--   ★ 回滚会丢掉"谁被授权了什么"；已有的 admin/user 角色与问卷数据不动。
--
-- ★ 这一步只做"地基"，不改任何现有端点的放行规则：
--   现有 41 个 @RequireAdmin 仍然只认 admin（超管）；
--   次级管理员即使被建出来，也必须在后续把对应端点换成 @RequirePermission 后才能访问。
--   这样迁移可以先上、代码再逐模块切，不会出现"迁移完就多出一批越权入口"。
-- =====================================================================

-- 1) 用户级权限点（次级管理员逐项授权）
CREATE TABLE IF NOT EXISTS `t_user_permission` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID（t_user.id）',
  `permission_code` varchar(64) NOT NULL COMMENT '权限点；只允许 common/Permission.ADMIN_GRANTABLE',
  `created_by` int DEFAULT NULL COMMENT '授权人（超管）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_permission` (`user_id`,`permission_code`),
  KEY `idx_user_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-权限点直接授权（次级管理员）';

-- 2) 次级管理员角色（不给角色级权限：权限全部走 t_user_permission）
INSERT IGNORE INTO `t_role` (`code`, `name`, `is_builtin`) VALUES ('subadmin', '次级管理员', 1);

-- 3) 超管补 6 个模块级权限点（原有 7 个继续保留）
INSERT IGNORE INTO `t_role_permission` (`role_id`, `permission_code`)
SELECT r.id, p.code FROM `t_role` r
JOIN (
    SELECT 'survey:manage' AS code UNION ALL
    SELECT 'template:manage' UNION ALL
    SELECT 'announcement:manage' UNION ALL
    SELECT 'log:view' UNION ALL
    SELECT 'feedback:handle' UNION ALL
    SELECT 'system:view'
) p
WHERE r.code = 'admin';

-- 4) 若库里已有 role='subadmin' 的用户（手工改过/未来导入），补一条 t_user_role
INSERT IGNORE INTO `t_user_role` (`user_id`, `role_id`)
SELECT u.id, r.id
FROM `t_user` u
JOIN `t_role` r ON r.code = 'subadmin'
WHERE u.role = 'subadmin' AND u.deleted_at IS NULL;

-- =====================================================================
-- 自检
-- =====================================================================
-- 应有 3 个角色（admin / subadmin / user）
SELECT COUNT(*) AS role_count FROM `t_role`;

-- admin 应有 13 个权限点；subadmin 应为 0（权限在用户级）
SELECT r.code, COUNT(rp.id) AS perm_count
FROM `t_role` r LEFT JOIN `t_role_permission` rp ON rp.role_id = r.id
GROUP BY r.code ORDER BY r.code;

-- 新表应存在
SELECT COUNT(*) AS user_permission_table
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user_permission';

-- 当前被授权的次级管理员数（迁移后一般为 0）
SELECT COUNT(*) AS granted_users FROM `t_user_permission`;
