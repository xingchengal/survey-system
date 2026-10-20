-- =====================================================================
-- 数据清理：协作者表里的管理员账号（t_survey_collaborator）
-- 性质：**只删数据、不改结构**；可重复执行（没有命中行时删除 0 行）。
-- 原因：协作者只能邀请普通用户。修复前的 invite 未校验 t_user.role，超管 / 次级管理员
--       可被普通作者拉进名单：账号暴露给作者、可被改角色 / 移除、占协作者名额，
--       ACCEPTED 行在超 2 名时还会扣作者的点。
-- 回滚：删除的是本就不该存在的授权行，无需回滚；执行前先跑 ① 留档。
-- =====================================================================

-- ① 核对：先看要删哪些行（执行 DELETE 前先跑这段并留存结果）
SELECT c.id, c.survey_id, c.user_id, u.username, u.role,
       c.collaborator_role, c.status, c.created_time
FROM `t_survey_collaborator` c
JOIN `t_user` u ON u.id = c.user_id
WHERE u.role IN ('admin', 'subadmin')
ORDER BY c.id;

-- ② 删除：只删指向管理员账号的协作者行
DELETE c
FROM `t_survey_collaborator` c
JOIN `t_user` u ON u.id = c.user_id
WHERE u.role IN ('admin', 'subadmin');

-- ---------- 自检 ----------
-- ③ 应为 0：协作者表里不再有管理员账号
SELECT COUNT(*) AS staff_rows_left
FROM `t_survey_collaborator` c
JOIN `t_user` u ON u.id = c.user_id
WHERE u.role IN ('admin', 'subadmin');
