package com.survey.filter;

import com.survey.entity.User;
import com.survey.service.UserService;
import com.survey.util.JwtUtil;
import com.survey.util.WebUtils;
import com.survey.common.Role;
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
 * 接口文档访问控制：/doc.html、/v3/api-docs 等只给管理员，凭证是 doc_token Cookie。
 *
 * ★ 文档页由浏览器直接打开、带不了 Authorization 头，所以在 JwtAuthFilter 之后用 Cookie 单独把关。
 * ★ 六道校验：Cookie 非空 → JWT 可解析 → scope=doc → role=admin → 账号仍有效 → 票不早于 token_valid_after；
 *   任一不过返回 403（“没权限看”，不是“没登录”）。
 * Cookie 是单独签发的 2 小时文档票（不是 12 小时的登录 token），HttpOnly + SameSite=Strict。
 */
@Component
@Order(2)   // 在 JwtAuthFilter 之后执行
public class DocAccessFilter implements Filter {

    @Autowired
    private JwtUtil jwtUtil;

    /** 校验 5 用：确认票对应的账号仍然有效（未注销 / 未被删）。 */
    @Autowired
    private UserService userService;

    private static final String DOC_COOKIE_NAME = "doc_token";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String uri = request.getRequestURI();

        // 不是文档路径一律不碰
        if (!isDocPath(uri)) {
            chain.doFilter(request, response);
            return;
        }

        // OPTIONS 预检放行（预检不带 Cookie）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 校验 1：从 Cookie 里取 token
        String token = getCookie(request, DOC_COOKIE_NAME);
        if (token == null || token.isEmpty()) {
            writeJson(response, 403, "禁止访问：请先以管理员身份登录");
            return;
        }

        // 校验 2：解析 JWT（过期/签名错返回 null）
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            writeJson(response, 403, "禁止访问：登录已过期，请重新登录");
            return;
        }

        // 校验 3：必须是文档票（scope=doc）；把登录 token 塞进这个 Cookie 不算
        if (!JwtUtil.SCOPE_DOC.equals(claims.get("scope", String.class))) {
            writeJson(response, 403, "禁止访问：文档通行证无效，请重新登录后再访问");
            return;
        }

        // 校验 4：必须是管理员
        String role = claims.get("role", String.class);
        if (!Role.isAdmin(role)) {
            writeJson(response, 403, "禁止访问：仅管理员可查看接口文档");
            return;
        }

        // 校验 5：账号仍有效（文档票 2 小时内无法吊销，注销/被删后必须立刻失效）
        Integer userId = claims.get("userId", Integer.class);
        User currentUser = userId == null ? null : userService.getById(userId);
        if (currentUser == null) {
            writeJson(response, 403, "禁止访问：账号不存在或已注销，请重新登录");
            return;
        }

        // 校验 6：票不能早于 token_valid_after（改密码 / 恢复账号后旧票作废）
        if (!JwtUtil.isIssuedAtOrAfter(claims, currentUser.getTokenValidAfter())) {
            writeJson(response, 403, "禁止访问：登录状态已失效，请重新登录后再访问");
            return;
        }

        chain.doFilter(request, response);
    }

    /** 是否文档路径。不能包含 /favicon.ico：否则非管理员连站点图标都拿不到。 */
    private boolean isDocPath(String uri) {
        return uri.startsWith("/doc.html")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/webjars")
                || uri.startsWith("/knife4j");
    }

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

    /** 统一 JSON 错误响应；委托 WebUtils 用 Jackson 序列化，避免手拼 JSON 产生非法字符。 */
    private void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        WebUtils.writeJsonError(response, code, message);
    }
}
