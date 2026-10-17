package com.survey.service;

import com.survey.common.RedisCircuitBreaker;
import com.survey.common.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link StatsCacheService} 的纯单元测试（mock 掉 Redis，不连真实服务）。
 *
 * ── 钉住的四条不变量 ──────────────────────────────────
 *   1. **Redis 故障 = 未命中**（返回 null），而不是抛异常出去 ——
 *      调用方随后走"直查数据库"，功能不受影响（缓存是加速，不是依赖）。
 *   2. **开关关闭时完全不碰 Redis** —— 无 Redis 的环境（CI）靠它保证行为可预期。
 *   3. **答卷数的增量更新只对已存在的 key 自增**（脚本里先 EXISTS）——
 *      直接 INCR 会在 key 不存在时凭空造出"答案=1"的假缓存，那比不缓存更危险。
 *   4. **失效按具体 key 删、且成批删**（一次提交要失效整份问卷的题目）。
 */
@ExtendWith(MockitoExtension.class)
class StatsCacheServiceTest {

    private static final long STATS_TTL_SECONDS = 600L;
    private static final long COUNT_TTL_SECONDS = 60L;
    private static final long JITTER_SECONDS = 60L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private StatsCacheService cache;

    /** 用**真实**熔断器（不是 mock）：熔断本身的行为也要在本类里被断言到。 */
    private RedisCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        cache = new StatsCacheService();
        breaker = new RedisCircuitBreaker();
        ReflectionTestUtils.setField(cache, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(cache, "breaker", breaker);
        ReflectionTestUtils.setField(cache, "enabled", true);
        ReflectionTestUtils.setField(cache, "statsTtlSeconds", STATS_TTL_SECONDS);
        ReflectionTestUtils.setField(cache, "countTtlSeconds", COUNT_TTL_SECONDS);
        ReflectionTestUtils.setField(cache, "ttlJitterSeconds", JITTER_SECONDS);
    }

