package com.survey.common;

/**
 * Redis key 的**唯一出处** —— 缓存与限流两套命名空间都在这里定义，业务代码不得自己拼字符串。
 *
 * ── ★★ 两条命名空间必须互相隔离（这是本类存在的首要理由）──────
 *   · 缓存：`survey:cache:*` —— 预聚合的统计结果，**丢了只会回源重算**，不影响正确性；
 *   · 限流：`survey:rate:*`  —— 配额计数器，**丢了等于配额被重置**（防爆破直接失效）。
 *   两者一旦共用前缀，清缓存的那一下就会顺手清掉限流计数 ——
 *   症状是"清一次缓存，登录防爆破同时失效"，属于极难排查的一类问题。
 *   `RedisKeysTest` 因此钉住两条前缀**互不包含、互不为前缀**。
 *
 * ── ★ 为什么要求集中定义（而不是散落字符串）──────────────────
 *   散落的字面量没有任何机制保证"清缓存时不误伤限流"：
 *   某处写 `survey:cache:*` 的前缀匹配、某处写 `survey:rate:login:1.2.3.4`，
 *   只要有人日后把限流 key 改成 `survey:cache:rate:...`，隔离性当场消失。
 *   收成一个类之后，前缀只在这里出现一次，测试可以直接断言隔离性。
 *
 * ── 运维红线（写在这里，别只在文档里）────────────────────────
 *   **禁止** `KEYS *` / `FLUSHDB` / `FLUSHALL` 清缓存：
 *   前两者会把限流计数一起清空，后者还会顺带清掉别的应用的库。
 *   要清缓存就按具体的 key 删（{@link #cachePrefix()} 只用于排查与文档，不用于扫库）。
 *
 * ── 完整规范 ──────────────────────────────────────────
 *   缓存失效策略、降级与熔断的方向、运维红线、改造前后的压测数据，
 *   见仓库内 `docs/Redis-键规范与缓存限流设计.md`（随仓库入库，可直接引用）。
 */
public final class RedisKeys {

    /** 工具类不允许实例化（本类只有静态方法）。 */
    private RedisKeys() {
    }

    /** 缓存命名空间：统计预聚合结果。丢了回源重算，是"可丢数据"。 */
    private static final String CACHE_PREFIX = "survey:cache:";

    /** 限流命名空间：窗口配额计数器。丢了等于放行，是"安全数据"。 */
    private static final String RATE_PREFIX = "survey:rate:";

    /**
     * 某问卷的答卷份数（`/api/answer/count/{surveyId}` 的热点）。
     * ★ 值是**十进制字符串**（StringRedisTemplate 的 INCR/DECR 只认字符串）。
     */
    public static String answerCount(int surveyId) {
        return CACHE_PREFIX + "answer-count:" + surveyId;
    }

    /**
     * 某道选择题的选项分布（`[{content, count}]` 的 JSON）。
     * ★ 源查询是 `GROUP BY content` 扫明细表，是本项目最重的一条统计 SQL。
     */
    public static String questionStats(int questionId) {
        return CACHE_PREFIX + "qstats:" + questionId;
    }

    /**
     * 同一道题的选项分布，**只统计有效答卷**（`t_answer.quality_flag = 0`）。
     *
     * ★ 与 {@link #questionStats} 是**两个独立 key**（不是同一个 key 换个参数查）：
     *   统计页的"剔除可疑样本"开关一拨就换口径，共用 key 会让图表静默算错。
     * ★ 失效时必须**两个一起删** —— 见 `StatsCacheService.evictQuestions`。
     */
    public static String questionStatsValid(int questionId) {
        return CACHE_PREFIX + "qstats-valid:" + questionId;
    }

    /** 某道填空题的文字回答列表（JSON 数组）。 */
    public static String questionTexts(int questionId) {
        return CACHE_PREFIX + "qtexts:" + questionId;
    }

    /**
     * 限流计数键。
     *
     * @param rule    规则名（{@code RateLimitInterceptor.RULES} 里的 name，如 login / submit-share）
     * @param subject 计数主体（IP 或登录用户 id）
     */
    public static String rateLimit(String rule, String subject) {
        return RATE_PREFIX + rule + ':' + subject;
    }

    /** 缓存前缀。★ 只给排查与单测用，**不要**拿它去 `KEYS` 扫库（见类注释的运维红线）。 */
    public static String cachePrefix() {
        return CACHE_PREFIX;
    }

    /** 限流前缀。★ 同上。 */
    public static String ratePrefix() {
        return RATE_PREFIX;
    }
}
