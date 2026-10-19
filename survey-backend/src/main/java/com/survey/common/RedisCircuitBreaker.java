package com.survey.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 轻量熔断器 —— 依赖不可用时**快速失败**，不让每个请求都去等一次命令超时。
 * ★ 熔断 ≠ 降级：降级决定"不查 Redis 时做什么"（回源 / 进程内限流），本类只决定"还试不试"。
 * 窗口过期后放行一次真实请求：成功即关闭（秒级恢复），失败则重新打开。
 */
@Component
public class RedisCircuitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(RedisCircuitBreaker.class);

    /** 熔断窗口长度：这段时间内所有 Redis 操作直接跳过，走各自的安全降级路径。 */
    private static final long OPEN_WINDOW_MS = 10_000L;

    /** 告警节流：60 秒最多一条（长时间不可用时不要刷满日志）。 */
    private static final long WARN_INTERVAL_MS = 60_000L;

    /** 熔断窗口的截止时刻（纪元毫秒）。0（或早于当前时间）= 未熔断。 */
    private final AtomicLong openUntil = new AtomicLong(0);

    private final AtomicLong lastWarnAt = new AtomicLong(0);

    /** 是否处于熔断窗口内 —— true 表示调用方应当**直接走降级路径**，不要尝试 Redis。 */
    public boolean isOpen() {
        return System.currentTimeMillis() < openUntil.get();
    }

    /**
     * 记录一次成功：立即关闭熔断窗口（依赖已恢复，不必等窗口走完）。
     * ★ 只在"确实处于熔断中"时记 info：正常路径每请求都会进来，无条件打日志会刷满。
     */
    public void recordSuccess() {
        long previous = openUntil.getAndSet(0);
        if (previous > System.currentTimeMillis()) {
            LOG.info("Redis 已恢复，熔断关闭（缓存回到读缓存、限流回到 Redis 计数）");
        }
    }

    /**
     * 记录一次失败：打开（或续期）熔断窗口。
     * @param action 失败的操作描述（进日志，便于定位哪条链路先挂）
     * @param e      原始异常（只记 toString，避免每 60 秒刷一遍堆栈）
     */
    public void recordFailure(String action, Exception e) {
        long now = System.currentTimeMillis();
        // 返回旧值：旧值早于当前时间 ⇒ 这次是"从正常进入熔断"，告警一次
        boolean justOpened = openUntil.getAndSet(now + OPEN_WINDOW_MS) <= now;
        long last = lastWarnAt.get();
        if (justOpened && now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
            LOG.warn("Redis 调用失败，已熔断 {} 秒（期间缓存走直查库、限流走进程内计数）：{} 失败；原因={}",
                    OPEN_WINDOW_MS / 1000, action, e.toString());
        }
    }
}
