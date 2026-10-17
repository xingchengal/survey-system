package com.survey.config;

import com.survey.common.RedisCircuitBreaker;
import com.survey.common.RedisKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 限流计数的 **Redis 实现**（`INCR` + `PEXPIRE` 的固定窗口，用 Lua 保证原子）。
 *
 * ── 为什么必须换成 Redis（内存计数的问题）──────────────────
 *   原来是进程内的 {@code ConcurrentHashMap<String, Counter>}：计数器只活在**单个 JVM** 里。
 *   单实例部署时它工作得很好，但一旦多实例（水平扩容、灰度、容器滚动更新），
 *   配额就被放大了 N 倍 —— 每个实例各拦 10 次/分，实际允许 10×N 次/分，
 *   "防暴力破解"这条规则在被真正需要的时候（多实例）恰好失效。
 *   ★ 已实测：30 次配额全部打到实例 A 之后，第 31 次请求打到**实例 B** 直接得到 429。
 *
 * ── 为什么用 Lua，而不是 INCR 之后再 EXPIRE ────────────────
 *   两条命令之间存在窗口：`INCR` 成功、`EXPIRE` 之前进程被杀死（或被 Redis 拒绝执行），
 *   这个 key 就变成**永不过期**的计数 —— 该主体从此被永久锁死（比"没限流"更糟）。
 *   放进 Lua 之后，"自增 + 首次设置过期"是一个原子步骤，不存在中间态。
 *
 * ── 固定窗口 vs 滑动窗口（有意的取舍）────────────────────
 *   窗口边界最多放行 2 倍配额（10 次/分的规则在跨窗口瞬间最多 20 次）。
 *   对"挡脚本爆破、挡刷接口"这个目标完全够用，而滑动窗口要么用 ZSET 存每次请求
 *   （内存与 CPU 都随配额线性增长），要么用令牌桶（需要额外维护上次填充时间）。
 *   本项目的取向是"简单、够用、可解释"，因此保持固定窗口。
 *
 * ── ★★ 降级方向与缓存**相反**（这是本类最重要的设计约定）────
 *   Redis 不可用时本类返回 {@code null}，调用方（{@code RateLimitInterceptor}）
 *   **必须**回退到进程内计数继续限流，而不是直接放行：
 *     · 缓存失败 → 当作未命中（安全，只是变慢）；
 *     · 限流失败 → 仍然受限（保守），代价是多实例下配额被放大，
 *       但**绝不允许**"Redis 一挂就等于不限流"——那会把一次依赖故障放大成安全事件。
 *   ★ 已实测：`SHUTDOWN NOSAVE` 停掉 Redis 后，连续 12 次登录尝试仍是 10 次 400 + 2 次 429。
 *
 * ── 熔断（与缓存共用一个 {@link RedisCircuitBreaker}）────────
 *   失败一次就短路 10 秒，期间不再尝试 Redis、直接走进程内计数 ——
 *   否则每个请求都要等一次命令超时（实测约 2 秒/请求），限流会变成全站延迟放大器。
 *   熔断期间限流**仍然生效**（只是从"全局共享配额"退化为"单机配额"）。
 */
@Component
public class RedisRateLimiter {

    /**
     * 固定窗口计数：自增；若是本窗口第一次请求，则设置过期时间（毫秒）。
     *
     * @return 本窗口内该主体的累计请求次数（含本次）
     */
    private static final RedisScript<Long> FIXED_WINDOW = RedisScript.of(
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return current",
            Long.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisCircuitBreaker breaker;

    /** 总开关。关掉即完全走进程内计数（单实例部署或压测对比时可用）。 */
    @Value("${app.rate-limit.redis-enabled:true}")
    private boolean redisEnabled;

    /**
     * 在给定主体的窗口计数上加一，并返回加一后的值。
     *
     * @param rule     规则名（进 key，见 {@code RateLimitInterceptor.RULES}）
     * @param subject  计数主体（真实连接 IP 或登录用户 id）
     * @param windowMs 窗口长度（毫秒），同时作为 key 的过期时间
     * @return 累计次数；**Redis 不可用、已熔断或开关关闭时返回 null**
     *         （调用方必须回退到进程内计数，绝不能放行）
     */
    public Long increment(String rule, String subject, long windowMs) {
        if (!redisEnabled || breaker.isOpen()) {
            return null;
        }
        try {
            Long count = redisTemplate.execute(FIXED_WINDOW,
                    List.of(RedisKeys.rateLimit(rule, subject)),
                    Long.toString(windowMs));
            breaker.recordSuccess();
            return count;
        } catch (RuntimeException e) {
            // 告警由熔断器统一节流（60 秒一条），这里只上报失败
            breaker.recordFailure("限流计数（规则 " + rule + "）", e);
            return null;
        }
    }
}
