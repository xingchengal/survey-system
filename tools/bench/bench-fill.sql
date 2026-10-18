-- ============================================================
-- 压测夹具：一份专用问卷 + 2 万答卷 + 约 12 万明细
--
-- 用途：复现 README「技术亮点 8. 性能基线」里那组数字。
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 survey_db < bench-fill.sql
-- 清理：bench-cleanup.sql（按 share_code='bench0001' 定位，可重复执行）
--
-- ★ owner 取**第一个管理员**，不写死 id —— 原来写的是 demo_user1(555)，
--   在别人的库里那个 id 可能不存在（问卷会挂在一个幽灵 user_id 上）。
--   压测用 admin token，归属校验对 admin 直接放行，所以 owner 是谁不影响压测；
--   写一个真实存在的 id 能让它在「我的问卷」里看得到，排查时更直观。
-- ★ 这份夹具会**真的往库里写 2 万答卷 + 12 万明细**（灌入约 2.5 秒），
--   只应该在**演示库/独立库**上跑，别对着生产库跑。
-- ============================================================
SET SESSION cte_max_recursion_depth = 100000;

SET @owner = (SELECT id FROM t_user WHERE role = 'admin' ORDER BY id LIMIT 1);

-- 1) 问卷
INSERT INTO t_survey(user_id, title, description, status, share_code, created_time, start_time, view_count, is_template)
VALUES (@owner, '【压测样本】可整段删除', 'benchmark fixture', 1, 'bench0001', NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY, 0, 0);
SET @sid = LAST_INSERT_ID();

-- 2) 题目：3 单选 + 3 多选 + 2 填空
INSERT INTO t_question(survey_id, type, title, required, sort_order) VALUES
(@sid, 1, '压测单选1：最常使用的设备', 1, 0),
(@sid, 1, '压测单选2：年龄段', 1, 1),
(@sid, 1, '压测单选3：使用频率', 1, 2),
(@sid, 2, '压测多选1：关注的功能（多选）', 1, 3),
(@sid, 2, '压测多选2：遇到的困难（多选）', 0, 4),
(@sid, 2, '压测多选3：希望改进的方面（多选）', 0, 5),
(@sid, 3, '压测填空1：您的建议', 0, 6),
(@sid, 3, '压测填空2：备注', 0, 7);

SELECT id INTO @q1 FROM t_question WHERE survey_id = @sid AND sort_order = 0;
SELECT id INTO @q2 FROM t_question WHERE survey_id = @sid AND sort_order = 1;
SELECT id INTO @q3 FROM t_question WHERE survey_id = @sid AND sort_order = 2;
SELECT id INTO @q4 FROM t_question WHERE survey_id = @sid AND sort_order = 3;
SELECT id INTO @q5 FROM t_question WHERE survey_id = @sid AND sort_order = 4;
SELECT id INTO @q6 FROM t_question WHERE survey_id = @sid AND sort_order = 5;
SELECT id INTO @q7 FROM t_question WHERE survey_id = @sid AND sort_order = 6;

-- 3) 选项
INSERT INTO t_option(question_id, content, sort_order) VALUES
(@q1, '手机', 0), (@q1, '平板', 1), (@q1, '电脑', 2), (@q1, '其他', 3),
(@q2, '18岁以下', 0), (@q2, '18-25岁', 1), (@q2, '26-35岁', 2), (@q2, '36岁以上', 3),
(@q3, '每天使用', 0), (@q3, '每周几次', 1), (@q3, '每月几次', 2), (@q3, '很少使用', 3),
(@q4, '界面美观', 0), (@q4, '操作简单', 1), (@q4, '统计准确', 2), (@q4, '导出方便', 3), (@q4, '分享便捷', 4),
(@q5, '加载慢', 0), (@q5, '题型太少', 1), (@q5, '不支持条件跳转', 2), (@q5, '移动端适配差', 3), (@q5, '其他', 4),
(@q6, '题目模板', 0), (@q6, '协作编辑', 1), (@q6, '数据分析', 2), (@q6, 'API对接', 3), (@q6, '多语言', 4);

-- 4) 2 万份匿名答卷，提交时间散布在过去 30 天
INSERT INTO t_answer(survey_id, user_id, submit_time)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 20000)
SELECT @sid, NULL, NOW() - INTERVAL (n * 3 % 43200) MINUTE FROM seq;

SET @amin = (SELECT MIN(id) FROM t_answer WHERE survey_id = @sid);
SET @amax = (SELECT MAX(id) FROM t_answer WHERE survey_id = @sid);

-- 5) 明细：Q1/Q2 全量各 2 万行，Q3 半量 1 万行，Q4 全量 2 万 + 偶数答卷补一行 1 万，
--    Q5 全量 2 万行，Q7 填空 2 万行  => 合计约 12 万行
INSERT INTO t_answer_detail(answer_id, question_id, content)
SELECT a.id, @q1, o.content FROM t_answer a
JOIN t_option o ON o.question_id = @q1 AND o.sort_order = MOD(a.id, 4)
WHERE a.survey_id = @sid;

INSERT INTO t_answer_detail(answer_id, question_id, content)
SELECT a.id, @q2, o.content FROM t_answer a
JOIN t_option o ON o.question_id = @q2 AND o.sort_order = MOD(a.id, 4)
WHERE a.survey_id = @sid;

INSERT INTO t_answer_detail(answer_id, question_id, content)
SELECT a.id, @q3, o.content FROM t_answer a
JOIN t_option o ON o.question_id = @q3 AND o.sort_order = MOD(a.id, 4)
WHERE a.survey_id = @sid AND MOD(a.id, 2) = 0;

INSERT INTO t_answer_detail(answer_id, question_id, content)
SELECT a.id, @q4, o.content FROM t_answer a
JOIN t_option o ON o.question_id = @q4 AND o.sort_order = MOD(a.id, 5)
WHERE a.survey_id = @sid;

INSERT INTO t_answer_detail(answer_id, question_id, content)
SELECT a.id, @q4, o.content FROM t_answer a
JOIN t_option o ON o.question_id = @q4 AND o.sort_order = MOD(a.id + 2, 5)
WHERE a.survey_id = @sid AND MOD(a.id, 2) = 0;

INSERT INTO t_answer_detail(answer_id, question_id, content)
SELECT a.id, @q5, o.content FROM t_answer a
JOIN t_option o ON o.question_id = @q5 AND o.sort_order = MOD(a.id, 5)
WHERE a.survey_id = @sid;

INSERT INTO t_answer_detail(answer_id, question_id, content)
SELECT a.id, @q7, CONCAT('压测回答-', a.id, '：这是一条用于压测的填空内容')
FROM t_answer a WHERE a.survey_id = @sid;

-- 6) 结果
SELECT @sid AS survey_id, @amin AS first_answer_id, @amax AS last_answer_id;
SELECT COUNT(*) AS answers FROM t_answer WHERE survey_id = @sid;
SELECT COUNT(*) AS details FROM t_answer_detail d
JOIN t_answer a ON d.answer_id = a.id WHERE a.survey_id = @sid;

-- 【回滚】压测结束后整段删除（按 survey_id，不按条数）：
--   DELETE d FROM t_answer_detail d JOIN t_answer a ON d.answer_id = a.id WHERE a.survey_id = <sid>;
--   DELETE FROM t_answer WHERE survey_id = <sid>;
--   DELETE FROM t_option WHERE question_id IN (SELECT id FROM t_question WHERE survey_id = <sid>);
--   DELETE FROM t_question WHERE survey_id = <sid>;
--   DELETE FROM t_survey WHERE id = <sid>;
