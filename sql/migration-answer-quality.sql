-- ============================================================
-- 答卷无效样本质控：给 t_answer 加 3 列（纯新增，可逆）
--
-- 用法：mysql.exe -uroot -p<口令> --default-character-set=utf8mb4 survey_db -e "source <此文件>"
-- ★ 可重复执行不了（ALTER 重复加列会报 1060）—— 加列前先查一遍：
--     SELECT COLUMN_NAME FROM information_schema.COLUMNS
--      WHERE TABLE_SCHEMA='survey_db' AND TABLE_NAME='t_answer' AND COLUMN_NAME='quality_flag';
--   返回空才需要执行。
-- ★ 撤销：ALTER TABLE t_answer DROP COLUMN duration_ms, DROP COLUMN quality_flag, DROP COLUMN quality_reasons;
--   撤销会丢掉已打的质量标记（不影响答卷与明细本身），重算需要重新保存答卷。
-- ============================================================

ALTER TABLE `t_answer`
  ADD COLUMN `duration_ms` int DEFAULT NULL
    COMMENT '答题耗时（毫秒）。由答题页上报；NULL=未上报。★ 客户端值，可伪造，只作质控信号、不作安全边界',
  ADD COLUMN `quality_flag` tinyint NOT NULL DEFAULT '0'
    COMMENT '质控标记：0=正常，1=可疑（命中任一无效样本规则，规则码见 quality_reasons）',
  ADD COLUMN `quality_reasons` varchar(200) DEFAULT NULL
    COMMENT '命中的规则码，逗号分隔：TOO_FAST=过快 / STRAIGHT_LINE=直线作答 / OPTION_MISMATCH=答案不在选项里 / DUP_TITLE_CONFLICT=同题干答案不一致';

-- 校验：应当返回 3 行（另加原有的 submit_time 等）
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'survey_db' AND TABLE_NAME = 't_answer'
  AND COLUMN_NAME IN ('duration_ms', 'quality_flag', 'quality_reasons');
