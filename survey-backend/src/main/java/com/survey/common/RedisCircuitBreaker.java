package com.survey.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 的轻量熔断器 —— 依赖不可用时**快速失败**，而不是让每个请求都去等一次命令超时。
 *
 * ── 为什么需要它（实测数据，不是理论担忧）──────────────────
 *   把 Redis 停掉之后，统计接口**仍然返回 200**（缓存降级为直查数据库，功能没坏），
 *   但单个请求耗时从 12ms 涨到 **约 4000ms** —— 因为每一次缓存读写都要先等 Lettuce
 *   的命令超时（{@code spring.data.redis.timeout}）才能失败并回退。
 *   一个"只是变慢"的降级，在真实流量下会立刻把 Tomcat 线程池占满，最终表现和宕机一样。
 *   ⇒ 失败一次就打开熔断窗口，窗口内**直接跳过 Redis**（零等待），窗口过后再放一次探测。
 *
 * ── 与"降级"的分工（两个不同的概念，别混）────────────────
 *   · **降级**决定"Redis 不可用时做什么"：缓存 → 回源；限流 → 回退进程内计数。
 *   · **熔断**决定"还要不要每次都去试"：打开期间不再发起 Redis 调用。
 *   本类只管后者；调用方仍然必须实现好自己的降级路径（两者缺一不可）。
 *
 * ── 为什么不用 Resilience4j / Sentinel ──────────────────
 *   本项目只需要"失败即短路、成功即恢复"这一条语义，用两个 AtomicLong 就够了；
 *   引入完整的熔断框架会带来配置面、线程池与指标依赖，收益不成比例。
 *
 * ── 恢复策略（有意选择"乐观恢复"）──────────────────────
 *   窗口过期后放行一次真实请求：成功 → 立即关闭窗口（{@link #recordSuccess()}，
 *   不等窗口走完）；失败 → 重新打开窗口。所以恢复是"秒级"，不需要手工干预。
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
     *
     * ★ 只有"确实处于熔断中"才记一条 info：正常运行时每个请求都会调到这里，
     *   无条件打日志会把日志刷满；而"故障已恢复"这条信息运维必须看得到 ——
     *   否则日志里只有一条 warn，看不到它什么时候好的。
     */
    public void recordSuccess() {
        long previous = openUntil.getAndSet(0);
        if (previous > System.currentTimeMillis()) {
            LOG.info("Redis 已恢复，熔断关闭（缓存回到读缓存、限流回到 Redis 计数）");
        }
    }

    /**
     * 记录一次失败：打开（或续期）熔断窗口。
     *
     * @param action 失败的操作描述（进日志，便于定位是哪条链路先挂的）
     * @param e      原始异常（只记 toString，避免把完整堆栈每 60 秒刷一遍）
     */
    public void recordFailure(String action, Exception e) {
        long now = System.currentTimeMillis();
        // 返回旧值：旧值早于当前时间 ⇒ 这次是"从正常进入熔断"，值得告警一次
        boolean justOpened = openUntil.getAndSet(now + OPEN_WINDOW_MS) <= now;
        long last = lastWarnAt.get();
        if (justOpened && now - last >= WARN_INTERVAL_MS && lastWarnAt.compareAndSet(last, now)) {
            LOG.warn("Redis 调用失败，已熔断 {} 秒（期间缓存走直查库、限流走进程内计数）：{} 失败；原因={}",
                    OPEN_WINDOW_MS / 1000, action, e.toString());
        }
    }
}
