-- ============================================================
-- 问卷系统 · 数据库结构（MySQL 8，库名 survey_db，共 13 张表）
-- ============================================================
-- 来源：mysqldump --no-data --default-character-set=utf8mb4 survey_db
--       ★ 但**不是**原样 dump —— 里面手工补了"个人中心即将用到"的结构
--         （t_login_log 与 t_user 的两列），所以它代表**目标结构**，比任一时刻的现库更全。
-- 更新日期：2026-10-17（第二十八轮：补性能索引；此前已含 t_survey_version 与答卷质控 3 列）
--
-- 为什么要有这个文件（评审【工程性】+《待解决问题》C1：仓库里 0 个 .sql 文件，
-- 换台机器/重装数据库后无法重建 survey_db，答辩演示换机器时有风险）：
--   重建库只需两条命令：
--     mysql -uroot -p --default-character-set=utf8mb4 -e "CREATE DATABASE IF NOT EXISTS survey_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
--     mysql -uroot -p --default-character-set=utf8mb4 survey_db < sql/init.sql
--
-- ★ 这里只有**表结构**：不含任何业务数据，也不含数据库口令 / AI Key / JWT 密钥
--   （那些在 application-local.yml 里，且该文件从不进 git）。
--
-- ★ 建完库还有两步（都不需要手写 SQL）：
--   1) 初始管理员：系统没有管理员种子数据 —— 先注册一个账号，
--      再执行 UPDATE t_user SET role='admin' WHERE username='你的账号';
--   2) 内置模板：模板广场初始是空的。用管理员账号调一次**幂等**接口
--      POST /api/template-seeder/init 即可补回 31 个模板。
--      ★ 该接口在 prod profile 下**不注册**（@Profile("!prod")），需在 local 下调用。
--
-- ★ 表清单（13 张）：
--   t_user 用户      / t_survey 问卷        / t_question 题目      / t_option 选项
--   t_answer 答卷    / t_answer_detail 明细 / t_notification 通知  / t_announcement 公告
--   t_operation_log 操作日志 / t_error_log 异常日志
--   t_refresh_token 刷新令牌（"记住我"，第十九轮）
--   t_login_log 登录日志（个人中心"最近登录时间/IP"用）
--   t_survey_version 问卷版本快照（第二十五轮）
--
-- ★ 老库升级按顺序执行（每个都可重复执行，脚本头部写了撤销方式）：
--   migration-personal-center.sql  → 个人中心两列 + t_login_log
--   migration-answer-unique-key.sql → t_answer 唯一键 uk_survey_user
--   migration-survey-version.sql    → t_survey_version
--   migration-answer-quality.sql    → t_answer 质控 3 列（★ 不可重复执行，先查列）
--   migration-perf-indexes.sql      → 性能索引（第二十八轮）
--
-- ★ 与 sql/migration-personal-center.sql 的分工：
--   本文件是**全量**（新建库直接用它 → 得到最终结构）；
--   migration 脚本是**增量**（已在跑的库用它补 t_login_log 与 t_user 两列，可重复执行）。
--   两份都留：新建库用 init，升级已有库用 migration。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_announcement` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '公告ID',
  `title` varchar(200) NOT NULL COMMENT '标题',
  `content` text NOT NULL COMMENT '内容',
  `publisher_id` int DEFAULT NULL COMMENT '发布人ID',
  `publisher_name` varchar(50) DEFAULT NULL COMMENT '发布人昵称',
  `status` tinyint DEFAULT '1' COMMENT '状态：1=发布，0=下架',
  `expire_time` datetime DEFAULT NULL COMMENT '过期时间，NULL表示永不过期',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_expire_time` (`expire_time`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统公告表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_answer` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '答卷ID',
  `survey_id` int NOT NULL COMMENT '所属问卷ID',
  `user_id` int DEFAULT NULL COMMENT '填写者ID，NULL表示匿名填写',
  `submit_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  `duration_ms` int DEFAULT NULL COMMENT '答题耗时（毫秒）。由答题页上报；NULL=未上报。★ 客户端值，可伪造，只作质控信号、不作安全边界',
  `quality_flag` tinyint NOT NULL DEFAULT '0' COMMENT '质控标记：0=正常，1=可疑（命中任一无效样本规则，规则码见 quality_reasons）',
  `quality_reasons` varchar(200) DEFAULT NULL COMMENT '命中的规则码，逗号分隔：TOO_FAST / STRAIGHT_LINE / OPTION_MISMATCH / DUP_TITLE_CONFLICT',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_survey_user` (`survey_id`,`user_id`),
  -- ★ 答卷列表分页 / 质控样本 / 导出预检：都是 "WHERE survey_id = ? ORDER BY submit_time DESC"。
  --   等值列 + 排序列放同一个索引，避免 filesort；深度翻页也不用先扫全表再排序。
  --   原来的单列 idx_survey_id 已被 uk_survey_user 的最左前缀覆盖，不再单独保留
  --   （老库升级见 sql/migration-perf-indexes.sql）。
  KEY `idx_survey_submit_time` (`survey_id`,`submit_time`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3254 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='答卷记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_answer_detail` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  `answer_id` int NOT NULL COMMENT '所属答卷ID',
  `question_id` int NOT NULL COMMENT '题目ID',
  `content` varchar(1000) DEFAULT NULL COMMENT '回答内容',
  `row_key` varchar(500) DEFAULT NULL COMMENT '矩阵题：该答案属于哪一行（行标题原文）；其它题型为 NULL',
  `rank_no` smallint DEFAULT NULL COMMENT '排序题：名次（1 起）；其它题型为 NULL',
  PRIMARY KEY (`id`),
  KEY `idx_answer_id` (`answer_id`),
  KEY `idx_question_id` (`question_id`)
) ENGINE=InnoDB AUTO_INCREMENT=16017 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='答题明细表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_error_log` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '异常ID',
  `user_id` int DEFAULT NULL COMMENT '操作人ID',
  `username` varchar(50) DEFAULT NULL COMMENT '操作人昵称',
  `request_uri` varchar(300) DEFAULT NULL COMMENT '请求路径',
  `request_method` varchar(10) DEFAULT NULL COMMENT '请求方式',
  `error_type` varchar(200) DEFAULT NULL COMMENT '异常类型',
  `error_message` text COMMENT '异常信息',
  `stack_trace` text COMMENT '堆栈',
  `ip` varchar(50) DEFAULT NULL COMMENT 'IP地址',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=35 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统异常日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_login_log` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `user_id` int NOT NULL COMMENT '用户ID（关联t_user.id，不加外键，与全库一致）',
  `username` varchar(50) DEFAULT NULL COMMENT '登录账号（冗余一份，便于账号改名前后的历史可读）',
  `role` varchar(20) DEFAULT NULL COMMENT '角色：admin/user',
  `ip` varchar(50) DEFAULT NULL COMMENT '登录IP（优先取 X-Forwarded-For，可伪造，仅供参考；与 t_operation_log.ip 同列宽）',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '浏览器 UA（写入前按 500 截断；仅用于异地登录这类排查）',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '结果：1=成功，0=失败（当前只写 1；为登录失败锁定预留）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`,`create_time`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录日志表';

