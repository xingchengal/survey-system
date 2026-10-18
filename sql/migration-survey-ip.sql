-- =====================================================================
-- 迁移：IP 限制（高级设置包的一部分）
-- 性质：给 t_survey 加一列 + 新增 t_survey_ip（可重复执行）
-- 回滚：DROP TABLE t_survey_ip; ALTER TABLE t_survey DROP COLUMN ip_limit;
--
-- ★ 为什么单独一张表而不是在 t_answer 上按 IP 查重：
--   ① 匿名答卷的 t_answer.user_id 恒为 null，没有别的"人"标识；
--   ② 需要的是"同一 IP 对同一问卷只能一次"这一条唯一约束，
--      表上的 UNIQUE(survey_id, ip) 才是最可靠的并发防线（不靠先查后插）。
-- ★ 只记录提交成功的 IP：记录与答卷在**同一事务**里写，提交失败会一起回滚，
--   不会出现"答卷没落库，IP 却被占了"。
-- ★ 已知误伤：同一 NAT / 校园网 / 公司出口 IP 的多个真实用户会被当成同一个人。
--   所以这是**作者主动开启**的选项（默认关闭），并在设置里写明风险。
-- =====================================================================

-- 1) t_survey.ip_limit（先查列，保证可重复执行）
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey' AND COLUMN_NAME = 'ip_limit'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_survey` ADD COLUMN `ip_limit` tinyint NOT NULL DEFAULT 0 COMMENT ''IP 限制：0=关闭；1=同一 IP 只能提交一次（高级设置包）''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) 提交 IP 记录（唯一键 = 真正的并发防线）
CREATE TABLE IF NOT EXISTS `t_survey_ip` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `survey_id` int NOT NULL COMMENT '问卷 id',
  `ip` varchar(64) NOT NULL COMMENT '提交方连接 IP（不看 X-Forwarded-For，防伪造绕过）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_survey_ip` (`survey_id`, `ip`),
  KEY `idx_survey_ip_survey` (`survey_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问卷提交 IP（IP 限制用）';

-- 自检
SELECT COUNT(*) AS ip_limit_column FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey' AND COLUMN_NAME = 'ip_limit';
SELECT COUNT(*) AS survey_ip_rows FROM `t_survey_ip`;
