package com.survey.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.survey.common.RedisCircuitBreaker;
import com.survey.common.RedisKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 答卷统计的 Redis 预聚合缓存：只缓存"重复计算、结果只依赖已提交答卷"的几条查询。
 * 一致性：答卷数只在缓存已存在时 INCR；单题分布/文本直接删除、下次回源（多选题一选项一行，增量维护不划算）。
 * ★ 所有 key 必须走 {@link RedisKeys}：缓存 survey:cache:* 与限流 survey:rate:* 两套命名空间不得混用或前缀扫库。
 * TTL 是兜底：单题 600s、答卷数 60s，单题另加 0~N 秒随机抖动打散集体过期（防缓存击穿）。
 * 失效入口由写入路径在事务提交后（afterCommit）调用；回滚事务不能先动缓存。
 * Redis 异常一律吞掉当未命中回源，并由 RedisCircuitBreaker 短路，不能让缓存故障阻塞请求。
 */
@Service
public class StatsCacheService {

    /**
     * 「缓存已存在才自增」的 Lua 脚本：必须用 Lua 保证"判存在 + 自增"的原子性，
     * 分成两条命令会在 TTL 到期瞬间丢计数或给已删的键自增。
     *
     * @return 自增后的值；key 不存在时返回 -1（调用方无需处理，下次读会回源）
     */
    private static final RedisScript<Long> BUMP_IF_EXISTS = RedisScript.of(
            "if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('INCR', KEYS[1]) end return -1",
            Long.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisCircuitBreaker breaker;

    /**
     * JSON 编解码器：自己持有实例，**不要**改成注入 Spring 的 ObjectMapper ——
     * Spring Boot 4 不再为 Jackson 2 注册该 bean（注入会启动失败），QwenClient / WebUtils 也是自己 new。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 总开关：压测对比与无 Redis 的环境（CI）可以关掉，行为退回"每次都直查库"。 */
    @Value("${app.cache.stats-enabled:true}")
    private boolean enabled;

    /**
     * 单题分布 / 填空文本的 TTL（秒）：回源要扫明细表，比较贵，所以 TTL 长，主要靠写入路径主动删除。
     */
    @Value("${app.cache.stats-ttl-seconds:600}")
    private long statsTtlSeconds;

    /**
     * 答卷数的 TTL（秒），故意比统计短得多：回源只是一条 COUNT(*)，很便宜；
     * 且 INCR 万一失败会让缓存少 1，短 TTL 把错误窗口压到 1 分钟。
     */
    @Value("${app.cache.count-ttl-seconds:60}")
    private long countTtlSeconds;

    /**
     * 单题统计 TTL 的随机抖动上限（秒）：打散同一批 key 的集体过期，避免并发请求一起回源。
     * 答卷数不加抖动：它的 TTL 本来就要短而稳定。
     */
    @Value("${app.cache.ttl-jitter-seconds:60}")
    private long ttlJitterSeconds;

    // ==================== 答卷份数 ====================

    /** 读缓存的答卷份数；未命中、开关关闭或 Redis 不可用都返回 null（调用方回源）。 */
    public Integer getAnswerCount(int surveyId) {
        if (!enabled || breaker.isOpen()) {
            return null;
        }
        try {
            String raw = redisTemplate.opsForValue().get(RedisKeys.answerCount(surveyId));
            breaker.recordSuccess();
            if (raw == null) {
                return null;
            }
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            // 缓存里被写进了非数字（手工写入 / 其它程序占用同名 key）：当未命中。
            // 不要因为一个脏值就熔断整个 Redis 通道。
            return null;
        } catch (RuntimeException e) {
            breaker.recordFailure("读答卷数缓存", e);
            return null;
        }
    }

    /** 回源后写回缓存。 */
    public void putAnswerCount(int surveyId, int count) {
        if (!enabled || breaker.isOpen()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(RedisKeys.answerCount(surveyId),
                    Integer.toString(count), Duration.ofSeconds(countTtlSeconds));
            breaker.recordSuccess();
        } catch (RuntimeException e) {
            breaker.recordFailure("写答卷数缓存", e);
        }
    }

    /**
     * 提交答卷成功后的增量更新：仅在缓存已存在时 +1。
     * ★ key 不存在时什么都不做 —— 直接 INCR 会凭空造出一个值 1 的"有效缓存"，下次读就把 1 当答卷数返回。
     */
    public void bumpAnswerCount(int surveyId) {
        if (!enabled || breaker.isOpen()) {
            return;
        }
        try {
            redisTemplate.execute(BUMP_IF_EXISTS, List.of(RedisKeys.answerCount(surveyId)));
            breaker.recordSuccess();
        } catch (RuntimeException e) {
            breaker.recordFailure("自增答卷数缓存", e);
            // 增量失败说明缓存里的计数已不可信：宁可让它回源一次，也别让"少 1"留到 TTL 到期。
            // 用静默删除（不参与熔断状态机）：在失败路径上再记一次成败会搅乱熔断窗口。
            deleteQuietly(RedisKeys.answerCount(surveyId));
        }
    }

    // ==================== 单题选项分布 ====================

    /** 读缓存的选项分布；未命中/Redis 不可用返回 null（调用方回源）。 */
    public List<Map<String, Object>> getQuestionStats(int questionId) {
        if (!enabled) {
            return null;
        }
        String json = getRaw(RedisKeys.questionStats(questionId), "读选项分布缓存");
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            // 反序列化失败（缓存里被写进了别的形状）：当未命中，回源覆盖；与 Redis 本身无关，不熔断。
            return null;
        }
    }

