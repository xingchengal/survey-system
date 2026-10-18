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
 * JWT 认证过滤器 —— 全站接口的「门」（@Order(1)，排在 @Order(0) 的 RequestSizeFilter 之后）。
 *
 * ── 它做什么 ──────────────────────────────────────────
 *   拦住所有 /api/ 开头的请求，检查 Authorization: Bearer &lt;token&gt;；
 *   校验通过后把身份**写进请求头** X-User-Id / X-User-Role / X-Username，
 *   后面的 Controller 就能用 @RequestHeader("X-User-Id") 直接拿到当前用户。
 *
 * ── ★ 三条放行规则（顺序很重要）────────────────────────
 *   1. OPTIONS 直接放行 —— 浏览器 CORS 预检请求不带 Authorization；
 *   2. 非 /api/ 开头直接放行 —— 静态资源、SPA 路由、index.html 不受管；
 *   3. 白名单放行（见 isWhiteList 的注释）。
 *
 * ── 白名单里为什么是这几个（每个都有业务理由，别随手加）──
 *   /api/user/login、/api/user/register   ：登录注册本来就没有 token
 *   /api/user/refresh                     ：调用它时 access token 已过期（见 isWhiteList）
 *   /api/captcha/                         ：注册页的滑块验证码（此时必然没有 token）
 *   /api/survey/share/、/api/survey/view/ ：匿名访客凭分享码打开问卷、记访问量
 *   /api/survey/result/                   ：匿名查看"公开结果页"（问卷作者主动开启才可用，
 *                                           Controller 内部还会校验 public_result=1 并去掉填空原文）
 *   /api/question/list/                   ：匿名答题页必须能加载题目
 *                                           ★ Controller 内部还细分了"已发布可读 / 草稿仅作者"
 *   /api/answer/submit-share/             ：匿名提交答卷
 *   /api/announcement/active              ：登录页也要能显示公告
 *   /api/health                           ：启动脚本的就绪探针
 *
 * ── 与 DocAccessFilter 的分工 ──────────────────────────
 *   文档路径（/doc.html 等）**不在**这个白名单里，而是交给 @Order(2) 的 DocAccessFilter
 *   用 Cookie（doc_token）单独把关 —— 因为浏览器直接打开文档页时没法带 Authorization 头。
 */
@Component
@Order(1)
public class JwtAuthFilter implements Filter {

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * ★ 用于确认"这个 token 对应的账号仍然有效"（未注销 / 未被删）。
     * ★ 为什么注入 Service 而不是直接用 UserMapper：本类是全站的第一道门，让它跟业务层
     *   一样走 Service，将来给"查用户"加缓存时只有一处要改。
     */
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

        // 规则 2：只拦截 /api/ 开头的接口
        // 其他（静态资源、SPA 路由、首页）一律放行
        if (!uri.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // 规则 3：白名单放行（无需登录就能访问的接口）
        //
        // ★ 白名单 ≠ "没有身份"（务必理解）：
        //   /api/question/list/ 在白名单里，但 QuestionController 要靠
        //   X-User-Id / X-User-Role 区分「已发布任何人可读」和「草稿仅作者/管理员可读」。
        //   若白名单直接 chain.doFilter(原始 request)、**跳过身份注入**，这两个头就永远是 null，
        //   作者本人和管理员读自己的草稿题目会被判成"未发布，无权查看"(400)，
        //   编辑页/AI 生成后的跳转页全都加载不出题目。
        //   正确做法：白名单也**尽力**解析 token 并注入身份（解析不出来就注入空值）——
        //   既保住匿名访问，又让"能带 token 的白名单请求"拥有正确身份。
        //
        //   ★ 同时这也是安全要求：白名单路径同样要**覆盖**客户端的身份头，
        //     否则攻击者可以直接伪造 X-User-Id: 1 / X-User-Role: admin 来读别人草稿。
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

        // ★★ **token 合法还不够，账号必须仍然有效**。
        //
        //   为什么必须有这一道（这是软删除能不能真正生效的关键）：
        //   JWT 一旦签发就**无法吊销**（项目没有黑名单），而它的有效期是 12 小时。
        //   如果只在登录时判 deleted_at，那么"注销"之后，本人手里那枚还没过期的 token
        //   在最长 12 小时内**仍然是一张完整通行证** —— 能建问卷、改资料、提交答卷。
        //   验收口径：注销后拿旧 token 调 /api/user/profile 必须是 401，而不是 200。
        //   ⇒ 代价是每个受保护请求多一次主键查询。用户表很小、单实例部署，这点开销可接受；
        //     真要省，正确的做法是加本地缓存（缓存"已注销的 userId 集合"），而不是去掉这道检查。
        //
        //   ★ 这一道同时让"被物理删除的用户"的 token 立刻失效 —— 管理员删用户与用户自己注销，
        //     从此都立即生效（不再有 12 小时的权限撤销延迟）。
        //   ★ 白名单路径**刻意不查**（匿名答题、登录页公告、健康检查本来就允许无身份；
        //     而且 /api/user/refresh 必须能在 access token 过期后调用）。
        //
        //   ★★ 第二道：**这枚票是不是在某个时间点之前签发的**。
        //     账号还在，不等于"这枚票还有效" —— 用户改密码、或管理员恢复他的注销，
        //     都会把 `token_valid_after` 推到那一刻，此后旧票一律作废
        //     （JWT 无法吊销，这是唯一手段；边界见 JwtUtil.parseToken(token, validAfter)）。
        //     两次判断共用**同一次查询**取回的对象，没有额外查询开销。
        Integer userId = claims.get("userId", Integer.class);
        User currentUser = userId == null ? null : userService.getById(userId);
        if (currentUser == null) {
            // 文案与"token 无效"区分开：这是**账号本身**不可用了（被删或被注销），
            // 前端拿到 401 会清本地登录态并回登录页，与其它 401 的处理一致。
            writeJson(response, 401, "账号不存在或已注销，请重新登录");
            return;
        }
        if (!JwtUtil.isIssuedAtOrAfter(claims, currentUser.getTokenValidAfter())) {
            // ★ 与"账号不可用"分开写文案：这句对应用户能理解的两件事 ——
            //   "你改过密码"或"管理员刚恢复了这个账号"，两种情况都要求重新登录。
            writeJson(response, 401, "登录状态已失效，请重新登录");
            return;
        }

        // ★ 关键一步：用包装器覆盖请求头。
        //   这样客户端就算自己伪造 X-User-Id 也没用 —— 会被这里的值覆盖掉
        //   （原理见 HeaderRewriteRequestWrapper 的 getHeader 实现）。
        //   所以"Controller 里读 X-User-Id 是可信的"这个前提，成立的条件就是经过了这个过滤器。
        chain.doFilter(withIdentity(request, claims), response);
    }