CREATE TABLE `t_notification` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '通知ID',
  `user_id` int NOT NULL COMMENT '接收人ID',
  `title` varchar(200) NOT NULL COMMENT '标题',
  `content` varchar(500) DEFAULT NULL COMMENT '内容',
  `type` varchar(30) DEFAULT 'system' COMMENT '类型：answer=收到答卷，announcement=公告，system=系统',
  `is_read` tinyint DEFAULT '0' COMMENT '0=未读，1=已读',
  `link` varchar(200) DEFAULT NULL COMMENT '跳转链接',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '通知时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_is_read` (`is_read`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内通知表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_operation_log` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `user_id` int DEFAULT NULL COMMENT '操作人ID',
  `username` varchar(50) DEFAULT NULL COMMENT '操作人用户名',
  `role` varchar(20) DEFAULT NULL COMMENT '角色：admin/user',
  `operation` varchar(200) DEFAULT NULL COMMENT '操作描述',
  `method` varchar(200) DEFAULT NULL COMMENT '调用的方法',
  `ip` varchar(50) DEFAULT NULL COMMENT '操作IP',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=250 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_option` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '选项ID',
  `question_id` int NOT NULL COMMENT '所属题目ID',
  `content` varchar(500) NOT NULL COMMENT '选项内容',
  `sort_order` int DEFAULT '0' COMMENT '选项顺序',
  `next_question_id` int DEFAULT NULL COMMENT '选中此选项后跳到哪一题（NULL表示下一题）',
  PRIMARY KEY (`id`),
  KEY `idx_question_id` (`question_id`)
) ENGINE=InnoDB AUTO_INCREMENT=8510 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目选项表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_question` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '题目ID',
  `survey_id` int NOT NULL COMMENT '所属问卷ID',
  `type` tinyint NOT NULL COMMENT '题型：1=单选，2=多选，3=单行输入，4=多行文本，5=下拉，6=评分，7=日期，8=矩阵，9=排序，10=NPS',
  `title` varchar(500) NOT NULL COMMENT '题目内容',
  `required` tinyint DEFAULT '0' COMMENT '是否必填：0=否，1=是',
  `sort_order` int DEFAULT '0' COMMENT '题目顺序',
  `matrix_rows` varchar(2000) DEFAULT NULL COMMENT '矩阵题行标题：JSON 数组文本（仅 type=8），如 ["服务态度","响应速度"]',
  `matrix_cols` varchar(2000) DEFAULT NULL COMMENT '矩阵题列标题：JSON 数组文本（仅 type=8），如 ["很满意","满意","一般"]',
  PRIMARY KEY (`id`),
  KEY `idx_survey_id` (`survey_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3186 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问卷题目表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_refresh_token` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `user_id` int NOT NULL COMMENT '所属用户ID（关联t_user.id）',
  `token_hash` varchar(128) NOT NULL COMMENT '刷新令牌的 SHA-256 十六进制（不存明文 token）',
  `expires_at` datetime NOT NULL COMMENT '过期时间',
  `revoked` tinyint DEFAULT '0' COMMENT '是否吊销：0=有效，1=已吊销',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '签发时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_token_hash` (`token_hash`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='刷新令牌表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_survey` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '问卷ID',
  `user_id` int NOT NULL COMMENT '创建者ID（关联t_user.id）',
  `title` varchar(200) NOT NULL COMMENT '问卷标题',
  `description` varchar(500) DEFAULT NULL COMMENT '问卷描述',
  `status` tinyint DEFAULT '0' COMMENT '状态：0=草稿，1=已发布',
  `share_code` varchar(20) DEFAULT NULL COMMENT '分享码（发布后生成，用于匿名访问）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '0=正常，1=回收站',
  `start_time` datetime DEFAULT NULL COMMENT '定时发布时间（可选）',
  `end_time` datetime DEFAULT NULL COMMENT '截止时间（可选）',
  `view_count` int DEFAULT '0' COMMENT '访问次数',
  `deleted_by` int DEFAULT NULL COMMENT '删除人ID（谁移入回收站的）',
  `deleted_at` datetime DEFAULT NULL COMMENT '移入回收站的时间：NULL=不在回收站，非NULL=何时被删；恢复时置回 NULL',
  `lifecycle` varchar(20) DEFAULT 'DRAFT' COMMENT '业务生命周期：DRAFT/PUBLISHED/SUSPENDED/ENDED/ARCHIVED；与 status 并存，由 Service 同步',
  `access_password` varchar(20) DEFAULT NULL COMMENT '答题密码：NULL=不需要；非空时加载题目与提交答卷都要带 X-Survey-Password',
  `max_responses` int DEFAULT NULL COMMENT '最多收集多少份答卷：NULL=不限；达到后拒绝新提交',
  `thank_text` varchar(500) DEFAULT NULL COMMENT '提交后的感谢语：NULL=默认「提交成功，感谢您的参与」',
  `redirect_url` varchar(500) DEFAULT NULL COMMENT '提交后跳转链接：NULL=不跳转；只允许 http/https',
  `public_result` tinyint NOT NULL DEFAULT '0' COMMENT '是否公开结果页：0=不公开，1=开启（匿名可访问 /r/{shareCode}）',
  `ip_limit` tinyint NOT NULL DEFAULT '0' COMMENT 'IP 限制：0=关闭；1=同一 IP 只能提交一次（高级设置包）',
  `is_template` tinyint DEFAULT '0' COMMENT '是否模板：0=否，1=是',
  `template_category` varchar(50) DEFAULT NULL COMMENT '模板分类：调查/报名/反馈等',
  `template_use_count` int NOT NULL DEFAULT '0' COMMENT '该模板被"从模板创建"使用的次数（热度；非模板恒为 0）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `share_code` (`share_code`),
  -- ★ 管理端问卷列表 / 回收站 / 模板广场都按 "created_time DESC" 排序（见 SurveyMapper 的 5 条查询）
  KEY `idx_created_time` (`created_time`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=747 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问卷表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_survey_version` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '版本ID',
  `survey_id` int NOT NULL COMMENT '所属问卷ID',
  `version_no` int NOT NULL COMMENT '版本号（同一问卷内从 1 递增，不因删除旧版本而回退）',
  `title` varchar(200) DEFAULT NULL COMMENT '快照时的问卷标题（改标题不生成版本，但对比时能看到它）',
  `description` varchar(500) DEFAULT NULL COMMENT '快照时的问卷描述',
  `question_count` int NOT NULL DEFAULT '0' COMMENT '题目数量（列表展示用，免得去解析 JSON）',
  `questions_json` mediumtext NOT NULL COMMENT '题目与选项的完整快照（JSON，按 sort_order 排序）',
  `change_summary` varchar(500) DEFAULT NULL COMMENT '相对上一版本的变更摘要（自动生成，便于列表直接展示）',
  `created_by` int DEFAULT NULL COMMENT '操作人ID',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_survey_version` (`survey_id`,`version_no`),
  KEY `idx_survey_id` (`survey_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问卷版本快照表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_user` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(50) NOT NULL COMMENT '用户名（唯一）',
  `password` varchar(100) NOT NULL COMMENT '密码（PBKDF2 哈希，约 83 字符；兼容历史 MD5 的 32 位十六进制）',
  `nickname` varchar(50) DEFAULT NULL COMMENT '昵称',
  `role` varchar(20) DEFAULT 'user' COMMENT '角色：admin=管理员，user=普通用户',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
  `avatar` mediumtext COMMENT '头像（base64 或 URL）',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `profile_public` tinyint DEFAULT 1 COMMENT '【已废弃】原「资料公开开关」，需求已撤销、应用代码不再读写；列保留未删',
  `deleted_at` datetime DEFAULT NULL COMMENT '注销时间（软删除）：NULL=正常，非NULL=已注销；所有用户查询都要过滤 deleted_at IS NULL',
  `token_valid_after` datetime DEFAULT NULL COMMENT '令牌生效起点：JWT 的 iat 不晚于它即视为失效（NULL=不限制；改密码/恢复账号时写入）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=467 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- =====================================================================
-- 第三十四轮（§H-H2）：RBAC 与问卷协作者
-- ★ 与 sql/migration-rbac.sql 保持逐字一致的结构（老库升级走那份脚本）。
-- =====================================================================
CREATE TABLE `t_role` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `code` varchar(50) NOT NULL COMMENT '角色码（唯一）：admin / user / 未来的自定义角色',
  `name` varchar(50) NOT NULL COMMENT '展示名',
  `is_builtin` tinyint NOT NULL DEFAULT '0' COMMENT '1=内置角色（不允许删除）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表（RBAC）';

CREATE TABLE `t_role_permission` (
  `id` int NOT NULL AUTO_INCREMENT,
  `role_id` int NOT NULL COMMENT '角色ID（t_role.id）',
  `permission_code` varchar(64) NOT NULL COMMENT '权限点，取值为 common/Permission 里的常量',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_id`,`permission_code`),
  KEY `idx_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-权限点关联表';

CREATE TABLE `t_user_role` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID（t_user.id）',
  `role_id` int NOT NULL COMMENT '角色ID（t_role.id）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-角色关联表';

CREATE TABLE `t_survey_collaborator` (
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

-- 系统种子数据（与 migration-rbac.sql 完全一致；重复导入安全）
INSERT IGNORE INTO `t_role` (`code`, `name`, `is_builtin`) VALUES ('admin', '管理员', 1);
INSERT IGNORE INTO `t_role` (`code`, `name`, `is_builtin`) VALUES ('user', '普通用户', 1);

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

INSERT IGNORE INTO `t_role_permission` (`role_id`, `permission_code`)
SELECT r.id, p.code FROM `t_role` r
JOIN (
    SELECT 'survey:edit'    AS code UNION ALL
    SELECT 'survey:publish' UNION ALL
    SELECT 'survey:data'    UNION ALL
    SELECT 'survey:delete'
) p
WHERE r.code = 'user';

INSERT IGNORE INTO `t_user_role` (`user_id`, `role_id`)
SELECT u.id, r.id
FROM `t_user` u
JOIN `t_role` r ON r.code = u.role
WHERE u.deleted_at IS NULL;

CREATE TABLE `t_survey_review` (
  `id` int NOT NULL AUTO_INCREMENT,
  `survey_id` int NOT NULL COMMENT '被举报的问卷',
  `reporter_id` int NOT NULL COMMENT '举报人（登录用户）',
  `reason` varchar(500) NOT NULL COMMENT '举报理由（用户填写）',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
  `action` varchar(20) DEFAULT NULL COMMENT '处理动作：SUSPEND / WARN / IGNORE（仅 APPROVED 时填）',
  `handled_by` int DEFAULT NULL COMMENT '处理人（管理员）',
  `handled_at` datetime DEFAULT NULL COMMENT '处理时间',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_survey` (`survey_id`),
  KEY `idx_reporter` (`reporter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问卷举报表';

-- ==================== IP 限制（高级设置包） ====================
-- ★ 唯一键(survey_id, ip) 才是并发防线；记录与答卷同事务写入。
CREATE TABLE `t_survey_ip` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `survey_id` int NOT NULL COMMENT '问卷 id',
  `ip` varchar(64) NOT NULL COMMENT '提交方连接 IP（不看 X-Forwarded-For，防伪造绕过）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_survey_ip` (`survey_id`, `ip`),
  KEY `idx_survey_ip_survey` (`survey_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问卷提交 IP（IP 限制用）';

-- ==================== H5 额度钱包（模拟付费） ====================
-- ★ amount 有符号（入账正 / 出账负），对账口径：SUM(amount) = balance。
-- ★ idempotency_key 全表唯一，扣款/退款/充值/赠送都靠它去重。
CREATE TABLE `t_wallet` (
  `user_id` int NOT NULL COMMENT '用户 id（一人一包）',
  `balance` int NOT NULL DEFAULT 0 COMMENT '可用额度（点）',
  `frozen` int NOT NULL DEFAULT 0 COMMENT '冻结额度（预留；当前业务未使用）',
  `version` int NOT NULL DEFAULT 0 COMMENT '版本号（辅助排查，不参与扣减判定）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='额度钱包';

CREATE TABLE `t_wallet_transaction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `type` varchar(32) NOT NULL COMMENT 'SIGNUP_BONUS / RECHARGE / PUBLISH / REFUND / ADMIN_ADJUST',
  `amount` int NOT NULL COMMENT '有符号：入账为正、出账为负',
  `balance_after` int NOT NULL DEFAULT 0 COMMENT '本笔之后的余额快照',
  `biz_id` varchar(64) DEFAULT NULL COMMENT '关联业务（如 surveyId）',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键（全表唯一）',
  `remark` varchar(200) DEFAULT NULL,
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_tx_idem` (`idempotency_key`),
  KEY `idx_wallet_tx_user` (`user_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='额度流水';

-- 现有用户初始化钱包并赠送 10 点（新机器建库时同样生效；幂等）
INSERT IGNORE INTO `t_wallet` (`user_id`, `balance`, `version`)
SELECT `id`, 10, 1 FROM `t_user` WHERE `deleted_at` IS NULL;

INSERT IGNORE INTO `t_wallet_transaction`
  (`user_id`, `type`, `amount`, `balance_after`, `biz_id`, `idempotency_key`, `remark`)
SELECT w.`user_id`, 'SIGNUP_BONUS', 10, w.`balance`, NULL, CONCAT('signup:', w.`user_id`),
       '初始化：赠送 10 点'
FROM `t_wallet` w
WHERE w.`balance` = 10 AND w.`version` = 1
  AND NOT EXISTS (
    SELECT 1 FROM `t_wallet_transaction` x WHERE x.`idempotency_key` = CONCAT('signup:', w.`user_id`)
  );

SET FOREIGN_KEY_CHECKS = 1;
