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
 * 答卷统计的 Redis 预聚合缓存（读多写少、重复计算的那几条统计查询）。
 *
 * ── 为什么值得缓存（本机实测，2 万答卷 / 12 万明细，32 并发 / 2000 次请求）──
 *   | 接口 | 缓存关闭 | 缓存开启 |
 *   |---|---|---|
 *   | `GET /api/answer/statistics/{questionId}`（`GROUP BY content`） | QPS 165、P50 **190ms**、P99 256ms | QPS **2333**、P50 **11ms**、P99 41ms |
 *   | `GET /api/answer/count/{surveyId}`（`COUNT(*)`）                | QPS 1371、P50 21ms | QPS 3082、P50 9ms |
 *   | `GET /api/answer/texts/{questionId}`（2000 条文本）              | QPS 566、P50 26ms | QPS 1120、P50 13ms |
 *   （同一次对比里**未接缓存**的两条接口数字完全不变 —— 个人统计 11.5ms、答卷列表 239ms，
 *     说明差异来自缓存本身，而不是机器状态波动。）
 *   这三条的共同点是**结果只依赖已提交的答卷**，而答卷是"偶尔写一次、被反复看"的数据，
 *   每次访问都去扫明细表属于纯粹的重复计算。
 *
 * ── 一致性策略（**失效优先，增量只用于计数**）──────────────
 *   · 答卷份数：提交成功后**只在缓存已存在时** `INCR`（见 {@link #bumpAnswerCount}）；
 *   · 单题分布 / 填空文本：提交成功后**直接删掉**，下次访问回源重算 ——
 *     多选题"一选项一行"、以及将来可能的删除答卷，都会让分布以难以穷举的方式变化，
 *     增量维护的风险远高于"删掉重算"的成本（一条 `GROUP BY` 而已）；
 *   · 所有 key 都带 TTL，且**按回源成本分别设置**（三个配置项见下方）：
 *     单题分布/文本 600 秒 + 随机抖动（回源 190ms，贵），答卷数 60 秒（回源 21ms，便宜）。
 *     TTL 是**兜底**，不是主要手段 —— 正常失效靠写入路径主动删除。
 *   · 抖动（{@code app.cache.ttl-jitter-seconds}）用于打散"集体过期"：同一批 key 若在同一时刻写入，
 *     就会在同一刻集体过期，那一刻的并发请求会一起回源（缓存击穿）。
 *
 * ── ★ 为什么"只在 key 存在时 INCR"（不是直接 INCR）──────────
 *   直接 `INCR survey:cache:answer-count:9` 在 key **不存在**时会凭空创建一个值为 1 的键，
 *   而库里的真实答卷数是 20000 —— 下一次读缓存就会把 **1** 当成答卷数返回，
 *   并且这个错值会一直"看起来是有效缓存"直到 TTL 到期。这类"缓存造出假数据"的 bug
 *   比"缓存没命中"危险得多，所以增量更新必须先确认缓存已经被读过（存在）。
 *   ★ 同时 `INCR` **不会**清除原有 TTL（Redis 语义），所以这里也不做续期：
 *     活跃问卷的计数缓存会周期性回源重建，等于自带一道自愈。
 *
 * ── 降级：Redis 挂了不能拖垮业务（两层，缺一不可）──────────
 *   ① **降级**：每个方法都把 Redis 异常吞掉并返回 null（= 未命中），
 *      调用方随后走原来的"直查数据库"路径，功能不受影响，只是失去加速；
 *   ② **熔断**（{@link RedisCircuitBreaker}）：失败一次就短路 10 秒，期间**不再尝试** Redis。
 *      ★ 为什么必须有第 ② 层：实测把 Redis 停掉后接口虽然仍是 200，但每次请求都要等一次
 *      Lettuce 命令超时（timeout=2s），单请求耗时从 12ms 涨到约 4000ms ——
 *      "只是变慢"的降级在真实流量下会占满 Tomcat 线程池，效果等同宕机。
 *   ★ 这与限流的降级取向**相反**（见 {@code config/RedisRateLimiter}）：
 *     缓存失败可以安全地"当作没命中"，限流失败必须"当作仍然受限"。
 *
 * ── 一个类型细节（改代码前先看）──────────────────────────
 *   回源路径（MyBatis）返回的 `count` 是 `Long`，而缓存命中时 JSON 反序列化出来的是
 *   `Integer`（值很小时）。**不要**对 `map.get("count")` 做 `(Long)` 强转：
 *   那条代码在"有缓存"和"没缓存"两种情况下行为不同。前端只把值当数字用，不受影响。
 */
@Service
public class StatsCacheService {

    /**
     * 「缓存已存在才自增」的 Lua 脚本。
     * ★ 必须用 Lua 保证"判存在 + 自增"的原子性：分成两条命令时，
     *   两个并发提交可能都在 key 缺失时判断为"不存在"而放弃自增（丢计数），
     *   或者都在 TTL 到期瞬间判断为"存在"而给一个刚被删掉的键自增（造出假值）。
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
     * JSON 编解码器。★ 自己持有实例，**不要**改成注入 Spring 的 ObjectMapper：
     *   本项目用的是 Jackson 2（`com.fasterxml.jackson`），而 Spring Boot 4 **不再为它注册
     *   ObjectMapper bean**（注入会在启动时直接失败：No qualifying bean of type ObjectMapper）。
     *   QwenClient 与 WebUtils 也都是自己 new 的，这里与既有写法保持一致。
     *   缓存里存的是本项目自己写的简单结构（{content,count} 列表 / 字符串列表），
     *   不需要与 MVC 的消息转换器共享任何配置。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 总开关。压测对比与无 Redis 的环境（CI）可以关掉，行为退回"每次都直查库"。 */
    @Value("${app.cache.stats-enabled:true}")
    private boolean enabled;

    /**
     * 单题分布 / 填空文本的 TTL（秒）。
     * ★ 这两条回源要扫明细表（2 万答卷下 P50 约 190ms），所以 TTL 长，失效靠写入路径主动删除。
     */
    @Value("${app.cache.stats-ttl-seconds:600}")
    private long statsTtlSeconds;

    /**
     * 答卷数的 TTL（秒），**故意比统计短得多**。两条理由：
     *   ① 回源只是一条 `COUNT(*)`（实测 21ms），短 TTL 的代价可以忽略；
     *   ② 提交答卷后走的是 `INCR` 增量 —— 万一这条增量失败（Redis 抖动但还没触发熔断），
     *      缓存就会比库里少 1；短 TTL 把这个"少 1"的窗口从 10 分钟压到 1 分钟。
     */
    @Value("${app.cache.count-ttl-seconds:60}")
    private long countTtlSeconds;

    /**
     * 单题统计 TTL 的**随机抖动上限**（秒）。
     * ★ 用途：同一批 key 若在同一时刻写入，就会在同一时刻集体过期，
     *   那一刻的并发请求会一起回源（缓存击穿）。叠加 0~N 秒随机值可把过期时刻打散。
     * ★ 答卷数不加抖动：它的 TTL 本来就要短而稳定（见上一条）。
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
            // ★ 不要因为一个脏值就熔断整个 Redis 通道。
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
     * 提交答卷成功后的**增量更新**：仅在缓存已存在时 +1。
     * ★ key 不存在时什么都不做（不是"从 0 开始算"）——理由见类注释。
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
            // ★ 增量失败意味着"缓存里的计数已经不可信"：宁可让它回源一次（COUNT(*) 约 21ms），
            //   也不要让"答卷数少 1"的错误值一直留到 TTL 到期。
            //   ★ 用"静默删除"而不是 delete()：后者会参与熔断状态机，
            //     在失败路径上再记一次成功/失败会把熔断窗口搅乱。
            //   ★ 若 Redis 彻底不可用，这条删除也会失败并被吞掉 —— 此时读缓存同样失败，
            //     调用方仍会回源，结果依然正确。
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
            // 反序列化失败（缓存里被写进了别的形状）：当未命中，回源覆盖。
            // ★ 与 Redis 本身无关，不熔断。
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
     * 删掉多道题的统计缓存（分布 + 文本），一次 `DEL k1 k2 ...` 完成。
     * ★ 提交一份答卷就要失效整份问卷的题目（一次提交可能涉及十几道题）；
     *   逐题各发两条 DEL 会产生 2N 条 Redis 往返，所以这里只保留批量入口。
     * ★ 这里**没有**单题的 evictQuestion：全项目只有"提交答卷"与"彻底删除问卷"两个失效点，
     *   它们都需要整批清 —— 留一个无人调用的单题入口，只会让后来者以为还有第二条失效路径。
     */
    public void evictQuestions(Collection<Integer> questionIds) {
        if (!enabled || questionIds == null || questionIds.isEmpty() || breaker.isOpen()) {
            return;
        }
        List<String> keys = new ArrayList<>(questionIds.size() * 3);
        for (Integer qid : questionIds) {
            if (qid != null) {
                keys.add(RedisKeys.questionStats(qid));
                // ★ 两个口径必须一起失效：新答卷的"有效性"会同时改变全量与有效样本的分布
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
     * 删掉整份问卷相关的统计缓存。
     *
     * ★ 调用点：问卷被彻底删除（级联删除子表）等**整份问卷级别**的写操作。
     *   只删这份问卷自己的 key，**绝不用前缀扫库**（那会连限流计数一起清，见 {@link RedisKeys}）。
     *
     * @param surveyId    问卷 id
     * @param questionIds 该问卷的题目 id（用于清掉逐题的分布/文本缓存；可为空集合）
     */
    public void evictSurvey(int surveyId, Collection<Integer> questionIds) {
        if (!enabled) {
            return;
        }
        delete(RedisKeys.answerCount(surveyId), "删答卷数缓存");
        // ★ 逐题清走批量接口（一次 DEL 多个 key），不要在这里 for 循环调 evictQuestion：
        //   一份 20 题的问卷会因此产生 40 条 Redis 往返。
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
     * 单题统计的 TTL：基准值 + 0~N 秒随机抖动。
     * ★ 抖动是为了打散"集体过期 → 并发一起回源"的击穿；代价只是缓存最多多活 N 秒。
     */
    private Duration ttlWithJitter() {
        if (ttlJitterSeconds <= 0) {
            return Duration.ofSeconds(statsTtlSeconds);
        }
        long jitter = ThreadLocalRandom.current().nextLong(0, ttlJitterSeconds + 1);
        return Duration.ofSeconds(statsTtlSeconds + jitter);
    }

    /**
     * 静默删除：**失败路径专用** —— 不参与熔断状态机、不打日志。
     * ★ 与 {@link #delete(String, String)} 的区别见 {@link #bumpAnswerCount} 里的注释。
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
