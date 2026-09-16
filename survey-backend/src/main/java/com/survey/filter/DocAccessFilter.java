package com.survey.filter;

import com.survey.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 文档访问控制 Filter
 * 只允许登录的管理员访问 /doc.html、/v3/api-docs 等接口文档相关路径
 */
@Component
@Order(2)   // 在 JwtAuthFilter 之后执行
public class DocAccessFilter implements Filter {

    @Autowired
    private JwtUtil jwtUtil;

    private static final String DOC_COOKIE_NAME = "doc_token";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String uri = request.getRequestURI();

        // 只拦截文档相关路径
        if (!isDocPath(uri)) {
            chain.doFilter(request, response);
            return;
        }

        // OPTIONS 预检放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 从 Cookie 里读 token
        String token = getCookie(request, DOC_COOKIE_NAME);
        if (token == null || token.isEmpty()) {
            writeJson(response, 403, "禁止访问：请先以管理员身份登录");
            return;
        }

        // 解析 JWT
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            writeJson(response, 403, "禁止访问：登录已过期，请重新登录");
            return;
        }

        // 校验角色
        String role = claims.get("role", String.class);
        if (!"admin".equals(role)) {
            writeJson(response, 403, "禁止访问：仅管理员可查看接口文档");
            return;
        }

        // 通过，放行
        chain.doFilter(request, response);
    }

    /**
     * 是否文档相关路径
     */
    private boolean isDocPath(String uri) {
        return uri.startsWith("/doc.html")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/webjars")
                || uri.startsWith("/knife4j")
                || uri.equals("/favicon.ico");
    }

    /**
     * 读 Cookie
     */
    private String getCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /**
     * 返回 JSON 响应
     */
    private void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"code\":" + code + ",\"message\":\"" + message + "\"}");
    }
}