-- =====================================================================
-- 迁移：举报审核（§H-H1-2）
-- 性质：纯新增表（可重复执行）
-- 回滚：DROP TABLE t_survey_review;
--
-- ★ status 取值只有 PENDING / APPROVED / REJECTED。
--   "通过" 的动作记录在 action 里（H1-3 用 SUSPEND / WARN / IGNORE），
--   不复用 status —— 一个字段一个含义，别把"审批结果"和"处理动作"混在一起。
-- =====================================================================
CREATE TABLE IF NOT EXISTS `t_survey_review` (
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

-- 自检
SELECT COUNT(*) AS review_rows FROM `t_survey_review`;
