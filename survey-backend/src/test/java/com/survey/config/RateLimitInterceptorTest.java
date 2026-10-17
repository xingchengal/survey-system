package com.survey.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitInterceptor} 的纯单元测试（不连 Redis、不起 Spring 容器）。
 *
 * ── 这个类钉住的回归点（★ 最重要的一条在 fallsBack 那个用例里）────
 *   计数搬到 Redis 之后，"Redis 不可用"这条路径的**方向**决定了它是缓解还是事故：
 *     · 回退进程内计数 → 退化成"单机限流"，业务与安全都还在；
 *     · 直接放行        → Redis 一挂就等于全站不限流（防爆破、防刷全部失效）。
 *   下面用 mock 让 {@code RedisRateLimiter.increment} 返回 null（= 不可用），
 *   断言第 11 次登录请求**仍然**被 429 拦下 —— 谁把 fallback 改成 `return true`，这里就红。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    /** login 规则：10 次/分钟/IP（见 RateLimitInterceptor.RULES）。 */
    private static final int LOGIN_LIMIT = 10;

    @Mock
    private RedisRateLimiter redisRateLimiter;

    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor();
        ReflectionTestUtils.setField(interceptor, "redisRateLimiter", redisRateLimiter);
    }

    private MockHttpServletRequest loginRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/user/login");
        request.setRequestURI("/api/user/login");
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    @Test
    @DisplayName("Redis 计数未超上限：放行")
    void allowsWithinLimit() throws Exception {
        when(redisRateLimiter.increment(eq("login"), eq("10.0.0.1"), anyLong())).thenReturn(1L);

        assertTrue(interceptor.preHandle(loginRequest(), new MockHttpServletResponse(), null));
    }

    @Test
    @DisplayName("Redis 计数超上限：429 + 统一失败响应体")
    void blocksAboveLimit() throws Exception {
        when(redisRateLimiter.increment(eq("login"), eq("10.0.0.1"), anyLong()))
                .thenReturn((long) LOGIN_LIMIT + 1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(loginRequest(), response, null));

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":429"),
                "响应体形状必须与其它失败响应一致：" + response.getContentAsString());
    }

    @Test
    @DisplayName("★ Redis 不可用（返回 null）时仍必须限流：回退进程内计数，第 11 次 429")
    void fallsBackToInProcessCountingWhenRedisUnavailable() throws Exception {
        when(redisRateLimiter.increment(anyString(), anyString(), anyLong())).thenReturn(null);

        for (int i = 1; i <= LOGIN_LIMIT; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(loginRequest(), response, null),
                    "第 " + i + " 次请求在配额内，应放行");
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(loginRequest(), blocked, null),
                "★ 第 11 次必须被拦下：Redis 挂了也只能退化成单机限流，绝不能变成不限流");
        assertEquals(429, blocked.getStatus());
    }

    @Test
    @DisplayName("不在限流名单里的路径：直接放行，且完全不碰 Redis")
    void passesThroughUnlistedPath() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/answer/statistics/3665");
        request.setRequestURI("/api/answer/statistics/3665");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
        verifyNoInteractions(redisRateLimiter);
    }

    @Test
    @DisplayName("按用户计数的规则用 X-User-Id 作主体（同一 IP 下的不同用户各算一份配额）")
    void userSubjectUsesInjectedHeader() throws Exception {
        when(redisRateLimiter.increment(eq("ai"), eq("77"), anyLong())).thenReturn(1L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/generate");
        request.setRequestURI("/api/ai/generate");
        request.addHeader("X-User-Id", "77");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
    }
}
