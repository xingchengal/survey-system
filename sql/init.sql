-- ============================================================
-- 问卷系统 · 数据库结构（MySQL 8，库名 survey_db，共 21 张表）
-- ============================================================
-- 来源：mysqldump --no-data --default-character-set=utf8mb4 survey_db
--       ★ 但**不是**原样 dump —— 里面手工补了"个人中心即将用到"的结构
--         （t_login_log 与 t_user 的两列），所以它代表**目标结构**，比任一时刻的现库更全。
-- 更新日期：2026-10-19（第四十五轮：考试判分，4 张表共 7 列）
--          2026-10-17（第二十八轮：补性能索引；此前已含 t_survey_version 与答卷质控 3 列）
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
  `total_score` int DEFAULT NULL COMMENT '本次答卷总分（提交那一刻的分值快照）；NULL=未判分',
  `earned_score` int DEFAULT NULL COMMENT '本次得分；NULL=未判分（普通问卷，或判分上线前的历史答卷）★ 统计一律只算非 NULL 的行',
  `is_passed` tinyint DEFAULT NULL COMMENT '是否及格：NULL=未设及格线或未判分；0=不及格；1=及格（得分 >= 及格分）',
  `checked_in` tinyint NOT NULL DEFAULT '0' COMMENT '是否已签到：0=未签到（默认），1=已签到',
  `checked_in_at` datetime DEFAULT NULL COMMENT '签到时间：取消签到时置回 NULL',
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
  `is_correct` tinyint DEFAULT NULL COMMENT '该题是否答对：1=对，0=错，NULL=未判分。★ 口径按题：多选一选项一行，同题所有行同值',
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
  `is_correct` tinyint NOT NULL DEFAULT '0' COMMENT '是否正确答案：0=否，1=是。★ 只有单选/多选/下拉有判分意义；判分按 content 比对（明细表没有 option_id）',
  `image_url` varchar(500) DEFAULT NULL COMMENT '选项配图：相对路径 /uploads/xxx.webp；NULL=无图',
  PRIMARY KEY (`id`),
  KEY `idx_question_id` (`question_id`)
) ENGINE=InnoDB AUTO_INCREMENT=8510 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目选项表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_upload` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '上传ID',
  `url` varchar(500) NOT NULL COMMENT '相对路径 /uploads/xxx.webp（与 t_option.image_url 同口径）',
  `original_name` varchar(255) DEFAULT NULL COMMENT '原始文件名（只作审计，不参与存储路径）',
  `size_bytes` int NOT NULL DEFAULT '0' COMMENT '文件字节数',
  `mime` varchar(100) DEFAULT NULL COMMENT '内容类型（按文件头探测，不信任请求头）',
  `created_by` int DEFAULT NULL COMMENT '上传人ID',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间（GC 宽限期从这里算）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_upload_url` (`url`),
  KEY `idx_upload_created` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上传文件登记（GC 与容量审计）';
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
  `score` int NOT NULL DEFAULT '0' COMMENT '分值：0=不参与判分；>0 表示考试模式下计分（只有单选/多选/下拉允许 >0，保存时校验）',
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
  `grade_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '考试模式：0=普通问卷（不判分），1=判分（提交时算分并写入 t_answer 三个分数字段）',
  `pass_score` int DEFAULT NULL COMMENT '及格分：NULL=不判及格（只出分）；开启考试模式时取值必须在 0 ~ 本卷总分之间',
  `time_limit_seconds` int DEFAULT NULL COMMENT '答题限时（秒）：NULL=不限；前端倒计时，服务端按上报 duration_ms + 10s 宽限校验（非防作弊）',
  `show_answer_after_submit` tinyint NOT NULL DEFAULT '0' COMMENT '提交后是否展示正确答案：0=否（只出分），1=是；只在 grade_enabled=1 时有意义',
  `require_login` tinyint NOT NULL DEFAULT '0' COMMENT '是否仅限登录用户填写：0=匿名可填（默认），1=必须先登录；开启后只收带 token 的 POST /answer/submit',
  `checkin_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '是否启用签到核销：0=否（默认），1=是；作者在答卷列表里打勾',
  `show_participants` tinyint NOT NULL DEFAULT '0' COMMENT '是否公开报名名单：0=否（默认），1=是；开启后填写页显示前 50 位报名者',
  `shuffle_questions` tinyint NOT NULL DEFAULT '0' COMMENT '题目乱序：0=按 sort_order，1=填写页随机',
  `shuffle_options` tinyint NOT NULL DEFAULT '0' COMMENT '选项乱序：0=按 sort_order，1=填写页随机',
  `questions_per_attempt` int NOT NULL DEFAULT '0' COMMENT '每份答卷抽题数：0=不抽，N=从全部题目随机抽 N 道（判分总分按抽到的题算）',
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
  `disabled` tinyint NOT NULL DEFAULT 0 COMMENT '禁用标记：0=正常，1=已禁用（禁止登录；数据保留；管理员可解禁）',
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
  `code` varchar(50) NOT NULL COMMENT '角色码（唯一）：admin=超管 / subadmin=次级管理员 / user=普通用户',
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

