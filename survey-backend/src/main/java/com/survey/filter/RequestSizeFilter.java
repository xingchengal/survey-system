package com.survey.filter;

import com.survey.util.WebUtils;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 请求体大小上限 —— 在**反序列化之前**就把超大请求挡掉（超限返回 413）。
 *
 * ── 为什么需要它 ──────────────────────────────────────
 *   项目里所有的"长度上限"（{@code common/TextLimits}、{@code PageParams} 等）都发生在
 *   **Spring 已经把整个请求体反序列化成对象之后**。也就是说：
 *   {@code @RequestBody List<AnswerDetail> details} 收到一个 200MB 的 JSON 时，
 *   Jackson 会先老老实实把它读进堆、构造出几十万个对象，
 *   然后 {@code AnswerService.submit()} 才会说"单题回答不能超过 1000 个字符" ——
 *   **内存在发出这句礼貌拒绝之前就已经被吃光了**。这类"事后校验"挡不住资源耗尽型攻击。
 *   （Tomcat 默认对请求体大小没有上限；{@code spring.servlet.multipart.max-file-size}
 *     只管 multipart 表单，本项目用的是 JSON，不受它约束。）
 *
 * ── 怎么挡 ────────────────────────────────────────────
 *   读 {@code Content-Length}，超过阈值直接 413，**不进 Controller、不做反序列化**。
 *   ★ 只检查"带 body 的方法"（POST/PUT/PATCH）—— GET/DELETE 通常没有 Content-Length（-1），
 *     一律检查会把所有 GET 请求误杀。
 *   ★ 长度为未知（-1：无 body 的 POST 或 chunked 传输）时**放行 + 记 warn**，
 *     不能一律拒绝 —— 原因见下方代码注释。
 *
 * ── 阈值怎么定的 ──────────────────────────────────────
 *   4MB。依据：头像接口允许 3MB 的 data URL 字符串（见 common/AvatarValidator），
 *   加上 JSON 引号转义与其它字段的开销，4MB 足够；
 *   而任何"正常业务"的请求体都远小于它（一份答卷几十 KB 顶天了）。
 */
@Component
@Order(0)   // ★ 最先执行：必须排在 JwtAuthFilter 之前，越早拒绝越省资源
public class RequestSizeFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestSizeFilter.class);

    /** 允许的最大请求体字节数（4MB） */
    private static final long MAX_BODY_BYTES = 4L * 1024 * 1024;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String method = request.getMethod();
        boolean mayHaveBody = "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method);
        if (!mayHaveBody) {
            chain.doFilter(request, response);   // GET/DELETE 等：不看长度，直接放行
            return;
        }

        long length = request.getContentLengthLong();
        if (length > MAX_BODY_BYTES) {
            LOG.warn("拒绝超大请求体：{} {} length={} 上限={}", method, request.getRequestURI(),
                    length, MAX_BODY_BYTES);
            WebUtils.writeJsonError(response, 413, "请求体过大（最大 " + (MAX_BODY_BYTES / 1024 / 1024) + "MB）");
            return;
        }
        // ★ length == -1 表示"没有 Content-Length"，它有两种来源，必须区别对待：
        //   ① **请求根本没有 body** —— 这在本项目里很常见：POST /api/survey/publish/{id}、
        //      /api/survey/view/{code}、/api/user/logout、/api/demo/init、/api/template-seeder/init
        //      全都不带 body（curl -X POST 也不发 Content-Length）；
        //   ② 客户端用 chunked 传输（刻意不声明长度）。
        //   ★ 一律拒绝会误杀 ①：publish / view / logout 这类无 body 的 POST 会直接变成 413。
        //   所以这里**放行 + 记一条 warn**：本过滤器能挡住"明确声明了超大 body"的请求（这是主要防护目标），
        //   挡不住刻意用 chunked 绕过的请求 —— 要堵那一类，需要再包一层"限制读取字节数"的
        //   HttpServletRequestWrapper（用起来风险更高），当前单实例演示系统先不做，已如实记录。
        if (length < 0) {
            LOG.warn("请求没有 Content-Length，跳过体积检查（可能是无 body 的 POST，也可能是 chunked）：{} {}",
                    method, request.getRequestURI());
        }

        chain.doFilter(request, response);
    }
}
