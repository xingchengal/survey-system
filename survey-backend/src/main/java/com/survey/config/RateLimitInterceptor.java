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
 * 关键接口限流（固定窗口）：计数优先走 Redis，Redis 不可用时回退进程内计数。
 *
 * ★ 降级方向与缓存相反：Redis 挂了也必须继续限流，绝不能“Redis 一挂就等于不限流”。
 * IP 计数只能用 WebUtils.getConnectionIp()；用可伪造的 XFF 会让防爆破形同虚设。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private enum Subject {
        /** 按客户端 IP（未登录也能调的公开接口） */
        IP,
        /** 按登录用户 id（避免同出口 IP 的多个用户互相挤占配额） */
        USER
    }

    /**
     * 限流规则。
     *
     * @param prefix  路径前缀（startsWith 匹配，可覆盖带路径参数的端点）
     * @param name    规则名，进限流 key（见 RedisKeys.rateLimit）
     * @param subject 计数维度
     */
    private record Rule(String name, String prefix, int limit, long windowMs, Subject subject) {
    }

    private static final List<Rule> RULES = List.of(
            // 登录：10 次/分钟/IP，把暴力破解成本抬到不可行
            new Rule("login", "/api/user/login", 10, 60_000, Subject.IP),
            // AI 生成：1 次/30 秒/用户（真实计费；按用户而非共享出口 IP）
            new Rule("ai", "/api/ai/generate", 1, 30_000, Subject.USER),
            // 匿名记访问量 / 匿名提交答卷
            new Rule("view", "/api/survey/view/", 30, 60_000, Subject.IP),
            new Rule("submit", "/api/answer/submit-share/", 30, 60_000, Subject.IP),
            // ★ 登录用户提交答卷必须单独一条：submit-share 前缀匹配不到 /submit/{id}，
            //   否则注册个账号就能无限灌；按用户计数（同出口 IP 可能有多个正常用户）。
            new Rule("submit-auth", "/api/answer/submit/", 60, 60_000, Subject.USER),
            // /api/health 每次都要借连接池连接，不限流会被脚本连 Tomcat 线程一起吸住
            new Rule("health", "/api/health", 60, 60_000, Subject.IP),
            // 滑块取图（CPU 密集）与判定（极轻）必须分开计数：共用计数器会让真实用户很快见底
            new Rule("captcha-slider", "/api/captcha/slider", 20, 60_000, Subject.IP),
            new Rule("captcha-verify", "/api/captcha/verify", 120, 60_000, Subject.IP),
            // refresh token：正常 12 小时一次，30 次/分覆盖多标签同时刷新，也挡住爆破
            new Rule("refresh", "/api/user/refresh", 30, 60_000, Subject.IP)
    );

    private static final class Counter {
        volatile long windowStart;
        final AtomicInteger count = new AtomicInteger();
    }

    /** 降级用的进程内计数：只在 Redis 不可用时生效，多实例下配额不共享。 */
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    /** Redis 计数（多实例共享配额）；返回 null 表示不可用，需回退 counters。 */
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

        // 计数主体：按用户取 JwtAuthFilter 注入的 X-User-Id（未登录为空串 → 同一个桶）。
        // 计数 IP 只能用 getConnectionIp()：getClientIp() 信任可伪造的 XFF，
        // 攻击者每请求换一个 XFF 就能绕过防爆破（日志里的 IP 仍走 getClientIp）。
        String subject = rule.subject() == Subject.IP
                ? WebUtils.getConnectionIp(request)
                : String.valueOf(request.getHeader("X-User-Id"));

        // Redis 返回 null 表示不可用（或开关关闭），此时必须回退进程内计数，绝不能放行。
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
        // 固定文案手拼 JSON 安全；要塞变量必须改用 WebUtils.writeJsonError()，
        // 否则引号/反斜杠会产出非法 JSON，甚至变成响应体注入。
        response.getWriter().write("{\"success\":false,\"code\":429,\"message\":\"操作过于频繁，请稍后再试\"}");
        return false;
    }

    private Rule match(String uri) {
        for (Rule r : RULES) {
            if (uri.startsWith(r.prefix())) {
                return r;
            }
        }
        return null;
    }

    /** 降级路径的固定窗口计数；对单个 Counter 加锁，竞争只到同一 IP/用户。 */
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
     * 定期清理过期窗口，避免降级 counters 随访问过的 IP/用户数无限增长（内存泄漏）。
     * 依赖启动类的 @EnableScheduling：去掉它清理不再运行，但限流本身仍工作。
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
