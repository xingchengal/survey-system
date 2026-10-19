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
 * 限流计数的 Redis 实现：Lua 保证 INCR + PEXPIRE 原子，固定窗口。
 *
 * ★ Redis 不可用时返回 null，调用方必须回退进程内计数 —— 绝不能“Redis 一挂就放行”。
 * ★ 用 Lua 而非 INCR 后 EXPIRE：两步之间进程退出会让 key 永不过期，该主体被永久锁死。
 * 固定窗口边界最多放行 2 倍配额；对防爆破/防刷够用，换来实现简单。
 */
@Component
public class RedisRateLimiter {

    /** 固定窗口计数脚本：自增；本窗口首次请求时设置 PEXPIRE（毫秒）。 */
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
     * 在给定主体的窗口计数上加一，返回本窗口累计次数。
     *
     * @param windowMs 窗口长度（毫秒），同时作为 key 的过期时间
     * @return Redis 不可用、已熔断或开关关闭时为 null（调用方必须回退进程内计数，绝不能放行）
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
