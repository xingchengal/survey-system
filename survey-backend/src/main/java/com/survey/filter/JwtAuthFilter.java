package com.survey.filter;

import com.survey.entity.User;
import com.survey.service.UserService;
import com.survey.util.JwtUtil;
import com.survey.util.WebUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * JWT 认证过滤器 —— 全站接口的门（@Order(1)，在 RequestSizeFilter 之后）。
 *
 * ★ 校验通过后把身份写进 X-User-Id / X-User-Role / X-Username；客户端伪造的同名头会被覆盖。
 * 放行顺序：OPTIONS → 非 /api/ → 白名单；白名单也要尽力解析并覆盖身份头（草稿读权限靠它）。
 * 非白名单不只要验签：账号须仍有效、未被禁用、票不早于 token_valid_after（JWT 无法吊销）。
 */
@Component
@Order(1)
public class JwtAuthFilter implements Filter {

    @Autowired
    private JwtUtil jwtUtil;

    /** 每请求确认账号仍有效；走 Service 而非 Mapper，便于以后给查用户加缓存。 */
    @Autowired
    private UserService userService;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // 规则 1：CORS 预检放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();

        // 规则 2：只拦截 /api/；静态资源、SPA 路由与首页一律放行
        if (!uri.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // 规则 3：白名单放行。
        // 白名单 ≠ 没有身份：/api/question/list/ 要靠身份头区分“已发布可读 / 草稿仅作者”，
        // 跳过注入会让作者读不了自己的草稿；同时必须覆盖伪造的身份头（否则可冒充 admin）。
        if (isWhiteList(uri)) {
            chain.doFilter(withIdentity(request, resolveClaims(request)), response);
            return;
        }

        // 非白名单路径：必须带合法 token，否则 401
        Claims claims = resolveClaims(request);
        if (claims == null) {
            String authHeader = request.getHeader("Authorization");
            boolean hasToken = authHeader != null && authHeader.startsWith("Bearer ");
            writeJson(response, 401, hasToken ? "token 无效或已过期" : "未登录或 token 缺失");
            return;
        }

        // 验签通过还不够：JWT 签发后无法吊销（无黑名单，有效期 12 小时），
        // 所以每请求查一次账号 —— 注销 / 被删 / 被禁用的旧票必须立刻失效（多一次主键查询）。
        // 第二道看票是否早于 token_valid_after：改密码或恢复账号后旧票作废。
        Integer userId = claims.get("userId", Integer.class);
        User currentUser = userId == null ? null : userService.getById(userId);
        if (currentUser == null) {
            // 文案与“token 无效”区分：这是账号本身不可用（被删 / 注销），前端同样按 401 处理
            writeJson(response, 401, "账号不存在或已注销，请重新登录");
            return;
        }
        // 被禁用也立即失效（与“注销”分开文案，用户才知道找谁）；
        // UserService.setDisabled 同时写 token_valid_after，双保险。
        if (currentUser.getDisabled() != null && currentUser.getDisabled() == 1) {
            writeJson(response, 401, "账号已被管理员禁用，请联系管理员解禁");
            return;
        }
        if (!JwtUtil.isIssuedAtOrAfter(claims, currentUser.getTokenValidAfter())) {
            // 改过密码或账号刚被恢复：两种情况都要求重新登录
            writeJson(response, 401, "登录状态已失效，请重新登录");
            return;
        }

        // 覆盖请求头：客户端伪造的 X-User-Id 会被这里的值覆盖（见 HeaderRewriteRequestWrapper）
        chain.doFilter(withIdentity(request, claims), response);
    }

    /**
     * 尽力解析 Authorization 头里的 JWT，只返回身份、不判 401（白名单与非白名单共用）。
     *
     * @return 无 token 头、非法或已过期都返回 null
     */
    private Claims resolveClaims(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return jwtUtil.parseToken(authHeader.substring(7));
    }

    /**
     * 用解析出的身份覆盖请求头。claims 为 null（匿名）也要写成空串，
     * 否则客户端伪造的 X-User-Id / X-User-Role 会残留。
     */
    private HeaderRewriteRequestWrapper withIdentity(HttpServletRequest request, Claims claims) {
        Integer userId = claims == null ? null : claims.get("userId", Integer.class);
        String role = claims == null ? null : claims.get("role", String.class);
        String username = claims == null ? null : claims.get("username", String.class);

        HeaderRewriteRequestWrapper wrapper = new HeaderRewriteRequestWrapper(request);
        wrapper.setHeader("X-User-Id", userId != null ? String.valueOf(userId) : "");
        wrapper.setHeader("X-User-Role", role != null ? role : "");
        wrapper.setHeader("X-Username", username != null ? username : "");
        return wrapper;
    }

    /** 输出统一的 JSON 错误响应（结构与其他接口的失败响应保持一致：success/code/message）。 */
    private void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        // 委托 WebUtils 用 Jackson 序列化，避免手拼 JSON 产生非法字符
        WebUtils.writeJsonError(response, code, message);
    }

    /**
     * 无需登录即可访问的 API。★ 没有子路径的端点必须用 equals：
     * startsWith("/api/user/login") 会连 /api/user/login-logs 一起放行，401 变 400。
     * 只有确实要放行整棵子树时才用 startsWith（注意末尾的 “/”）。
     * /api/captcha/ 与 /api/user/refresh 必须公开：前者注册时无 token，后者调用时 access token 已过期。
     */
    private boolean isWhiteList(String uri) {
        return uri.equals("/api/user/login")
                || uri.equals("/api/user/register")
                || uri.equals("/api/user/refresh")
                || uri.startsWith("/api/captcha/")
                || uri.startsWith("/api/survey/share/")
                || uri.startsWith("/api/survey/result/")
                || uri.startsWith("/api/survey/view/")
                || uri.startsWith("/api/question/list/")
                || uri.startsWith("/api/answer/submit-share/")
                || uri.startsWith("/api/answer/participants/")
                || uri.startsWith("/api/announcement/active")
                || uri.startsWith("/api/health");
    }
}
