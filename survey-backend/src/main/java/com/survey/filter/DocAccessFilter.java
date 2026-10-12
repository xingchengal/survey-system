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
 * 接口文档访问控制过滤器 —— 把 /doc.html 等文档路径限制给管理员。
 *
 * ── 为什么需要单独一个过滤器（而不是塞进 JwtAuthFilter）──
 *   文档是**浏览器直接打开**的页面，没法像接口那样带 Authorization 头。
 *   所以只能换一种凭证载体：Cookie。
 *   于是设计成：管理员调登录接口时，后端顺带下发一个 doc_token Cookie（HttpOnly），
 *   以后他打开 /doc.html，浏览器自动带上这个 Cookie，本过滤器校验通过后放行。
 *
 * ── 与 JwtAuthFilter 的分工 ────────────────────────────
 *   @Order(0) RequestSizeFilter：请求体大小上限，最先执行
 *   @Order(1) JwtAuthFilter    ：管 /api/ 接口，凭证是 Authorization 头
 *   @Order(2) DocAccessFilter  ：管文档路径，凭证是 doc_token Cookie（本类）
 *   ★ 文档路径（/doc.html、/v3/api-docs 等）不在 JwtAuthFilter 的白名单里，由本类专门把关。
 *
 * ── 六道校验 ──────────────────────────────────────────
 *   1. Cookie 存在且非空；2. Cookie 里的 JWT 能解析通过（未过期、签名对）；
 *   3. JWT 里 **scope == "doc"** —— 确认这确实是一张"文档通行证"，而不是把登录 token 塞进这个 Cookie；
 *   4. JWT 里的 role == "admin"；
 *   5. **该账号仍然有效**（未注销 / 未被删）—— 否则一个刚注销的管理员还能看 2 小时文档；
 *   6. **该票不是"令牌生效起点"之前签发的**（改密码 / 恢复账号后，旧文档票同样作废）。
 *   任一不满足都返回 403（不是 401 —— 因为这是"没权限看"，不是"没登录"）。
 *
 * ── 配套的 Cookie 设置在哪 ────────────────────────────
 *   controller/UserController.login()：仅当 role=admin 时下发。
 *   ★ 值为**单独签发**的文档通行证（JwtUtil.generateDocToken：带 scope=doc、有效期 2 小时），
 *     **不是**返回给前端的登录 token（那枚是 12 小时）—— 否则 Cookie 一泄露就等于完整登录凭证。
 *   普通用户登录 / 任何人登出时下发空值 + Max-Age=0 清掉它。
 *   该 Cookie 带 HttpOnly 与 SameSite=Strict（防 XSS 读取 + 防 CSRF 跨站携带）。
 */
@Component
@Order(2)   // 在 JwtAuthFilter 之后执行
public class DocAccessFilter implements Filter {

    @Autowired
    private JwtUtil jwtUtil;

    /** ★ 确认"这枚文档票对应的账号仍然有效"（见校验 5）。 */
    @Autowired
    private UserService userService;

    private static final String DOC_COOKIE_NAME = "doc_token";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String uri = request.getRequestURI();

        // 不属于文档路径 → 直接放行（本过滤器只管文档，别的一律不碰）
        if (!isDocPath(uri)) {
            chain.doFilter(request, response);
            return;
        }

        // OPTIONS 预检放行（预检不带 Cookie）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        // 校验 1：从 Cookie 里读 token
        String token = getCookie(request, DOC_COOKIE_NAME);
        if (token == null || token.isEmpty()) {
            writeJson(response, 403, "禁止访问：请先以管理员身份登录");
            return;
        }

        // 校验 2：解析 JWT（过期/签名错都返回 null）
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            writeJson(response, 403, "禁止访问：登录已过期，请重新登录");
            return;
        }

        // 校验 3：必须是**文档通行证**（scope=doc）—— 见 JwtUtil.generateDocToken 的说明。
        //   文档票与登录票是**两枚语义完全不同的 token**，所以必须确认
        //   "Cookie 里装的确实是一张文档票"：否则有人把登录 token 塞进 doc_token Cookie
        //   就能冒充（虽然也能通过 role 校验，但那样两枚 token 的语义边界就形同虚设了）。
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

        // 校验 5：账号必须仍然有效（未注销 / 未被删）。
        //   文档票的有效期是 2 小时，同样是"签发即无法吊销"的 JWT ——
        //   如果没有这一道，一个刚注销（或被管理员删掉）的管理员，
        //   在最长 2 小时内仍能打开接口文档（虽然看不到业务数据，但没理由留着）。
        //   与 JwtAuthFilter 里的那道是同一个理由，口径也一致：查 findById（已过滤 deleted_at）。
        Integer userId = claims.get("userId", Integer.class);
        User currentUser = userId == null ? null : userService.getById(userId);
        if (currentUser == null) {
            writeJson(response, 403, "禁止访问：账号不存在或已注销，请重新登录");
            return;
        }

        // 校验 6：这枚文档票是不是在"令牌生效起点"之前签发的。
        //   管理员改密码、或被恢复注销后，此前签发的文档票也应一并作废 ——
        //   与 JwtAuthFilter 第 5 道**同一份判定**（JwtUtil.isIssuedAtOrAfter，口径只写一处）。
        if (!JwtUtil.isIssuedAtOrAfter(claims, currentUser.getTokenValidAfter())) {
            writeJson(response, 403, "禁止访问：登录状态已失效，请重新登录后再访问");
            return;
        }

        // 通过，放行
        chain.doFilter(request, response);
    }

    /**
     * 是否文档相关路径。
     * ★ 这里【不要】包含 /favicon.ico：favicon 是站点图标、不是文档资源，不需要 doc_token ——
     *   否则非管理员连网站图标都拿不到（会被拦成 403）。
     */
    private boolean isDocPath(String uri) {
        return uri.startsWith("/doc.html")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui")
                || uri.startsWith("/webjars")
                || uri.startsWith("/knife4j");
    }

    /** 按名字从请求里读 Cookie（找不到返回 null）。 */
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
     * 返回统一的 JSON 错误响应（结构与其它失败响应一致）。
     * ★ 实现委托给 WebUtils，用 Jackson 序列化 —— 手工拼接时 message 含引号/反斜杠
     *   会产出非法 JSON。
     */
    private void writeJson(HttpServletResponse response, int code, String message) throws IOException {
        WebUtils.writeJsonError(response, code, message);
    }
}
