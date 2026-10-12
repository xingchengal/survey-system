-- ============================================================================
-- 问卷系统 · 迁移脚本（个人中心改版所需）
-- ============================================================================
-- 用途：给「个人中心改版」需求（IdeaProjects/docs/需求-个人中心改版.md）准备建表/加列。
--
-- 怎么跑：
--   mysql.exe -uroot -p<口令> --default-character-set=utf8mb4 survey_db -e "source C:/Users/1/Desktop/dsh/IdeaProjects/sql/migration-personal-center.sql"
--
-- ★ 本脚本**可重复执行**（跑第二遍不报错、不会改坏数据）。
-- ★ 执行前先备份：mysqldump -uroot -p<口令> survey_db > backup.sql
--
-- ★★ 关于"可重复执行"的实现方式（这里踩过一次，别再改回去）：
--   本机 MySQL 是 **8.0.46**，它**不支持** `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
--   （那是 MariaDB 的扩展语法；我在第一版里写成那样，实测直接 1064 语法错误）。
--   所以下面用 `INFORMATION_SCHEMA` 判断 + 动态 SQL 的标准写法，
--   建临时存储过程、跑完即删。**不要**为省事改成 IF NOT EXISTS。
--
-- 为什么是这些（对应需求条款）：
--   · 需求 三.3「账号安全信息展示：最近登录时间、登录IP，数据取自**系统登录日志**」
--     → 需要登录日志表：t_login_log（新建）
--   · 需求 三.4「资料公开状态开关」—— ⚠️ **该需求后来被需求方撤销**，
--     应用代码（端点/Service/Mapper/实体/前端）已全部删除；本脚本加的 profile_public 列
--     仍留在库里（删列不可逆）。**新库不要再用这一列**，见 docs/问卷系统-待解决问题.md B14。
--     → 需要 t_user.profile_public（加列）
--   · 需求 三.5「账号注销入口：采用**软删除**方案」
--     → 需要 t_user.deleted_at（加列）
--   · 需求 二.1 的可编辑项（昵称/头像/手机号/邮箱）与 二.3 改密码
--     → **不需要改表**：t_user 已有 nickname / avatar / phone / email / password
-- ============================================================================


-- ============================================================================
-- 一、新建：登录日志表
-- ============================================================================
-- 设计说明（对齐现有 10 张表的风格）：
--   · 表名 t_login_log、字段 snake_case、带中文 COMMENT；
--   · **不加外键**：全库 0 个外键约束，跨表一致性由代码维护（保持一致，别破例）；
--   · ip 用 varchar(50)：与 t_operation_log.ip / t_error_log.ip 完全一致
--     （util/WebUtils 会按列宽截断超长 IP，见那边注释）；
--   · status 列**现在就留上**：需求列了可选加分项"登录失败次数限制、多次错误临时锁定账号"，
--     有了它将来只改代码、不动表。当前实现只写 status=1（成功）。
--   · 索引：按用户查"最近登录"用 idx_user_id；按时间清理旧日志用 idx_create_time。
CREATE TABLE IF NOT EXISTS `t_login_log` (
  `id`          int          NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `user_id`     int          NOT NULL                COMMENT '用户ID（关联t_user.id，不加外键，与全库一致）',
  `username`    varchar(50)  DEFAULT NULL            COMMENT '登录账号（冗余一份，便于账号改名前后的历史可读）',
  `role`        varchar(20)  DEFAULT NULL            COMMENT '角色：admin/user',
  `ip`          varchar(50)  DEFAULT NULL            COMMENT '登录IP（优先取 X-Forwarded-For，可伪造，仅供参考；与 t_operation_log.ip 同列宽）',
  `user_agent`  varchar(500) DEFAULT NULL            COMMENT '浏览器 UA（写入前按 500 截断；仅用于"异地登录"这类排查）',
  `status`      tinyint      NOT NULL DEFAULT 1      COMMENT '结果：1=成功，0=失败（当前只写 1；为"登录失败锁定"预留）',
  `create_time` datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`, `create_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录日志表';


-- ============================================================================
-- 二、用户表加列：软删除 + 资料公开开关（可重复执行）
-- ============================================================================
DROP PROCEDURE IF EXISTS `sp_add_user_cols_if_missing`;
DELIMITER $$
CREATE PROCEDURE `sp_add_user_cols_if_missing`()
BEGIN
  -- 资料公开开关：需求 三.4
  IF NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'profile_public'
  ) THEN
    ALTER TABLE `t_user`
      ADD COLUMN `profile_public` tinyint DEFAULT 1
      COMMENT '资料是否公开：1=公开（问卷发布者信息对外可见），0=不公开' AFTER `email`;
  END IF;

  -- 软删除时间：需求 三.5
  IF NOT EXISTS (
      SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_user' AND COLUMN_NAME = 'deleted_at'
  ) THEN
    ALTER TABLE `t_user`
      ADD COLUMN `deleted_at` datetime DEFAULT NULL
      COMMENT '注销时间（软删除）：NULL=正常，非NULL=已注销；所有用户查询都要过滤 deleted_at IS NULL' AFTER `profile_public`;
  END IF;
END$$
DELIMITER ;

CALL `sp_add_user_cols_if_missing`();
DROP PROCEDURE `sp_add_user_cols_if_missing`;


-- ============================================================================
-- 三、deleted_at 的语义约定（★ 写代码时必须遵守，否则软删除会形同虚设）
-- ============================================================================
--   · NULL    = 账号正常
--   · 非 NULL = 已注销（软删除）
--   · **所有"查用户"的地方都要加 `WHERE deleted_at IS NULL`**：
--     登录、用户列表、归属校验、问卷发布者信息、通知接收人、演示数据回收……
--     ★ 漏掉任何一处 = 已注销账号还能用（这是软删除最常见的翻车点）。
--   · username 上有 UNIQUE 索引：已注销账号**仍然占着**那个账号名。
--     这是**有意**的（防止他人抢注注销用户的账号名）。若将来要放开，
--     得把唯一索引改成 (username, deleted_at) 组合唯一 —— **那是另一个决定，本轮不要动**。


-- ============================================================================
-- 四、执行后自检（三行都应返回结果）
-- ============================================================================
-- SHOW TABLES LIKE 't_login_log';
-- SHOW COLUMNS FROM t_user LIKE 'profile_public';
-- SHOW COLUMNS FROM t_user LIKE 'deleted_at';


-- ============================================================================
-- 五、本次**不需要**建的东西（写清楚，免得你多建）
-- ============================================================================
-- · 最近登录**不需要**再给 t_user 加 last_login_time —— 需求说"数据取自系统登录日志"，
--   直接 `SELECT ... FROM t_login_log WHERE user_id=? ORDER BY create_time DESC LIMIT 1` 即可。
--   加了反而要额外维护一致性（冗余列迟早不同步）。
-- · 手机号/邮箱**不需要**新列：t_user.phone / t_user.email 已存在。
-- · 短信/邮件验证码表：需求明确本轮不做（"不开发短信、邮件相关能力"）。
-- · 头像**不需要**新列/新表：t_user.avatar 是 mediumtext，存 base64（UserService 限 3MB）。
-- · 「记住我」的刷新令牌表 t_refresh_token **已存在**（第十九轮建），不用再建。
