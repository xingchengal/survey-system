-- =====================================================================
-- 迁移：RBAC（角色 / 权限点）+ 问卷协作者
-- 轮次：第三十四轮（§H 第一批后端 H2-1）
-- 性质：**纯新增**（4 张新表 + 系统种子数据），不改任何已有表 —— 可重复执行
-- 回滚：见文末（DROP TABLE；会丢掉协作者与角色分配，不动问卷/答卷）
--
-- ★ 为什么另起 t_role / t_user_role，而不是继续用 t_user.role 一列：
--   现有 role 只有 admin/user 两级，且写死在 JWT 与前端路由里；
--   要支持"一个用户多种角色 + 权限点矩阵"必须能一对多。
--   ★ 但**不改** t_user.role：登录/JWT/前端旧逻辑都以它为准，H2 只做"增量并存"。
--
-- ★ 列名为什么是 collaborator_role 而不是 role：
--   role 在 MySQL 8 是**保留字**，写成 `role` 每处都要反引号，MyBatis 注解里极易漏写。
-- =====================================================================

-- ---------- 1. 角色 ----------
CREATE TABLE IF NOT EXISTS `t_role` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `code` varchar(50) NOT NULL COMMENT '角色码（唯一）：admin / user / 未来的自定义角色',
  `name` varchar(50) NOT NULL COMMENT '展示名',
  `is_builtin` tinyint NOT NULL DEFAULT '0' COMMENT '1=内置角色（不允许删除）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表（RBAC）';

-- ---------- 2. 角色 → 权限点 ----------
CREATE TABLE IF NOT EXISTS `t_role_permission` (
  `id` int NOT NULL AUTO_INCREMENT,
  `role_id` int NOT NULL COMMENT '角色ID（t_role.id）',
  `permission_code` varchar(64) NOT NULL COMMENT '权限点，取值为 common/Permission 里的常量',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_id`,`permission_code`),
  KEY `idx_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-权限点关联表';

-- ---------- 3. 用户 → 角色（一个用户可多角色） ----------
CREATE TABLE IF NOT EXISTS `t_user_role` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID（t_user.id）',
  `role_id` int NOT NULL COMMENT '角色ID（t_role.id）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-角色关联表';

-- ---------- 4. 问卷协作者 ----------
CREATE TABLE IF NOT EXISTS `t_survey_collaborator` (
  `id` int NOT NULL AUTO_INCREMENT,
  `survey_id` int NOT NULL COMMENT '问卷ID',
  `user_id` int NOT NULL COMMENT '被邀请的用户ID',
  `collaborator_role` varchar(20) NOT NULL COMMENT 'viewer=只读 / editor=可编辑 / analyst=可看数据',
  `created_by` int DEFAULT NULL COMMENT '邀请人（问卷作者或管理员）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_survey_user` (`survey_id`,`user_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问卷协作者表';

-- =====================================================================
-- 种子数据（可重复执行：INSERT IGNORE 依赖上面的唯一键去重）
-- =====================================================================
INSERT IGNORE INTO `t_role` (`code`, `name`, `is_builtin`) VALUES ('admin', '管理员', 1);
INSERT IGNORE INTO `t_role` (`code`, `name`, `is_builtin`) VALUES ('user', '普通用户', 1);

-- admin：7 个权限点全给（★ 代码里 admin 还有一层"兜底放行"，这里给全是为了让矩阵可展示/可审计）
INSERT IGNORE INTO `t_role_permission` (`role_id`, `permission_code`)
SELECT r.id, p.code FROM `t_role` r
JOIN (
    SELECT 'survey:edit'   AS code UNION ALL
    SELECT 'survey:publish' UNION ALL
    SELECT 'survey:data'    UNION ALL
    SELECT 'survey:delete'  UNION ALL
    SELECT 'user:manage'    UNION ALL
    SELECT 'review:handle'  UNION ALL
    SELECT 'wallet:manage'
) p
WHERE r.code = 'admin';

-- user：自己名下的问卷要能编辑/发布/看数据/删除（仍受"归属校验"约束，权限点只表示"这类操作允许"）
INSERT IGNORE INTO `t_role_permission` (`role_id`, `permission_code`)
SELECT r.id, p.code FROM `t_role` r
JOIN (
    SELECT 'survey:edit'    AS code UNION ALL
    SELECT 'survey:publish' UNION ALL
    SELECT 'survey:data'    UNION ALL
    SELECT 'survey:delete'
) p
WHERE r.code = 'user';

-- 把现有用户按 t_user.role 补一条 t_user_role（已存在则忽略；已注销账号不补）
INSERT IGNORE INTO `t_user_role` (`user_id`, `role_id`)
SELECT u.id, r.id
FROM `t_user` u
JOIN `t_role` r ON r.code = u.role
WHERE u.deleted_at IS NULL;

-- ---------- 自检 ----------
-- 应为 2 行（admin / user）
SELECT COUNT(*) AS role_count FROM `t_role`;
-- admin 7 个、user 4 个
SELECT r.code, COUNT(*) AS perm_count FROM `t_role` r
JOIN `t_role_permission` rp ON rp.role_id = r.id GROUP BY r.code;
-- 应等于"未注销用户数"
SELECT COUNT(*) AS user_role_count FROM `t_user_role`;

-- =====================================================================
-- 回滚（按需手动执行；会丢协作者与角色分配，不动问卷/答卷）
-- =====================================================================
-- DROP TABLE IF EXISTS `t_survey_collaborator`;
-- DROP TABLE IF EXISTS `t_user_role`;
-- DROP TABLE IF EXISTS `t_role_permission`;
-- DROP TABLE IF EXISTS `t_role`;
