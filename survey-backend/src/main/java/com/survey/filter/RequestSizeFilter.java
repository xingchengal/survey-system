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
 * 请求体大小上限 —— 在反序列化之前挡掉超大请求（413）。
 *
 * ★ 只在反序列化之后校验长度挡不住资源耗尽：200MB 的 JSON 会先被 Jackson 读进堆再拒绝。
 * 只检查 POST/PUT/PATCH；GET/DELETE 没有 Content-Length，一律检查会误杀。
 * 阈值 4MB：头像 data URL 上限 3MB（AvatarValidator）+ JSON 转义开销。
 */
@Component
@Order(0)   // 必须排在 JwtAuthFilter 之前：越早拒绝越省资源
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
        // ★ length == -1 有两种来源：无 body 的 POST（publish / view / logout）与 chunked 传输。
        //   一律拒绝会误杀前者，所以放行 + 记 warn；chunked 绕过留给后续“限制读取字节数”。
        if (length < 0) {
            LOG.warn("请求没有 Content-Length，跳过体积检查（可能是无 body 的 POST，也可能是 chunked）：{} {}",
                    method, request.getRequestURI());
        }

        chain.doFilter(request, response);
    }
}
