-- ============================================================
-- 压测夹具的清理脚本（按 survey_id 与 username 整段删除，**不按条数**）
-- 用法：mysql.exe -uroot -p<口令> --default-character-set=utf8mb4 survey_db -e "source <此文件>"
-- ★ 执行前先看一眼下面的"清理前"查询结果，确认删的确实是压测夹具。
-- ============================================================

-- 清理前：确认目标（应当是 1 份问卷 / 约 2 万答卷 / 约 12 万明细）
SELECT s.id AS survey_id, s.title, s.share_code,
       (SELECT COUNT(*) FROM t_answer a WHERE a.survey_id = s.id) AS answers,
       (SELECT COUNT(*) FROM t_answer_detail d JOIN t_answer a ON d.answer_id = a.id
         WHERE a.survey_id = s.id) AS details
FROM t_survey s WHERE s.share_code = 'bench0001';

SET @sid = (SELECT id FROM t_survey WHERE share_code = 'bench0001');

-- 子表先删（顺序：明细 → 答卷 → 选项 → 题目 → 问卷）
-- ★ 按 survey_id 删；@sid 为 NULL 时这些语句不会误删任何东西（`survey_id = NULL` 恒不成立）
DELETE d FROM t_answer_detail d
JOIN t_answer a ON d.answer_id = a.id
WHERE a.survey_id = @sid;

DELETE FROM t_answer WHERE survey_id = @sid;

DELETE o FROM t_option o
JOIN t_question q ON o.question_id = q.id
WHERE q.survey_id = @sid;

DELETE FROM t_question WHERE survey_id = @sid;

DELETE FROM t_survey WHERE id = @sid;

-- 压测专用账号（本轮用 SQL 建的 PBKDF2 账号，与注册接口同口径）
DELETE FROM t_user WHERE username IN ('benchadmin', 'bench_admin');

-- 清理后：三项都应当是 0 / 空
SELECT (SELECT COUNT(*) FROM t_survey WHERE share_code = 'bench0001') AS 残留问卷,
       (SELECT COUNT(*) FROM t_user WHERE username IN ('benchadmin', 'bench_admin')) AS 残留账号;