    @Test
    @DisplayName("命中：直接返回缓存里的答卷数（不回源）")
    void returnsCachedAnswerCount() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.answerCount(9))).thenReturn("20000");

        assertEquals(20000, cache.getAnswerCount(9));
    }

    @Test
    @DisplayName("未命中：返回 null，由调用方回源并写回")
    void returnsNullOnMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.answerCount(9))).thenReturn(null);

        assertNull(cache.getAnswerCount(9));
    }

    @Test
    @DisplayName("Redis 不可用：读缓存返回 null（降级为直查库），不向上抛异常")
    void degradesWhenRedisUnavailable() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertNull(cache.getAnswerCount(9));
        assertNull(cache.getQuestionStats(3));
        assertNull(cache.getQuestionTexts(3));
    }

    @Test
    @DisplayName("开关关闭：所有方法都不碰 Redis（无 Redis 环境的行为必须可预期）")
    void bypassesRedisWhenDisabled() {
        ReflectionTestUtils.setField(cache, "enabled", false);

        assertNull(cache.getAnswerCount(9));
        assertNull(cache.getQuestionStats(3));
        assertNull(cache.getQuestionTexts(3));
        cache.putAnswerCount(9, 5);
        cache.bumpAnswerCount(9);
        cache.evictQuestions(List.of(3, 4));

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("回源后写回：答卷数用**短的固定 TTL**（把增量失败的脏读窗口压到 1 分钟）")
    void writesAnswerCountWithShortTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cache.putAnswerCount(9, 123);

        verify(valueOperations).set(RedisKeys.answerCount(9), "123", Duration.ofSeconds(COUNT_TTL_SECONDS));
    }

    @Test
    @DisplayName("单题统计的 TTL 带随机抖动（打散集体过期造成的缓存击穿）")
    void questionStatsTtlHasJitter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        cache.putQuestionStats(3, List.<Map<String, Object>>of(Map.of("content", "手机", "count", 1)));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(RedisKeys.questionStats(3)), anyString(), ttl.capture());
        long seconds = ttl.getValue().toSeconds();
        assertTrue(seconds >= STATS_TTL_SECONDS && seconds <= STATS_TTL_SECONDS + JITTER_SECONDS,
                "TTL 应落在 [" + STATS_TTL_SECONDS + ", " + (STATS_TTL_SECONDS + JITTER_SECONDS)
                        + "] 秒之间，实际是 " + seconds);
    }

    @Test
    @DisplayName("★ 自增失败时静默删掉该 key：不让\"缓存比库少 1\"的错误值一直留到 TTL 到期")
    void deletesCountCacheWhenIncrementFails() {
        doThrow(new RedisConnectionFailureException("slow"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList());

        cache.bumpAnswerCount(9);

        verify(redisTemplate).delete(RedisKeys.answerCount(9));
    }

    @Test
    @DisplayName("选项分布：JSON 往返正常，且命中路径的 count 是 Integer（回源路径是 Long）")
    void roundTripsQuestionStats() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.questionStats(3)))
                .thenReturn("[{\"content\":\"手机\",\"count\":5000},{\"content\":\"平板\",\"count\":3000}]");

        List<Map<String, Object>> stats = cache.getQuestionStats(3);

        assertNotNull(stats);
        assertEquals(2, stats.size());
        assertEquals("手机", stats.get(0).get("content"));
        // ★ 类型提醒（见 StatsCacheService 类注释）：命中路径反序列化出来的是 Integer，
        //   任何对 map.get("count") 做 (Long) 强转的代码都会在"有缓存"时炸掉。
        assertInstanceOf(Integer.class, stats.get(0).get("count"));
    }

    @Test
    @DisplayName("缓存内容损坏（非法 JSON）：当作未命中，由回源覆盖")
    void treatsBrokenJsonAsMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.questionStats(3))).thenReturn("这不是 JSON");

        assertNull(cache.getQuestionStats(3));
    }

    @Test
    @DisplayName("答卷数增量更新走 Lua：先判存在再自增（不给不存在的 key 造出假值）")
    void bumpsAnswerCountOnlyThroughScript() {
        cache.bumpAnswerCount(9);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redisTemplate).execute(script.capture(), anyList());
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("EXISTS"), "必须先判 key 是否存在：" + lua);
        assertTrue(lua.contains("INCR"), "存在时才自增：" + lua);
    }

    @Test
    @DisplayName("批量失效：一次提交要失效整份问卷的题目，合并成一次 DEL")
    void evictsQuestionsInOneBatch() {
        cache.evictQuestions(List.of(3, 4));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate).delete(keys.capture());
        assertEquals(6, keys.getValue().size(), "2 道题 × (全量分布 + 有效样本分布 + 填空文本) = 6 个 key");
        assertTrue(keys.getValue().contains(RedisKeys.questionStats(3)));
        assertTrue(keys.getValue().contains(RedisKeys.questionStatsValid(3)));
        assertTrue(keys.getValue().contains(RedisKeys.questionTexts(4)));
    }

    @Test
    @DisplayName("空题目列表不产生任何 Redis 调用（避免 delete() 空集合的无效往返）")
    void ignoresEmptyQuestionList() {
        cache.evictQuestions(List.of());

        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("整份问卷失效：答卷数 + 各题缓存一起删（彻底删除问卷时走这条）")
    void evictsWholeSurvey() {
        cache.evictSurvey(9, List.of(3, 4));

        verify(redisTemplate).delete(RedisKeys.answerCount(9));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate).delete(keys.capture());
        assertEquals(6, keys.getValue().size());
    }

    @Test
    @DisplayName("写入失败只记日志（降级），不影响调用方")
    void writeFailureIsSwallowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(any(), any(), any(Duration.class));

        assertDoesNotThrow(() -> cache.putAnswerCount(9, 1));
    }

    @Test
    @DisplayName("★ 失败一次即熔断：后续请求直接跳过 Redis（不再每个请求都等一次命令超时）")
    void opensCircuitAfterFirstFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertNull(cache.getAnswerCount(9), "第一次会真的去试 Redis，失败后返回未命中");
        assertTrue(breaker.isOpen(), "失败一次就应当打开熔断窗口");

        assertNull(cache.getAnswerCount(9));
        assertNull(cache.getQuestionStats(3));
        // 熔断后不再触达 Redis：整个过程中只尝试过一次
        verify(redisTemplate, times(1)).opsForValue();
    }

    @Test
    @DisplayName("熔断窗口期间直接降级；窗口过后放行一次探测，成功即立刻恢复")
    void closesCircuitWhenRedisRecovers() {
        ReflectionTestUtils.setField(breaker, "openUntil",
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() + 5_000));
        assertNull(cache.getAnswerCount(9), "熔断期间直接按未命中处理");
        verifyNoInteractions(redisTemplate);

        // 模拟窗口已过：放行一次真实请求，成功后熔断立即关闭
        ReflectionTestUtils.setField(breaker, "openUntil",
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() - 1));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeys.answerCount(9))).thenReturn("7");

        assertEquals(7, cache.getAnswerCount(9));
        assertFalse(breaker.isOpen(), "一次成功就应当关闭熔断，不必等窗口走完");
    }
}
