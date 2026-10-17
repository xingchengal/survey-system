-- ============================================================
-- 问卷版本快照：新增 t_survey_version（只新增表，不改动任何现有数据）
--
-- 用法：mysql.exe -uroot -p<口令> --default-character-set=utf8mb4 survey_db -e "source <此文件>"
-- ★ 可重复执行（IF NOT EXISTS）。
-- ★ 若要撤销：DROP TABLE t_survey_version;
--   注意：撤销后已生成的版本历史会全部丢失（但不影响问卷与答卷本身）。
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_survey_version` (
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

-- 校验（应当返回一行）
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'survey_db' AND TABLE_NAME = 't_survey_version'
ORDER BY ORDINAL_POSITION;
