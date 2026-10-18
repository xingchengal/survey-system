-- ============================================================
-- 回收站：给 t_survey 加 deleted_at（移入回收站的时间）
--
-- 为什么需要它：回收站列表原来只有「创建时间」，但用户来回收站想知道的是
--   「什么时候删的」——创建时间是几周前，和"刚刚删掉"完全不是一件事。
--
-- 用法（与其它 migration-*.sql 一致）：
--   mysql.exe -uroot -p<口令> --default-character-set=utf8mb4 survey_db -e "source <此文件>"
-- ★ 不可重复执行：ALTER 重复加列会报 1060（Duplicate column name）。
--   执行前先查一遍：
--     SELECT COLUMN_NAME FROM information_schema.COLUMNS
--      WHERE TABLE_SCHEMA='survey_db' AND TABLE_NAME='t_survey' AND COLUMN_NAME='deleted_at';
--   返回空才需要执行。
-- ★ 撤销：ALTER TABLE t_survey DROP COLUMN deleted_at;
--   只会丢掉"删除时间"这一列，问卷本身与回收站状态不受影响。
-- ============================================================

ALTER TABLE `t_survey`
  ADD COLUMN `deleted_at` datetime DEFAULT NULL
    COMMENT '移入回收站的时间：NULL=不在回收站，非NULL=何时被删；恢复时置回 NULL'
  AFTER `deleted_by`;

-- 回填历史回收站数据（当前库里有 3 条）。
-- 依据：moveToRecycle 只改 deleted / deleted_by，而 updated_time 是 ON UPDATE CURRENT_TIMESTAMP，
--   且回收站里的问卷不允许编辑 —— 所以"现在还在回收站"的行，updated_time 就是最后一次移入的时间。
--   对之后的新数据，moveToRecycle 会显式写 NOW()，不依赖这个推断。
UPDATE `t_survey` SET `deleted_at` = `updated_time`
 WHERE `deleted` = 1 AND `deleted_at` IS NULL;

-- 校验：应当返回全部回收站行，deleted_at 非空
SELECT id, deleted, deleted_by, deleted_at FROM t_survey WHERE deleted = 1;