    /** 回源后写回缓存。 */
    public void putQuestionStats(int questionId, List<Map<String, Object>> stats) {
        putJson(RedisKeys.questionStats(questionId), stats, "写选项分布缓存");
    }

    // ---------- 有效样本口径（剔除可疑答卷）----------

    /**
     * 读"只统计有效答卷"的选项分布；未命中/Redis 不可用返回 null。
     * ★ 与全量口径是两个 key：切换"剔除可疑样本"开关时必须换 key，否则会读到另一个口径的结果。
     */
    public List<Map<String, Object>> getValidQuestionStats(int questionId) {
        if (!enabled) {
            return null;
        }
        String json = getRaw(RedisKeys.questionStatsValid(questionId), "读有效样本分布缓存");
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    /** 回源后写回（有效样本口径）。 */
    public void putValidQuestionStats(int questionId, List<Map<String, Object>> stats) {
        putJson(RedisKeys.questionStatsValid(questionId), stats, "写有效样本分布缓存");
    }

    // ==================== 单题填空文本 ====================

    /** 读缓存的填空文本；未命中/Redis 不可用返回 null（调用方回源）。 */
    public List<String> getQuestionTexts(int questionId) {
        if (!enabled) {
            return null;
        }
        String json = getRaw(RedisKeys.questionTexts(questionId), "读填空文本缓存");
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    /** 回源后写回缓存。 */
    public void putQuestionTexts(int questionId, List<String> texts) {
        putJson(RedisKeys.questionTexts(questionId), texts, "写填空文本缓存");
    }

    // ==================== 失效（写入路径调用）====================

    /**
     * 删掉多道题的统计缓存（分布 + 文本），一次 {@code DEL k1 k2 ...} 完成：逐题发命令会产生 2N 条往返。
     * 两个口径（qstats / qstats-valid）必须一起失效：新答卷的"有效性"会同时改变全量与有效样本的分布。
     * 没有单题 evictQuestion：只有"提交答卷"与"彻底删除问卷"两个失效点，都需要整批清。
     */
    public void evictQuestions(Collection<Integer> questionIds) {
        if (!enabled || questionIds == null || questionIds.isEmpty() || breaker.isOpen()) {
            return;
        }
        List<String> keys = new ArrayList<>(questionIds.size() * 3);
        for (Integer qid : questionIds) {
            if (qid != null) {
                keys.add(RedisKeys.questionStats(qid));
                // 两个口径必须一起失效：新答卷会同时改变全量与有效样本的分布
                keys.add(RedisKeys.questionStatsValid(qid));
                keys.add(RedisKeys.questionTexts(qid));
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        try {
            redisTemplate.delete(keys);
            breaker.recordSuccess();
        } catch (RuntimeException e) {
            breaker.recordFailure("批量删单题统计缓存", e);
        }
    }

    /**
     * 删掉整份问卷相关的统计缓存（问卷被彻底删除等整份问卷级别的写操作）。
     * 只删这份问卷自己的 key，绝不用前缀扫库（那会连限流计数一起清，见 {@link RedisKeys}）。
     *
     * @param surveyId    问卷 id
     * @param questionIds 该问卷的题目 id（清掉逐题的分布/文本缓存；可为空集合）
     */
    public void evictSurvey(int surveyId, Collection<Integer> questionIds) {
        if (!enabled) {
            return;
        }
        delete(RedisKeys.answerCount(surveyId), "删答卷数缓存");
        // 逐题清走批量接口（一次 DEL 多个 key）：一份 20 题的问卷循环调单题接口会产生 40 条往返。
        evictQuestions(questionIds);
    }

    // ==================== 内部工具 ====================

    private String getRaw(String key, String action) {
        if (breaker.isOpen()) {
            return null;
        }
        try {
            String raw = redisTemplate.opsForValue().get(key);
            breaker.recordSuccess();
            return raw;
        } catch (RuntimeException e) {
            breaker.recordFailure(action, e);
            return null;
        }
    }

    private void putJson(String key, Object value, String action) {
        if (!enabled || value == null || breaker.isOpen()) {
            return;
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            // 序列化失败只意味着"这次不缓存"：与 Redis 无关，不影响回源结果，也不熔断
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, json, ttlWithJitter());
            breaker.recordSuccess();
        } catch (RuntimeException e) {
            breaker.recordFailure(action, e);
        }
    }

    /**
     * 单题统计的 TTL：基准值 + 0~N 秒随机抖动，打散集体过期；代价是缓存最多多活 N 秒。
     */
    private Duration ttlWithJitter() {
        if (ttlJitterSeconds <= 0) {
            return Duration.ofSeconds(statsTtlSeconds);
        }
        long jitter = ThreadLocalRandom.current().nextLong(0, ttlJitterSeconds + 1);
        return Duration.ofSeconds(statsTtlSeconds + jitter);
    }

    /**
     * 静默删除：失败路径专用 —— 不参与熔断状态机、不打日志（见 {@link #bumpAnswerCount}）。
     */
    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException ignored) {
            // 已经在失败路径上，这里再记一条日志只会放大噪音
        }
    }

    private void delete(String key, String action) {
        if (breaker.isOpen()) {
            return;
        }
        try {
            redisTemplate.delete(key);
            breaker.recordSuccess();
        } catch (RuntimeException e) {
            breaker.recordFailure(action, e);
        }
    }
}
