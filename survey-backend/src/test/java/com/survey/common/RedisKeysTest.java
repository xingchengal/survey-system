package com.survey.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RedisKeys 的纯单元测试 —— 钉住**缓存与限流两个命名空间互不污染**这条不变量。
 *
 * ── 为什么这条不变量值得单独测 ────────────────────────
 *   两条链路的 key 一旦共用前缀，最典型的后果是"清一次缓存，防爆破限流同时失效"：
 *   排查时看到的是"限流偶尔不起作用"，而根因在缓存那一侧的清理代码里 ——
 *   属于极难定位的一类问题。有了这几条断言，任何"把限流 key 挪到缓存前缀下"的改动
 *   都会在 `mvnw test` 阶段直接变红。
 */
class RedisKeysTest {

    @Test
    @DisplayName("两条命名空间前缀互不相等，也不互为前缀")
    void prefixesAreIsolated() {
        String cache = RedisKeys.cachePrefix();
        String rate = RedisKeys.ratePrefix();

        assertNotEquals(cache, rate, "缓存与限流必须使用不同的前缀");
        assertFalse(cache.startsWith(rate), "缓存前缀不能以限流前缀开头");
        assertFalse(rate.startsWith(cache), "限流前缀不能以缓存前缀开头（否则前缀匹配会互相命中）");
    }

    @Test
    @DisplayName("缓存类 key 一律落在缓存命名空间，限流 key 一律落在限流命名空间")
    void keysStayInTheirNamespace() {
        String[] cacheKeys = {
                RedisKeys.answerCount(9),
                RedisKeys.questionStats(3),
                RedisKeys.questionTexts(3)
        };
        for (String key : cacheKeys) {
            assertTrue(key.startsWith(RedisKeys.cachePrefix()), "应落在缓存前缀下：" + key);
            assertFalse(key.startsWith(RedisKeys.ratePrefix()), "不得落进限流前缀：" + key);
        }

        String rateKey = RedisKeys.rateLimit("login", "1.2.3.4");
        assertTrue(rateKey.startsWith(RedisKeys.ratePrefix()), "应落在限流前缀下：" + rateKey);
        assertFalse(rateKey.startsWith(RedisKeys.cachePrefix()), "不得落进缓存前缀：" + rateKey);
    }

    @Test
    @DisplayName("同一 id 下不同用途的 key 互不相同（答卷数 / 选项分布 / 填空文本）")
    void keysAreDistinctPerPurpose() {
        int id = 42;
        String count = RedisKeys.answerCount(id);
        String stats = RedisKeys.questionStats(id);
        String texts = RedisKeys.questionTexts(id);

        assertNotEquals(count, stats);
        assertNotEquals(count, texts);
        assertNotEquals(stats, texts);
    }

    @Test
    @DisplayName("限流 key 带规则名与主体：同一规则的不同主体、同一主体的不同规则都不共用一个桶")
    void rateLimitKeyCarriesRuleAndSubject() {
        String a = RedisKeys.rateLimit("login", "1.1.1.1");
        String b = RedisKeys.rateLimit("login", "2.2.2.2");
        String c = RedisKeys.rateLimit("submit", "1.1.1.1");

        assertNotEquals(a, b, "同一规则下不同 IP 必须各算一份配额");
        assertNotEquals(a, c, "同一主体在不同规则下必须各算一份配额");
        assertEquals(a, RedisKeys.rateLimit("login", "1.1.1.1"), "同样的入参必须生成同样的 key");
    }
}
