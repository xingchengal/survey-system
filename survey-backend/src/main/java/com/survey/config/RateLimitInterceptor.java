package com.survey.config;

import com.survey.util.WebUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 关键接口的限流拦截器（固定窗口计数；**计数在 Redis**，Redis 不可用时回退进程内计数）。
 *
 * ── 为什么需要它 ──────────────────────────────────────
 *   关键入口都不能没有配额，例如：
 *     1. `POST /api/user/login`          —— 可无限次暴力破解（配合"演示账号密码都是 123456"极其危险）
 *     2. `POST /api/ai/generate`         —— 每次都是**真实计费**的大模型调用，单次最长占用
 *                                           Tomcat 线程 120 秒（QwenClient 的 readTimeout）；
 *                                           脚本刷起来既烧钱，又能把线程池占满拖垮全站
 *     3. `POST /api/survey/view/{code}`  —— 公开、不需凭证，可被脚本刷高访问量
 *     4. `POST /api/answer/submit-share/{code}` —— 公开、不需凭证，可被灌垃圾答卷
 *   完整规则清单见下方 RULES。
 *
 * ── 计数放在哪（★ 改这里之前先读完）────────────────────
 *   · **主路径是 Redis**（{@code RedisRateLimiter}：Lua `INCR` + `PEXPIRE`）：
 *     配额在多实例之间共享。下面那个进程内 counters **已经不是主路径** ——
 *     它只活在单个 JVM 里，多实例部署时每个实例各拦一份、配额被放大 N 倍，
 *     而"多实例"恰恰是这条规则真正被需要的时候。
 *   · **Redis 不可用时回退进程内计数，而不是放行**：宁可退化成"单机限流"，
 *     也不能出现"Redis 一挂就等于不限流"。代价（如实说明）：降级期间多实例的配额
 *     会被放大，且实例重启会重置计数。
 *   · 用**固定窗口**而非滑动窗口：实现简单、无内存膨胀风险。
 *     代价是窗口边界最多放行 2 倍配额（例如 10 次/分的规则，跨窗口瞬间最多 20 次）——
 *     对"防暴力破解、防刷"这个目标完全可接受（Redis 与进程内两条路径的窗口语义一致）。
 *
 * ── 与全局异常处理的关系 ──────────────────────────────
 *   限流命中直接写 429 响应并中断请求，不走 ControllerAdvice（它只管 Controller 抛出的异常）。
 *   响应体刻意与其它失败响应保持同样的形状 {success:false, code, message}，前端无需特殊处理。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /** 按什么维度计数 */
    private enum Subject {
        /** 按客户端 IP（用于未登录也能调的公开接口） */
        IP,
        /** 按登录用户 id（用于"一个人一个配额"的接口，避免同一出口 IP 的多个用户互相影响） */
        USER
    }

    /**
     * 限流规则。
     *
     * @param name     规则名（进限流 key，见 {@code common/RedisKeys.rateLimit}）
     * @param prefix   路径前缀（用 startsWith 匹配，便于覆盖带路径参数的端点）
     * @param limit    窗口内允许的最大次数
     * @param windowMs 窗口长度（毫秒）
     * @param subject  计数维度
     */
    private record Rule(String name, String prefix, int limit, long windowMs, Subject subject) {
    }

    private static final List<Rule> RULES = List.of(
            // 登录：10 次/分钟/IP —— 正常用户输错几次完全够用，但把暴力破解的成本抬到不可行
            // ★ IP 取的是**真实连接地址**（WebUtils.getConnectionIp），不是可伪造的 XFF，
            //   否则换个请求头就能绕过这条规则（详见 preHandle 里的说明）。
            new Rule("login", "/api/user/login", 10, 60_000, Subject.IP),
            // AI 生成：1 次/30 秒/用户 —— 免费额度 + 真实计费；按用户而非 IP（同局域网出口 IP 可能多人共用）
            new Rule("ai", "/api/ai/generate", 1, 30_000, Subject.USER),
            // 匿名记访问量：30 次/分钟/IP
            new Rule("view", "/api/survey/view/", 30, 60_000, Subject.IP),
            // 匿名提交答卷：30 次/分钟/IP
            new Rule("submit", "/api/answer/submit-share/", 30, 60_000, Subject.IP),
            // ★ 登录用户提交答卷**必须单独一条规则**：前缀 "/api/answer/submit-share/"
            //   匹配不到 "/api/answer/submit/{id}"，否则同一件事的两条入口一个限 30 次/分、
            //   一个完全不限（注册个账号即可无限灌）。
            //   按**用户**计数（同一出口 IP 下可能有多个正常用户）。
            new Rule("submit-auth", "/api/answer/submit/", 60, 60_000, Subject.USER),
            // ★ /api/health 是**免登录白名单**接口，而且是唯一"每次都要向连接池
            //   借一条连接"的公开端点（见 HealthController）。没有限流时，
            //   一个循环脚本就能把连接池（prod 只有 20 条）和 Tomcat 线程一起吸住。
            //   60 次/分钟对探针与看板绿点都绰绰有余。
            new Rule("health", "/api/health", 60, 60_000, Subject.IP),
            // ★ 滑块验证码必须**分成两条规则**（不要合并回去）：
            //   两者若共用同一个计数器，真实用户一次注册就可能用掉 2~4 次（页面加载取图 +
            //   每次失败换图 + 每次拖动后判定），再叠加刷新页面/多标签，20 次很容易见底 →
            //   后续判定被 429 挡掉，前端只能显示"校验服务暂时不可用"，
            //   用户完全不知道是"你太快了"。
            //   两条规则的**成本特征完全不同**，限额理应不同：
            //     · /slider  要画两张 320×160 的 PNG 再 base64（CPU 密集）→ 保持 20 次/分/IP；
            //     · /verify  只是一次哈希查表 + 比较（极轻）→ 放宽到 120 次/分/IP，
            //       按"用户拖 10 次、每次 3 秒"的极端情况留足余量，同时仍能挡住脚本爆破位置。
            //   ★ 两条前缀互不重叠（"/slider" 与 "/verify" 谁也不包含谁），
            //     而 match() 是"第一个 startsWith 命中就返回"，所以这两行的顺序无关紧要。
            new Rule("captcha-slider", "/api/captcha/slider", 20, 60_000, Subject.IP),
            new Rule("captcha-verify", "/api/captcha/verify", 120, 60_000, Subject.IP),
            // ★ refresh token 换 access token。
            //   正常客户端只在 access token 过期时调一次（12 小时一次），
            //   30 次/分钟足以覆盖"多标签页同时刷新"，又挡住被当成爆破入口。
            new Rule("refresh", "/api/user/refresh", 30, 60_000, Subject.IP)
    );

    /** 一个计数窗口（固定窗口） */
    private static final class Counter {
        volatile long windowStart;
        final AtomicInteger count = new AtomicInteger();
    }

    /**
     * **降级用的**进程内计数（Redis 不可用时才走它）。
     * ★ 它不再承担"多实例共享配额"的职责，所以不要把它当主路径来读。
     */
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** Redis 计数（多实例共享配额）。★ 返回 null 表示"Redis 不可用"，此时回退上面的 counters。 */
    @Autowired
    private RedisRateLimiter redisRateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        String uri = request.getRequestURI();
        Rule rule = match(uri);
        if (rule == null) {
            return true;    // 不在限流名单里，直接放行
        }

        // 计数主体：按用户时取 JwtAuthFilter 注入的 X-User-Id（未登录会是空串 → 退化成同一个桶）
        // ★ 计数用的 IP 必须是 getConnectionIp()（容器层真实连接地址，完全不看请求头），
        //   **不能**用 getClientIp() —— 后者优先信任 X-Forwarded-For / X-Real-IP：
        //   攻击者爆破登录时每个请求换一个 XFF，计数 key 就每次都不同，
        //   "登录 10 次/分钟/IP"这条防爆破规则直接形同虚设。
        //   日志里的 IP 仍走 getClientIp()（那里只是给人看的参考信息，不受影响）。
        String subject = rule.subject() == Subject.IP
                ? WebUtils.getConnectionIp(request)
                : String.valueOf(request.getHeader("X-User-Id"));

        // ★ 配额判定先走 Redis（多实例共享同一份计数）；
        //   返回 null 表示 Redis 不可用（或开关关闭），此时**必须**回退到进程内计数继续限流 ——
        //   绝不能"Redis 一挂就放行"（两条链路的降级方向相反，见 RedisRateLimiter 类注释）。
        Long remoteCount = redisRateLimiter.increment(rule.name(), subject, rule.windowMs());
        boolean allowed = remoteCount != null
                ? remoteCount <= rule.limit()
                : allow(rule.name() + '|' + subject, rule.limit(), rule.windowMs());

        if (allowed) {
            return true;
        }

        LOG.warn("触发限流：rule={} uri={} subject={} 上限={}/{}ms",
                rule.name(), uri, subject, rule.limit(), rule.windowMs());

        response.setStatus(429);      // Too Many Requests
        response.setContentType("application/json;charset=UTF-8");
        // 固定文案、不含任何用户输入，所以这里手工拼 JSON 是安全的。
        // ★ 哪天要往 message 里塞变量，请改用 WebUtils.writeJsonError()（Jackson 序列化），
        //   否则双引号或反斜杠会产出非法 JSON、动态内容还会变成响应体注入。
        response.getWriter().write("{\"success\":false,\"code\":429,\"message\":\"操作过于频繁，请稍后再试\"}");
        return false;
    }

    /** 找出该 URI 命中的规则（没有则返回 null） */
    private Rule match(String uri) {
        for (Rule r : RULES) {
            if (uri.startsWith(r.prefix())) {
                return r;
            }
        }
        return null;
    }

    /**
     * **降级路径**的固定窗口计数：窗口过期就重置，否则累加并判断是否超限。
     * ★ 对同一个 Counter 加锁 —— 竞争只发生在同一 IP/用户的并发请求上，粒度足够细。
     * ★ 只在 Redis 不可用时被调用（见 preHandle），所以它不承担多实例共享的职责。
     */
    private boolean allow(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        Counter c = counters.computeIfAbsent(key, k -> new Counter());
        synchronized (c) {
            if (now - c.windowStart >= windowMs) {
                c.windowStart = now;
                c.count.set(0);
            }
            return c.count.incrementAndGet() <= limit;
        }
    }

    /**
     * 定期清理过期窗口，避免降级用的 counters 随访问过的 IP/用户数无限增长（长期运行的内存泄漏）。
     * ★ 依赖启动类上的 @EnableScheduling（去掉它这个清理就不会跑，但限流本身仍工作）。
     * ★ Redis 路径的窗口过期由 key 的 TTL 自己负责，与这里无关。
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        long maxWindow = RULES.stream().mapToLong(Rule::windowMs).max().orElse(60_000L);
        int before = counters.size();
        counters.entrySet().removeIf(e -> now - e.getValue().windowStart > maxWindow * 5);
        int removed = before - counters.size();
        if (removed > 0) {
            LOG.debug("限流计数器清理：移除 {} 个过期窗口，剩余 {}", removed, counters.size());
        }
    }
}
