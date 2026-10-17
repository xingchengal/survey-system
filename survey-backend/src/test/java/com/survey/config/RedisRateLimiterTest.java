package com.survey.config;

import com.survey.common.RedisCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link RedisRateLimiter} 的纯单元测试（不连 Redis）。
 *
 * ── 钉住的两条不变量 ──────────────────────────────────
 *   1. **自增与设置过期必须在同一个 Lua 脚本里**：
 *      分成 `INCR` + `EXPIRE` 两条命令时，中间进程被杀死会留下**永不过期**的计数键 ——
 *      该主体从此被永久锁死（比不限流更糟）。断言脚本源码同时含 INCR 与 PEXPIRE。
 *   2. **Redis 故障时必须返回 null（= 让调用方回退）**，绝不能返回一个"看起来很大"的值
 *      或抛异常出去：前者会让限流被绕过，后者会把一次依赖故障变成接口 500。
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private RedisRateLimiter limiter;

    /** 用真实熔断器：本类要断言"熔断期间不再触达 Redis"。 */
    private RedisCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        limiter = new RedisRateLimiter();
        breaker = new RedisCircuitBreaker();
        ReflectionTestUtils.setField(limiter, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(limiter, "breaker", breaker);
        ReflectionTestUtils.setField(limiter, "redisEnabled", true);
    }

    @Test
    @DisplayName("正常路径：返回 Redis 给出的窗口计数")
    void returnsRedisCounter() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(7L);

        Long result = limiter.increment("login", "1.2.3.4", 60_000);

        assertEquals(7L, result);
    }

    @Test
    @DisplayName("Lua 脚本把自增与设置过期放在一起（避免留下永不过期的计数键）")
    void scriptSetsTtlAtomically() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);

        limiter.increment("login", "1.2.3.4", 60_000);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<RedisScript<Long>> captor = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(captor.capture(), anyList(), anyString());
        String script = captor.getValue().getScriptAsString();
        assertTrue(script.contains("INCR"), "脚本必须自增计数：" + script);
        assertTrue(script.contains("PEXPIRE"), "脚本必须在首次请求时设置过期：" + script);
    }

    @Test
    @DisplayName("窗口长度作为过期时间传给脚本（毫秒）")
    void passesWindowAsTtlArgument() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);

        limiter.increment("view", "1.2.3.4", 30_000);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("30000"));
    }

    @Test
    @DisplayName("key 走限流命名空间，且带规则名与主体")
    void usesRateNamespace() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(1L);

        limiter.increment("login", "1.2.3.4", 60_000);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), anyString());
        assertEquals(List.of("survey:rate:login:1.2.3.4"), keys.getValue());
    }

    @Test
    @DisplayName("Redis 故障：返回 null（= 调用方必须回退），不向上抛异常")
    void returnsNullOnRedisFailure() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertNull(limiter.increment("login", "1.2.3.4", 60_000),
                "Redis 不可用时必须返回 null，让调用方回退到进程内计数（而不是放行、也不是 500）");
    }

    @Test
    @DisplayName("开关关闭：直接走进程内计数，完全不碰 Redis")
    void disabledBySwitch() {
        ReflectionTestUtils.setField(limiter, "redisEnabled", false);

        assertNull(limiter.increment("login", "1.2.3.4", 60_000));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("★ 熔断打开后不再尝试 Redis（否则每个请求都要等一次命令超时，限流会变成延迟放大器）")
    void skipsRedisWhileCircuitOpen() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("refused"));

        assertNull(limiter.increment("login", "1.2.3.4", 60_000), "第一次真的去试了，失败后返回 null");
        assertNull(limiter.increment("login", "1.2.3.4", 60_000), "熔断期间直接返回 null，不再等超时");

        verify(redisTemplate, times(1)).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("失败后再成功：熔断立即关闭（恢复是秒级的，不需要人工干预）")
    void recoversAfterSuccess() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new RedisConnectionFailureException("refused"));
        assertNull(limiter.increment("login", "1.2.3.4", 60_000));

        ReflectionTestUtils.setField(breaker, "openUntil",
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() - 1));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(3L);

        assertEquals(3L, limiter.increment("login", "1.2.3.4", 60_000));
        assertFalse(breaker.isOpen());
    }
}
