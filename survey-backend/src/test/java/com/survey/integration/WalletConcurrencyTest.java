package com.survey.integration;

import com.survey.common.BizException;
import com.survey.common.WalletTxType;
import com.survey.service.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * **真库并发测试**：额度钱包的"不超扣 / 幂等 / 对账一致"。
 *
 * ── 为什么必须是真库 ──────────────────────────────────
 *   Mockito 只能证明"代码调了哪条 SQL"，证明不了 MySQL 的行锁与条件更新。
 *   50 个线程同时扣同一个钱包，只有真库能回答"会不会扣成负数"。
 *
 * ── 为什么默认不跑 ────────────────────────────────────
 *   需要独立空库 survey_db_test（结构用 sql/init.sql 导入），CI 的普通单测阶段没有库。
 *   本类在 pom 的 surefire `<excludes>` 里；显式点名才会跑：
 *
 *     $env:TEST_DB_PASSWORD='<本机口令>'
 *     ./mvnw -o "-Dtest=WalletConcurrencyTest" test
 *
 * ── 数据隔离 ──────────────────────────────────────────
 *   测试用户/钱包/流水都在 @AfterEach 按 id 精确删除；
 *   数据源是 survey_db_test，绝不指向 survey_db（活库）。
 */
@SpringBootTest(properties = {
        // 让 50 个线程真的能同时拿到连接（默认 Hikari 10 个也能测，但并发度不够直观）
        "spring.datasource.hikari.maximum-pool-size=50"
})
@ActiveProfiles("integration")
class WalletConcurrencyTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int userId;
    private String username;

    @BeforeEach
    void setUp() {
        username = "wallet_conc_" + System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO t_user (username, password, nickname, role) VALUES (?, ?, ?, ?)",
                username, "x", "并发测试用户", "user");
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_user WHERE username = ?", Integer.class, username);
        // 预置 50 点（用真实 API 入账，顺带把流水写对）
        walletService.credit(userId, 50, WalletTxType.ADMIN_ADJUST, null,
                "setup:" + userId, "并发测试预置 50 点");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM t_wallet_transaction WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_wallet WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", userId);
    }

    @Test
    @DisplayName("★ 50 并发扣 1 点：成功 50 次、余额归零、绝不超扣")
    void fiftyConcurrentCharges_shouldNeverOverdraw() throws Exception {
        int threads = 50;
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        runConcurrently(threads, i -> {
            walletService.charge(userId, 1, WalletTxType.PUBLISH, String.valueOf(i),
                    "concurrent:" + userId + ":" + i, "并发扣费 " + i);
            success.incrementAndGet();
        }, success, failure, errors);

        assertEquals(threads, success.get(), "50 个并发扣减应全部成功，实际成功 " + success.get()
                + "，失败 " + failure.get() + "：" + summarize(errors));
        assertEquals(0, failure.get(), "不应有失败：" + errors);
        assertEquals(0, balanceOf(userId), "50 点全扣完，余额应为 0");
        assertEquals(threads, countTx(userId, WalletTxType.PUBLISH), "应恰好有 50 条扣费流水");
        assertEquals(0, walletService.reconcile().get("mismatchCount"), "对账应一致");
    }

    @Test
    @DisplayName("★ 余额为 0 时再并发扣 10 次：全部失败，且不留下任何流水")
    void chargeWithZeroBalance_shouldFailAndRollbackLedger() throws Exception {
        // 先扣光
        for (int i = 0; i < 50; i++) {
            walletService.charge(userId, 1, WalletTxType.PUBLISH, "drain-" + i,
                    "drain:" + userId + ":" + i, "扣光");
        }
        assertEquals(0, balanceOf(userId));

        int threads = 10;
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        runConcurrently(threads, i -> {
            try {
                walletService.charge(userId, 1, WalletTxType.PUBLISH, "fail-" + i,
                        "fail:" + userId + ":" + i, "余额不足");
                success.incrementAndGet();
            } catch (BizException e) {
                failure.incrementAndGet();
            }
        }, success, failure, errors);

        assertEquals(0, success.get(), "余额为 0 时不应有任何扣减成功");
        assertEquals(threads, failure.get(), "10 次都应因余额不足失败");
        assertEquals(0, balanceOf(userId), "余额仍应保持 0（不能变成负数）");
        assertEquals(0, countTx(userId, WalletTxType.PUBLISH) - 50,
                "失败的扣减应整体回滚（占位流水不能留下）");
        assertEquals(0, walletService.reconcile().get("mismatchCount"), "对账应一致");
    }

    @Test
    @DisplayName("★ 同一幂等键 20 并发：只扣一次，其余请求返回同一笔流水")
    void sameIdempotencyKeyConcurrently_shouldChargeOnce() throws Exception {
        // 重置到 10 点方便断言（前面 setUp 给了 50）
        walletService.charge(userId, 40, WalletTxType.ADMIN_ADJUST, null,
                "reset:" + userId, "重置到 10 点");

        int threads = 20;
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        List<Throwable> errors = new ArrayList<>();
        String sameKey = "same-key:" + userId;
        runConcurrently(threads, i -> {
            walletService.charge(userId, 1, WalletTxType.PUBLISH, "same", sameKey, "重复提交");
            success.incrementAndGet();
        }, success, failure, errors);

        assertEquals(threads, success.get(), "幂等命中应视为成功（返回原流水），实际成功 "
                + success.get() + "，失败 " + failure.get() + "：" + summarize(errors));
        assertEquals(0, failure.get(), "不应有失败：" + errors);
        assertEquals(9, balanceOf(userId), "20 个请求只应扣 1 点（10 → 9）");
        assertEquals(1, countTxByKey(userId, sameKey), "同一幂等键只能有一条流水");
    }

    // ==================== 工具 ====================

    private interface Task {
        void run(int index) throws Exception;
    }

    private void runConcurrently(int threads, Task task,
                                 AtomicInteger success, AtomicInteger failure,
                                 List<Throwable> errors) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run(index);
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                    failure.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程没有全部就绪");
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务没有在 60 秒内结束");
        pool.shutdownNow();
    }

    /** 把并发异常压成一段可读文本（类型 + 消息前 160 字，去重后最多 5 条） */
    private static String summarize(List<Throwable> errors) {
        return errors.stream()
                .map(e -> e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "" : e.getMessage()))
                .map(s -> s.length() > 160 ? s.substring(0, 160) : s)
                .distinct()
                .limit(5)
                .reduce((a, b) -> a + " | " + b)
                .orElse("无");
    }

    private int balanceOf(int uid) {        Integer balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM t_wallet WHERE user_id = ?", Integer.class, uid);
        return balance == null ? 0 : balance;
    }

    private int countTx(int uid, String type) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_wallet_transaction WHERE user_id = ? AND type = ?",
                Integer.class, uid, type);
        return n == null ? 0 : n;
    }

    private int countTxByKey(int uid, String key) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_wallet_transaction WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, uid, key);
        return n == null ? 0 : n;
    }

    /** 只用于断言对账结果里是否有本用户（保留给后续扩展） */
    @SuppressWarnings("unused")
    private boolean hasMismatchFor(int uid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT user_id FROM t_wallet w LEFT JOIN "
                        + "(SELECT user_id, SUM(amount) ledger FROM t_wallet_transaction GROUP BY user_id) t "
                        + "ON t.user_id = w.user_id WHERE w.balance <> COALESCE(t.ledger, 0)");
        return rows.stream().anyMatch(r -> uid == ((Number) r.get("user_id")).intValue());
    }
}