-- 次级管理员的**逐项授权**（与角色权限取并集；见 RoleMapper.findPermissionCodesByUserId）
-- ★ 为什么按用户存：每个次级管理员可以有不同的模块组合，不必为每个人建一个角色。
CREATE TABLE `t_user_permission` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '用户ID（t_user.id）',
  `permission_code` varchar(64) NOT NULL COMMENT '权限点，取值为 common/Permission 里的常量；只允许 ADMIN_GRANTABLE',
  `created_by` int DEFAULT NULL COMMENT '授权人（超管）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_permission` (`user_id`,`permission_code`),
  KEY `idx_user_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-权限点直接授权（次级管理员）';

CREATE TABLE `t_survey_collaborator` (
  `id` int NOT NULL AUTO_INCREMENT,
  `survey_id` int NOT NULL COMMENT '问卷ID',
  `user_id` int NOT NULL COMMENT '被邀请的用户ID',
  `collaborator_role` varchar(20) NOT NULL COMMENT 'viewer=只读 / editor=可编辑 / analyst=可看数据',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '邀请状态：PENDING=待接受 / ACCEPTED=已生效 / REJECTED=已拒绝 / REVOKED=作者撤销；只有 ACCEPTED 参与权限判定',
  `created_by` int DEFAULT NULL COMMENT '邀请人（问卷作者或管理员）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_survey_user` (`survey_id`,`user_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_user_status` (`user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问卷协作者表';

CREATE TABLE `t_feedback` (
  `id` int NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL COMMENT '发起人（只允许本人读写）',
  `type` varchar(20) NOT NULL COMMENT 'FEEDBACK=反馈 / SERVICE=客服工单',
  `subject` varchar(200) NOT NULL COMMENT '主题（≤200 字）',
  `status` varchar(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN=处理中 / CLOSED=已关闭；只关闭不删除',
  `handled_by` int DEFAULT NULL COMMENT '最近处理的管理员（通知按它精准发）',
  `last_message_id` bigint DEFAULT NULL COMMENT '最新消息 id（列表排序与轮询用）',
  `last_message_time` datetime DEFAULT NULL COMMENT '最新消息时间',
  `last_message_preview` varchar(200) DEFAULT NULL COMMENT '最新消息预览（截断）',
  `user_last_read_message_id` bigint DEFAULT NULL COMMENT '用户已读水位',
  `admin_last_read_message_id` bigint DEFAULT NULL COMMENT '管理员已读水位',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `open_service_user_id` int GENERATED ALWAYS AS (
    CASE WHEN `type` = 'SERVICE' AND `status` = 'OPEN' THEN `user_id` ELSE NULL END
  ) STORED COMMENT '并发保护：同一用户最多一个 OPEN SERVICE 工单；其他情况为 NULL（唯一键允许多个 NULL）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_open_service` (`open_service_user_id`),
  KEY `idx_feedback_user` (`user_id`, `id`),
  KEY `idx_feedback_status` (`status`, `id`),
  KEY `idx_feedback_handled` (`handled_by`, `status`),
  KEY `idx_feedback_last` (`last_message_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='反馈 / 客服工单头';

CREATE TABLE `t_feedback_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `feedback_id` int NOT NULL COMMENT '所属工单',
  `sender_id` int NOT NULL COMMENT '发送人 id',
  `sender_role` varchar(20) NOT NULL COMMENT 'user / admin',
  `content` varchar(2000) NOT NULL COMMENT '纯文本，≤2000 字；渲染禁止 v-html',
  `client_msg_id` varchar(64) DEFAULT NULL COMMENT '客户端幂等键（UUID）；NULL 时不参与唯一键',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_feedback_client_msg` (`feedback_id`, `client_msg_id`),
  KEY `idx_feedback_msg` (`feedback_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='反馈 / 客服消息线程';

-- 系统种子数据（与 migration-rbac.sql + migration-subadmin-permissions.sql 完全一致；重复导入安全）
INSERT IGNORE INTO `t_role` (`code`, `name`, `is_builtin`) VALUES ('admin', '超级管理员', 1);
INSERT IGNORE INTO `t_role` (`code`, `name`, `is_builtin`) VALUES ('subadmin', '次级管理员', 1);
INSERT IGNORE INTO `t_role` (`code`, `name`, `is_builtin`) VALUES ('user', '普通用户', 1);

-- admin（超管）：全部 13 个权限点（代码里还有兜底放行，这里给全是为了矩阵可展示/可审计）
INSERT IGNORE INTO `t_role_permission` (`role_id`, `permission_code`)
SELECT r.id, p.code FROM `t_role` r
JOIN (
    SELECT 'survey:edit'   AS code UNION ALL
    SELECT 'survey:publish' UNION ALL
    SELECT 'survey:data'    UNION ALL
    SELECT 'survey:delete'  UNION ALL
    SELECT 'user:manage'    UNION ALL
    SELECT 'review:handle'  UNION ALL
    SELECT 'wallet:manage'  UNION ALL
    SELECT 'survey:manage'  UNION ALL
    SELECT 'template:manage' UNION ALL
    SELECT 'announcement:manage' UNION ALL
    SELECT 'log:view'       UNION ALL
    SELECT 'feedback:handle' UNION ALL
    SELECT 'system:view'
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

-- ==================== 模板分类清单 ====================
-- ★ 分类允许"暂时没有模板"：模板管理页的「+ 新增分类」就是往这张表写。
-- ★ 与 t_survey.template_category 的关系：那个字段仍决定"这份模板属于哪个分类"；
--   本表只提供"有哪些可选分类、按什么顺序展示"，不反向约束 t_survey。
CREATE TABLE `t_template_category` (
  `id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL COMMENT '分类名（全表唯一）',
  `sort_order` int NOT NULL DEFAULT 0 COMMENT '展示顺序，小的在前；新增追加到末尾',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_category_name` (`name`),
  KEY `idx_template_category_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模板分类清单';

-- 7 个预置分类（与 TemplateSeederService 的预置模板分类一致；已有则跳过）
INSERT IGNORE INTO `t_template_category` (`name`, `sort_order`) VALUES
  ('满意度', 1), ('报名', 2), ('反馈', 3), ('调研', 4),
  ('健康', 5), ('教育', 6), ('其他', 7);

-- 把库里已经用过、但不在上面 7 类里的分类补进来（老库升级后清单不缺项）
INSERT IGNORE INTO `t_template_category` (`name`, `sort_order`)
SELECT DISTINCT `template_category`, 100
FROM `t_survey`
WHERE `is_template` = 1 AND `deleted` = 0
  AND `template_category` IS NOT NULL AND TRIM(`template_category`) <> '';

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
