-- =====================================================================
-- 迁移：额度钱包（§H-H5，模拟付费，不接真实支付）
-- 性质：纯新增表 + 给现有用户初始化钱包（可重复执行）
-- 回滚：DROP TABLE `t_wallet_transaction`; DROP TABLE `t_wallet`;
--
-- ★ amount 一律**有符号**：入账为正、出账为负。对账口径因此只有一句：
--     SUM(t_wallet_transaction.amount) = t_wallet.balance
--   不要再加 income / expense 两列 —— 两列就是两套求和，改一处必漏一处。
-- ★ idempotency_key 全表唯一：扣款、退款、充值、赠送都靠它去重；
--   重复请求插入 0 行 → 直接返回已有流水，不再动余额。
-- ★ balance_after 是"本笔之后的余额快照"：排查"为什么余额不对"时无需回放全表。
-- ★ 为什么不用乐观锁 version 做扣减：余额扣减的并发正确性靠
--   `UPDATE ... SET balance = balance - ? WHERE balance >= ?` 的**行锁 + 条件**，
--   一次语句完成"判断与扣减"，没有"读-改-写"窗口，也就没有超扣。
--   version 只作为排查/对账时的辅助字段保留。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `t_wallet` (
  `user_id` int NOT NULL COMMENT '用户 id（一人一包）',
  `balance` int NOT NULL DEFAULT 0 COMMENT '可用额度（点）',
  `frozen` int NOT NULL DEFAULT 0 COMMENT '冻结额度（预留；当前业务未使用）',
  `version` int NOT NULL DEFAULT 0 COMMENT '版本号（辅助排查，不参与扣减判定）',
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='额度钱包';

CREATE TABLE IF NOT EXISTS `t_wallet_transaction` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `type` varchar(32) NOT NULL COMMENT 'SIGNUP_BONUS / RECHARGE / PUBLISH / REFUND / ADMIN_ADJUST',
  `amount` int NOT NULL COMMENT '有符号：入账为正、出账为负',
  `balance_after` int NOT NULL DEFAULT 0 COMMENT '本笔之后的余额快照',
  `biz_id` varchar(64) DEFAULT NULL COMMENT '关联业务（如 surveyId）',
  `idempotency_key` varchar(128) NOT NULL COMMENT '幂等键（全表唯一）',
  `remark` varchar(200) DEFAULT NULL,
  `created_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wallet_tx_idem` (`idempotency_key`),
  KEY `idx_wallet_tx_user` (`user_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='额度流水';

-- 给现有用户初始化钱包并赠送 10 点。
-- ★ INSERT IGNORE 保证可重复执行：已经有钱包/已有 signup 流水的用户不会被二次赠送。
INSERT IGNORE INTO `t_wallet` (`user_id`, `balance`, `version`)
SELECT `id`, 10, 1 FROM `t_user` WHERE `deleted_at` IS NULL;

-- 只给"本次新建的钱包"补一条赠送流水（条件 = 余额 10 + version 1 + 尚无 signup 流水），
-- 否则重跑脚本会给老用户补一条流水而余额没加，反而制造对账不一致。
INSERT IGNORE INTO `t_wallet_transaction`
  (`user_id`, `type`, `amount`, `balance_after`, `biz_id`, `idempotency_key`, `remark`)
SELECT w.`user_id`, 'SIGNUP_BONUS', 10, w.`balance`, NULL, CONCAT('signup:', w.`user_id`),
       '迁移初始化：赠送 10 点'
FROM `t_wallet` w
WHERE w.`balance` = 10 AND w.`version` = 1
  AND NOT EXISTS (
    SELECT 1 FROM `t_wallet_transaction` x WHERE x.`idempotency_key` = CONCAT('signup:', w.`user_id`)
  );

-- 自检：钱包数 + 对账差额（应为 0）
SELECT COUNT(*) AS wallet_rows FROM `t_wallet`;
SELECT COUNT(*) AS mismatch_rows
FROM `t_wallet` w
LEFT JOIN (
  SELECT `user_id`, SUM(`amount`) AS ledger FROM `t_wallet_transaction` GROUP BY `user_id`
) t ON t.`user_id` = w.`user_id`
WHERE w.`balance` <> COALESCE(t.`ledger`, 0);
