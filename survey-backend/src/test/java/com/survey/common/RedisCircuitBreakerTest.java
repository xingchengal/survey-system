package com.survey.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RedisCircuitBreaker} 的纯单元测试。
 *
 * ── 这个类钉住的三条语义 ──────────────────────────────
 *   1. 失败一次就打开熔断（这是"快速失败"的全部价值所在：不能让每个请求都去等命令超时）；
 *   2. **一次成功立即关闭**，不必等 10 秒窗口走完（恢复要快）；
 *   3. 窗口过期后自动回到"可尝试"状态（否则一次抖动会让 Redis 永久不可用）。
 */
class RedisCircuitBreakerTest {

    private RedisCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new RedisCircuitBreaker();
    }

    /** 把熔断窗口的截止时刻直接改掉：用反射是因为它内部只有一个 AtomicLong，无需为测试引入时钟抽象。 */
    private void setOpenUntil(long epochMillis) {
        ReflectionTestUtils.setField(breaker, "openUntil", new AtomicLong(epochMillis));
    }

    @Test
    @DisplayName("初始状态：未熔断")
    void closedByDefault() {
        assertFalse(breaker.isOpen());
    }

    @Test
    @DisplayName("失败一次即打开熔断")
    void opensOnFailure() {
        breaker.recordFailure("测试操作", new RuntimeException("boom"));

        assertTrue(breaker.isOpen());
    }

    @Test
    @DisplayName("一次成功立即关闭熔断（不等窗口走完）")
    void closesOnSuccess() {
        breaker.recordFailure("测试操作", new RuntimeException("boom"));
        assertTrue(breaker.isOpen());

        breaker.recordSuccess();

        assertFalse(breaker.isOpen());
    }

    @Test
    @DisplayName("窗口过期后自动回到可尝试状态")
    void reopensAfterWindow() {
        breaker.recordFailure("测试操作", new RuntimeException("boom"));
        assertTrue(breaker.isOpen());

        setOpenUntil(System.currentTimeMillis() - 1);

        assertFalse(breaker.isOpen(), "窗口过期后必须允许再试一次，否则 Redis 抖动一次就永久不可用");
    }
}
