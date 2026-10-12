-- ============================================================================
-- 问卷系统 · 库基线核查（一条 SQL 打印所有关键计数）
-- ============================================================================
-- 用途：每轮开工前 / 收工后各跑一次，确认"没有留下垃圾、也没把用户的数据删掉"。
--
-- 怎么跑（PowerShell，口令见 survey-backend/src/main/resources/application-local.yml）：
--   mysql.exe -uroot -p<口令> --default-character-set=utf8mb4 survey_db -e "source C:/Users/1/Desktop/dsh/IdeaProjects/sql/baseline-check.sql"
--   （也可以直接把下面整段贴进 mysql 客户端）
--
-- ★★ 两条重要经验（都踩过）：
--   1. **数字会变，别慌**：用户自己会测（注册账号、答题、编辑问卷）。
--      编辑问卷会重建题目/选项，答一份卷会让答卷/明细/通知各 +1。
--      发现数字与上一轮记录不一致时，先看"是谁什么时候造的"（见文件末尾的两条查询），
--      再决定动不动 —— 不要把用户自己的测试数据当垃圾删掉。
--   2. **清测试数据按 MAX(id) 区间删，不要按条数**：历史上删过日志，表里 id 不连续，
--      "应该有 5 条"这种判断会误删。
-- ============================================================================

SELECT '===== 关键计数 =====' AS section;
SELECT
    (SELECT COUNT(*) FROM t_user)                                                       AS 用户数,
    (SELECT COUNT(*) FROM t_survey WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0)) AS 非模板未删问卷,
    (SELECT COUNT(*) FROM t_survey WHERE is_template = 1)                               AS 模板数,
    (SELECT COUNT(*) FROM t_survey WHERE deleted = 1)                                   AS 回收站问卷,
    (SELECT COUNT(*) FROM t_question)                                                   AS 题目数,
    (SELECT COUNT(*) FROM t_option)                                                     AS 选项数,
    (SELECT COUNT(*) FROM t_answer)                                                     AS 答卷数,
    (SELECT COUNT(*) FROM t_answer_detail)                                              AS 答卷明细数,
    (SELECT COUNT(*) FROM t_notification)                                               AS 通知数,
    (SELECT COUNT(*) FROM t_error_log)                                                  AS 异常日志数,
    (SELECT COUNT(*) FROM t_operation_log)                                              AS 操作日志数,
    (SELECT COUNT(*) FROM t_refresh_token)                                              AS 刷新令牌行数;

SELECT '===== MAX(id)（清理测试数据时按区间删的锚点）=====' AS section;
SELECT
    (SELECT MAX(id) FROM t_answer)        AS 答卷maxid,
    (SELECT MAX(id) FROM t_answer_detail) AS 明细maxid,
    (SELECT MAX(id) FROM t_notification)  AS 通知maxid,
    (SELECT MAX(id) FROM t_error_log)     AS 异常maxid,
    (SELECT MAX(id) FROM t_operation_log) AS 操作maxid,
    (SELECT MAX(id) FROM t_survey)        AS 问卷maxid;

SELECT '===== 用户清单（确认没有测试账号残留）=====' AS section;
SELECT id, username, nickname, phone, role, created_time FROM t_user ORDER BY id;

SELECT '===== 最近 10 条答卷（判断"新答卷是谁什么时候造的"）=====' AS section;
SELECT a.id, a.survey_id, s.title, a.user_id, a.submit_time
FROM t_answer a LEFT JOIN t_survey s ON s.id = a.survey_id
ORDER BY a.id DESC LIMIT 10;

SELECT '===== 最近 10 条通知 =====' AS section;
SELECT id, user_id, type, LEFT(title, 30) AS title, is_read, create_time
FROM t_notification ORDER BY id DESC LIMIT 10;

-- ============================================================================
-- 清理"我造的"测试数据时的模板（★ 跑之前先看清楚 where 条件！）
-- ============================================================================
-- DELETE FROM t_refresh_token WHERE user_id IN (SELECT id FROM t_user WHERE username LIKE 'e2e%');
-- DELETE FROM t_user          WHERE username LIKE 'e2e%';
-- DELETE FROM t_answer        WHERE id > <基线 maxid>;
-- DELETE FROM t_answer_detail WHERE id > <基线 明细maxid>;
-- DELETE FROM t_notification  WHERE id > <基线 通知maxid>;
