-- =====================================================================
-- 迁移：协作者邀请状态（t_survey_collaborator.status）
-- 性质：**加列 + 回填**（可重复执行：列/索引已存在则跳过；回填只补非法值）
-- 回滚：ALTER TABLE `t_survey_collaborator` DROP COLUMN `status`;
--       ALTER TABLE `t_survey_collaborator` DROP INDEX `idx_user_status`;
--       ★ 回滚会丢「待接受 / 已拒绝」状态；已经生效的协作者（ACCEPTED）不受影响。
--
-- ★ 语义：PENDING=邀请待被邀请人确认 / ACCEPTED=已生效 / REJECTED=已拒绝 / REVOKED=作者撤销。
--   - 存量行一律 ACCEPTED：迁移前邀请即生效，不能因为加列把现有协作关系冻结。
--   - 新增行默认 PENDING；被邀请人接受后改 ACCEPTED。**只有 ACCEPTED 参与权限判定**。
--   - 拒绝不物理删除，行保留为 REJECTED；作者撤销未处理的邀请 → REVOKED（撤销后接受接口 400）。
--   - 再次邀请把同一行重置为 PENDING；REJECTED / REVOKED 都不占名额、不重复扣费。
--   - 唯一键 uk_survey_user 不变：同一问卷同一用户始终只有一行，status 是它的生命周期。
-- ★ 本脚本只改结构；依赖新列的代码、init.sql 同步、接口与前端改动都在用户确认后做。
-- =====================================================================

-- ① 加列：先用 DEFAULT 'ACCEPTED' 让存量行直接落成已生效，②再把默认值切到 PENDING。
SET @col_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey_collaborator' AND COLUMN_NAME = 'status'
);
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE `t_survey_collaborator` ADD COLUMN `status` varchar(20) NOT NULL DEFAULT ''ACCEPTED'' COMMENT ''邀请状态：PENDING=待接受 / ACCEPTED=已生效 / REJECTED=已拒绝 / REVOKED=作者撤销；只有 ACCEPTED 参与权限判定''',
  'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ② 默认值切到 PENDING：此后新邀请必须先被接受才生效；存量行已是 ACCEPTED，不受影响。
ALTER TABLE `t_survey_collaborator`
  MODIFY COLUMN `status` varchar(20) NOT NULL DEFAULT 'PENDING'
  COMMENT '邀请状态：PENDING=待接受 / ACCEPTED=已生效 / REJECTED=已拒绝 / REVOKED=作者撤销；只有 ACCEPTED 参与权限判定';

-- ③ 兜底回填：只在手工加出空值/非法值时触发；不会把已有的 PENDING / REJECTED 改成 ACCEPTED。
UPDATE `t_survey_collaborator`
SET `status` = 'ACCEPTED'
WHERE `status` IS NULL OR `status` NOT IN ('PENDING', 'ACCEPTED', 'REJECTED', 'REVOKED');

-- ④ 组合索引：被邀请人查「我的邀请」按 user_id + status 过滤（已有 idx_user_id，补组合索引）。
SET @idx_exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey_collaborator' AND INDEX_NAME = 'idx_user_status'
);
SET @ddl2 = IF(@idx_exists = 0,
  'CREATE INDEX `idx_user_status` ON `t_survey_collaborator` (`user_id`, `status`)',
  'SELECT 1');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- ---------- 自检 ----------
-- ① 列应为 varchar(20) / NOT NULL / 默认 PENDING
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey_collaborator' AND COLUMN_NAME = 'status';

-- ② 状态分布：迁移后应为 accepted = total_rows，pending / rejected / revoked / invalid 全 0
SELECT COUNT(*) AS total_rows,
       COALESCE(SUM(`status` = 'ACCEPTED'), 0) AS accepted,
       COALESCE(SUM(`status` = 'PENDING'), 0) AS pending,
       COALESCE(SUM(`status` = 'REJECTED'), 0) AS rejected,
       COALESCE(SUM(`status` = 'REVOKED'), 0) AS revoked,
       COALESCE(SUM(`status` NOT IN ('PENDING', 'ACCEPTED', 'REJECTED', 'REVOKED')), 0) AS invalid
FROM `t_survey_collaborator`;

-- ③ 索引应为 (user_id, status) 两列
SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_survey_collaborator' AND INDEX_NAME = 'idx_user_status'
ORDER BY SEQ_IN_INDEX;
