package com.survey.filter;

import com.survey.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(1)
public class JwtAuthFilter implements Filter {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // CORS 预检放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();

        // ★ 关键：只拦截 /api/ 开头的接口
        // 其他（静态资源、SPA 路由、首页）一律放行
        if (!uri.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // 白名单放行（无需登录就能访问的接口）
        if (isWhiteList(uri)) {
            chain.doFilter(request, response);
            return;
        }

        // 从 Authorization 头拿 token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeJson(response, 401, "未登录或 token 缺失");
            return;
        }

        String token = authHeader.substring(7);
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            writeJson(response, 401, "token 无效或已过期");
            return;
        }

        Integer userId = claims.get("userId", Integer.class);
        String role = claims.get("role", String.class);
        String username = claims.get("username", String.class);

        // 包装 request，覆盖 X-User-Id / X-User-Role 请求头
        HeaderRewriteRequestWrapper wrapper = new HeaderRewriteRequestWrapper(request);
        wrapper.setHeader("X-User-Id", userId != null ? String.valueOf(userId) : "");
        wrapper.setHeader("X-User-Role", role != null ? role : "");
        wrapper.setHeader("X-Username", username != null ? username : "");

        chain.doFilter(wrapper, response);
    }

    private void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"code\":" + code + ",\"message\":\"" + message + "\"}");
    }

    /**
     * 白名单（无需登录就能访问的 API）
     */
    private boolean isWhiteList(String uri) {
        return uri.startsWith("/api/user/login")
                || uri.startsWith("/api/user/register")
                || uri.startsWith("/api/survey/share/")
                || uri.startsWith("/api/survey/view/")
                || uri.startsWith("/api/question/list/")
                || uri.startsWith("/api/answer/submit-share/")
                || uri.startsWith("/api/announcement/active")
                || uri.startsWith("/api/health");
    }
}