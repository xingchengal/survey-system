-- ============================================
-- 迁移：t_answer 加唯一索引 uk_survey_user
--   语义：同一**登录用户**对同一问卷只能有一行（防重的最后一道防线）
--   适用：已建好的旧库（新建库用 init.sql，其中已包含这条索引）
--   执行：Navicat 里逐条运行，或 mysql -e "source <本文件>"
--   日期：2026-10-12
-- ============================================
-- ★ 为什么不需要改匿名规则：
--   MySQL 唯一索引**不约束 NULL** ⇒ 匿名答卷（user_id IS NULL）天然不受这条索引限制，
--   正好等于"登录防重、匿名不防重"。
--   ⇒ 绝对**不要**执行 `UPDATE t_answer SET user_id = 0 WHERE user_id IS NULL;`
--     （那是早期注释里的错误推演：把 NULL 换成 0 会让所有匿名答卷互相冲突）
-- ★ 副作用：同一登录用户对同一问卷永久只能有一行（将来做"删后重填"会被它挡住）

-- 第 1 步：查历史重复行 —— 必须返回 0 行，否则第 2 步会失败
SELECT survey_id, user_id, COUNT(*) AS cnt
FROM t_answer
WHERE user_id IS NOT NULL
GROUP BY survey_id, user_id
HAVING COUNT(*) > 1;

-- 第 2 步：加索引
--   若报 1061 Duplicate key name 'uk_survey_user' ⇒ 索引已存在，跳过即可
--   若报 1062 Duplicate entry ⇒ 第 1 步之后又产生了重复行，先清掉重复行再执行
ALTER TABLE t_answer
  ADD UNIQUE KEY uk_survey_user (survey_id, user_id);

-- 第 3 步：核对（应见 Key_name = uk_survey_user、Non_unique = 0、Seq_in_index 1/2 = survey_id/user_id）
SHOW INDEX FROM t_answer WHERE Key_name = 'uk_survey_user';