    /**
     * 尽力从 Authorization 头解析 JWT。
     *
     * ★ 与「鉴权」的区别：本方法**不判定 401**，只负责"能不能解析出身份"。
     *   供白名单路径（可选身份）与非白名单路径（由调用方判 401）共用，
     *   避免两处各写一遍解析逻辑。
     *
     * @return 解析成功返回 Claims；没有 token 头、token 非法或已过期都返回 null
     */
    private Claims resolveClaims(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return jwtUtil.parseToken(authHeader.substring(7));
    }

    /**
     * 用解析出的身份覆盖请求头，返回包装后的 request。
     *
     * ★ 即使 claims 为 null（匿名访客）也要走一遍覆盖：把 X-User-Id / X-User-Role 写成空串，
     *   这样客户端自己伪造的这两个头就彻底失效了（getHeader 优先返回我们写入的值）。
     *   Controller 侧用 @RequestHeader(required = false) 接收时，空串会被 Spring 转成 null，
     *   语义正好是"无身份"，与修复前的匿名行为一致。
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
        // ★ 实现委托给 WebUtils：用 Jackson 序列化 —— 手工拼接时 message 里出现
        //   引号/反斜杠就会产出非法 JSON。
        WebUtils.writeJsonError(response, code, message);
    }

    /**
     * 白名单（无需登录就能访问的 API）。★ 每加一条都要想清楚：这真的该公开吗？
     *
     * ★★ **前缀写法的坑：没有子路径的端点一律用 `equals`**：
     *   若对**没有子路径**的路径用 `startsWith`，同一前缀下的其它端点会被一起放行 ——
     *   例：`startsWith("/api/user/login")` 会把 `GET /api/user/login-logs`（"我的最近登录"）
     *   一并放行（`login-logs` 以 `login` 开头），于是不带 token 请求它返回
     *   **400「缺少必需的请求参数或请求头」**，而不是应有的 401。
     *   ★ 危害仅限"匿名请求能进到控制器"：`withIdentity()` 会用 token 覆盖（无 token 则置空）
     *     `X-User-Id`，所以**读不到别人的数据**；但它破坏了"这个端点需登录"的档位，
     *     而且错误码从 401 变 400，前端会走错分支。
     *   ⇒ 只有确实要放行整棵子树时才用 `startsWith`
     *     （如 `/api/captcha/`、`/api/survey/share/`，注意末尾那个 `/`）。
     *     ★ 现状例外：`/api/announcement/active`、`/api/health` 没有子路径却用了 `startsWith` ——
     *       它们的前缀当前不会命中其它端点，所以无实际影响。
     *
     * ★ 两条"为什么必须公开"：
     *   · {@code /api/captcha/} —— 注册页的滑块验证码。注册页此时**必然没有 token**，
     *     而"取一张验证码图"是只读、不含任何用户数据；防刷由限流（取图 20 次/分/IP、
     *     判定 120 次/分/IP，见 RateLimitInterceptor）+ 凭证一次性/60 秒过期/连续失败 3 次作废 承担。
     *   · {@code /api/user/refresh} —— 用 refresh token 换新的 access token。
     *     ★ 这条**必须**在白名单里：调用它的时刻 access token 已经过期（否则不会来刷新），
     *     若要求它带有效 access token，续期机制永远走不通。安全性由 refresh token
     *     本身承担（只存 SHA-256、7 天过期、一次性轮换、可吊销）。
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
                || uri.startsWith("/api/announcement/active")
                || uri.startsWith("/api/health");
    }
}
