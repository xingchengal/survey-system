-- ============================================================
-- 问卷设置：给 t_survey 加 5 列（填答限制 + 提交后展示）
--
-- 对应问卷星「基本设置 / 提交后显示」里最常用的几项：
--   access_password  答题密码（NULL = 不需要密码）
--   max_responses    最多收集多少份答卷（NULL = 不限）
--   thank_text       提交后的感谢语（NULL = 用系统默认文案）
--   redirect_url     提交后跳转的链接（NULL = 不跳转；只允许 http/https）
--   public_result    是否公开结果页（0 = 不公开，1 = 开启；默认 0）
--
-- 用法：
--   mysql.exe -uroot -p<口令> --default-character-set=utf8mb4 survey_db -e "source <此文件>"
-- ★ 不可重复执行（ALTER 重复加列报 1060）。执行前先查：
--     SELECT COLUMN_NAME FROM information_schema.COLUMNS
--      WHERE TABLE_SCHEMA='survey_db' AND TABLE_NAME='t_survey'
--        AND COLUMN_NAME IN ('access_password','max_responses','thank_text','redirect_url','public_result');
-- ★ 撤销：ALTER TABLE t_survey DROP COLUMN access_password, DROP COLUMN max_responses,
--          DROP COLUMN thank_text, DROP COLUMN redirect_url, DROP COLUMN public_result;
-- ============================================================

ALTER TABLE `t_survey`
  ADD COLUMN `access_password` varchar(20) DEFAULT NULL
    COMMENT '答题密码：NULL=不需要；非空时加载题目与提交答卷都要带 X-Survey-Password',
  ADD COLUMN `max_responses` int DEFAULT NULL
    COMMENT '最多收集多少份答卷：NULL=不限；达到后拒绝新提交',
  ADD COLUMN `thank_text` varchar(500) DEFAULT NULL
    COMMENT '提交后的感谢语：NULL=默认「提交成功，感谢您的参与」',
  ADD COLUMN `redirect_url` varchar(500) DEFAULT NULL
    COMMENT '提交后跳转链接：NULL=不跳转；只允许 http/https',
  ADD COLUMN `public_result` tinyint NOT NULL DEFAULT '0'
    COMMENT '是否公开结果页：0=不公开，1=开启（匿名可访问 /r/{shareCode}）';

-- 校验
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'survey_db' AND TABLE_NAME = 't_survey'
  AND COLUMN_NAME IN ('access_password', 'max_responses', 'thank_text', 'redirect_url', 'public_result');
