package com.survey.common;

/**
 * Redis key 的唯一出处：缓存 `survey:cache:*` 与限流 `survey:rate:*` 两套命名空间，业务代码不得自己拼。
 * ★ 两套前缀必须互不包含：共用前缀会让"清缓存"顺手清掉限流计数，登录防爆破当场失效。
 * ★ 禁止 `KEYS *` / `FLUSHDB` / `FLUSHALL`：会把限流计数甚至别的应用的库一起清掉；清缓存只按具体 key 删。
 */
public final class RedisKeys {

    /** 工具类不允许实例化（本类只有静态方法）。 */
    private RedisKeys() {
    }

    /** 缓存命名空间：统计预聚合结果。丢了回源重算，是"可丢数据"。 */
    private static final String CACHE_PREFIX = "survey:cache:";

    /** 限流命名空间：窗口配额计数器。丢了等于放行，是"安全数据"。 */
    private static final String RATE_PREFIX = "survey:rate:";

    /** 某问卷的答卷份数（`/api/answer/count/{surveyId}` 的热点）；值是十进制字符串，INCR/DECR 只认字符串。 */
    public static String answerCount(int surveyId) {
        return CACHE_PREFIX + "answer-count:" + surveyId;
    }

    /** 某道选择题的选项分布（`[{content, count}]` JSON）；源查询 GROUP BY 扫明细表，是最重的一条统计 SQL。 */
    public static String questionStats(int questionId) {
        return CACHE_PREFIX + "qstats:" + questionId;
    }

    /**
     * 同一道题的选项分布，只统计有效答卷（t_answer.quality_flag = 0），与 questionStats 是两个独立 key。
     * 失效时必须两个一起删（见 StatsCacheService.evictQuestions），共用 key 会让图表静默算错。
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
     * @param rule    规则名（RateLimitInterceptor.RULES 的 name，如 login / submit-share）
     * @param subject 计数主体（IP 或登录用户 id）
     */
    public static String rateLimit(String rule, String subject) {
        return RATE_PREFIX + rule + ':' + subject;
    }

    /** 缓存前缀。只给排查与单测用，不要拿它去 `KEYS` 扫库。 */
    public static String cachePrefix() {
        return CACHE_PREFIX;
    }

    /** 限流前缀。同上，只给排查与单测用。 */
    public static String ratePrefix() {
        return RATE_PREFIX;
    }
}
