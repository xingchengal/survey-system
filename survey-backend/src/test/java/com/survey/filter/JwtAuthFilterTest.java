package com.survey.filter;

import com.survey.entity.User;
import com.survey.service.UserService;
import com.survey.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JwtAuthFilter 的纯单元测试（Mockito + MockHttpServletRequest/Response，**不启动 Spring 容器**）。
 *
 * ── 这个类钉住的回归点 ──────────────────────────────
 * 白名单路径（如 /api/question/list/）若直接 chain.doFilter(原始 request)，
 * 既没有注入身份，**也没有覆盖客户端自己传的身份头**。后果有两面：
 *   1. 功能面：作者/管理员读自己草稿时 X-User-Role 永远是 null → 被判"无权查看"；
 *   2. 安全面：攻击者伪造 `X-User-Role: admin` + `X-User-Id: 1` 就能读别人的草稿。
 * 所以白名单也必须走 withIdentity()：能解析出 token 就注入真实身份，解析不出就写空串覆盖。
 * 下面每个断言都对应这两面中的一条。
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    /** 白名单路径（precisely：/api/question/list/ 是匿名答题页加载题目用的，必须公开可用）。 */
    private static final String WHITE_LIST_URI = "/api/question/list/1";

    /** 非白名单路径：必须凭合法 token 才能过。 */
    private static final String PROTECTED_URI = "/api/user/profile";

    @Mock
    private JwtUtil jwtUtil;

    /**
     * ★ 非白名单路径有一道"账号是否仍然有效"的检查，过滤器因此多了一个依赖。
     * 这里给它 stub 成"用户存在"，让下面那些**与账号状态无关**的用例
     * （验签、伪造头覆盖、文案区分）保持原意。
     * ★ "账号已注销 → 401"与"改密码后旧票失效 → 401"这两组用例**就在本类**，
     *   见下面的第 5、6 组：前者判账号是否仍然有效，后者判签发时间是否早于 token_valid_after。
     */
    @Mock
    private UserService userService;

    @InjectMocks
    private JwtAuthFilter jwtAuthFilter;

    /**
     * 让"Token 合法"的用例默认通过账号有效性检查。
     * ★ 不写它的话 `userService.getById` 返回 null → 401。
     * ★ 这里显式把 `tokenValidAfter` 置为 null：表示"该账号从未改过密码/被恢复过"，
     *   也就是过滤器里"不限制签发时间"那一支（绝大多数真实账号的常态）。
     */
    private void givenAccountExists() {
        User existing = new User();
        existing.setId(1);
        existing.setUsername("alice");
        existing.setRole("admin");
        existing.setTokenValidAfter(null);
        lenient().when(userService.getById(anyInt())).thenReturn(existing);
    }

    /**
     * 构造带指定 claims 的 Claims mock（只 stub 过滤器真正会读的字段）。
     *
     * ★ 这里用 lenient() 是**有意**的：Mockito 的严格模式会判定
     *   "声明了却没被读到"的 stub 为失败，而本类的用例**验的就是"某一步提前返回"** ——
     *   例如"缺 userId 的 token 直接 401"，此时 `role` / `username` 那两条 stub 自然没被读到，
     *   严格模式下会报 UnnecessaryStubbing，把一条**正确**的断言变成红。
     *   ⇒ 这里放宽的是"多余的 stub"，**不是**断言：断言全部照旧严格。
     */
    private Claims claimsWith(Integer userId, String role, String username) {
        Claims claims = mock(Claims.class);
        lenient().when(claims.get("userId", Integer.class)).thenReturn(userId);
        lenient().when(claims.get("role", String.class)).thenReturn(role);
        lenient().when(claims.get("username", String.class)).thenReturn(username);
        // ★ 过滤器还要读 `iat`（与 token_valid_after 比较）——
        //   这里默认给"1 小时前签发"，代表一枚**正常的新鲜票**。
        //   （不给默认值的话 getIssuedAt() 返回 null，过滤器会按"畸形票"直接 401，
        //    于是一堆与签发时间无关的用例会莫名其妙地红。）
        java.util.Date iat = java.util.Date.from(
                java.time.LocalDateTime.now().minusHours(1)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant());
        lenient().when(claims.getIssuedAt()).thenReturn(iat);
        return claims;
    }

    /**
     * 带签发时间的 claims —— 用于验证 `token_valid_after` 那道判定。
     * ★ `issuedAt` 传 null 时 stub 成 null，用于构造"畸形票（没有 iat）"。
     */
    private Claims claimsWith(Integer userId, String role, String username, java.time.LocalDateTime issuedAt) {
        Claims claims = claimsWith(userId, role, username);
        java.util.Date iat = issuedAt == null ? null
                : java.util.Date.from(issuedAt.atZone(java.time.ZoneId.systemDefault()).toInstant());
        lenient().when(claims.getIssuedAt()).thenReturn(iat);
        return claims;
    }

    // ==================== 1. 白名单 + 合法 token：必须注入身份 ====================

    @Test
    @DisplayName("白名单路径带合法 token：下游必须能拿到 X-User-Id/X-User-Role（作者读自己草稿的前提）")
    void whiteList_withValidToken_shouldInjectIdentity() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", WHITE_LIST_URI);
        request.addHeader("Authorization", "Bearer x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // 注意：先构造好 claims 再做外层 stub —— 不能写成 thenReturn(claimsWith(...))，
        // 那会在未完成的外层 stubbing 里嵌套新的 when()，Mockito 报 UnfinishedStubbing。
        Claims claims = claimsWith(1, "admin", "alice");
        when(jwtUtil.parseToken("x")).thenReturn(claims);
        givenAccountExists();

        jwtAuthFilter.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captor.capture(), any(ServletResponse.class));
        HttpServletRequest forwarded = (HttpServletRequest) captor.getValue();

        // 防回归（功能面核心）：白名单若"完全不注入身份"，作者/管理员读自己未发布的草稿题目
        // 会被判成"未发布，无权查看"。这两条断言就是钉住"白名单也注入身份"。
        assertEquals("1", forwarded.getHeader("X-User-Id"), "白名单也必须注入 X-User-Id");
        assertEquals("admin", forwarded.getHeader("X-User-Role"), "白名单也必须注入 X-User-Role");
        // 防回归：X-Username 供操作日志/审计展示"谁干的"，同样不能丢
        assertEquals("alice", forwarded.getHeader("X-Username"), "白名单也必须注入 X-Username");

        // 防回归：白名单不能被 401 拦住，否则匿名答题页直接打不开
        assertEquals(200, response.getStatus(), "白名单 + 合法 token 必须是放行，不能被写成 401");
    }

    // ==================== 2. 白名单 + 无 token + 伪造身份头：必须被覆盖成空 ====================

    @Test
    @DisplayName("白名单路径无 token 但客户端伪造 X-User-Id/X-User-Role：下游收到的必须是空串（伪造被覆盖）")
    void whiteList_withForgedIdentityHeaders_shouldBeOverwrittenWithEmpty() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", WHITE_LIST_URI);
        // 攻击者自己伪造的身份头（没有 Authorization，等于匿名请求）
        request.addHeader("X-User-Id", "1");
        request.addHeader("X-User-Role", "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // 前置条件自检：伪造头确实带在原始请求里 —— 否则下面的断言等于什么都没证明
        assertEquals("1", request.getHeader("X-User-Id"), "前置条件：原始请求里确实带着伪造的 X-User-Id");
        assertEquals("admin", request.getHeader("X-User-Role"), "前置条件：原始请求里确实带着伪造的 X-User-Role");

        jwtAuthFilter.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captor.capture(), any(ServletResponse.class));
        HttpServletRequest forwarded = (HttpServletRequest) captor.getValue();

        // 防回归（安全面核心）：白名单路径必须覆盖客户端的身份头。
        // 断言"空串"而不是"null"是刻意的：HeaderRewriteRequestWrapper 对匿名注入的是 ""，
        // Spring 侧 @RequestHeader(required = false) 会把 "" 转成 null，语义即"无身份"。
        // 如果哪天有人把覆盖逻辑删掉（直接 chain.doFilter(request, ...)），这里会读到 "1"/"admin" 而红。
        assertEquals("", forwarded.getHeader("X-User-Id"), "匿名白名单请求：伪造的 X-User-Id 必须被覆盖成空串");
        assertEquals("", forwarded.getHeader("X-User-Role"), "匿名白名单请求：伪造的 X-User-Role 必须被覆盖成空串");
        assertNotEquals("1", forwarded.getHeader("X-User-Id"), "绝不能把伪造的 userId 透传给下游");
        assertNotEquals("admin", forwarded.getHeader("X-User-Role"), "绝不能把伪造的 admin 角色透传给下游");
        // 防回归：匿名访问本身必须仍然可用（覆盖身份 ≠ 拒绝访问）
        assertEquals(200, response.getStatus(), "匿名白名单请求仍必须放行，不能被 401");

        // 防回归：没有 Authorization 就不该去解析 token
        verify(jwtUtil, never()).parseToken(anyString());
    }

    // ==================== 3. 非白名单 + 无 token → 401 且不放行 ====================

    @Test
    @DisplayName("非白名单路径无 token：401，且绝不能调用 chain.doFilter（否则等于没鉴权）")
    void protectedPath_withoutToken_shouldReturn401AndNotChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        // 顺带把伪造身份头也带上：即使伪造，也必须 401 拦住
        request.addHeader("X-User-Id", "1");
        request.addHeader("X-User-Role", "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        jwtAuthFilter.doFilter(request, response, chain);

        // 防回归：非白名单无 token 必须 401。若这条挂了，等于所有接口变成"裸奔"。
        assertEquals(401, response.getStatus(), "无 token 访问受保护接口必须 401");
        // 防回归：401 之后绝不能继续往下走 —— 一旦调用 chain.doFilter，Controller 就会执行，
        // 而它眼里 X-User-Id 是可信的，等于绕过全部鉴权。
        verifyNoInteractions(chain);
        // 防回归：401 的响应体结构要与全局失败响应一致（前端只认 success/code/message）
        String body = response.getContentAsString();
        assertTrue(body.contains("\"success\":false"), "401 响应体必须含 success:false，实际=" + body);
        assertTrue(body.contains("401"), "401 响应体必须含 code 401，实际=" + body);
        // 防回归：无 token 与 token 无效必须给不同文案，便于前端区分"去登录"还是"重新登录"
        assertTrue(body.contains("未登录"), "无 token 时文案应是'未登录或 token 缺失'，实际=" + body);
    }

    // ==================== 4. 非白名单 + token 非法 → 401 ====================

    @Test
    @DisplayName("非白名单路径 token 非法（parseToken 返回 null）：401，且不能调用 chain.doFilter")
    void protectedPath_withInvalidToken_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // 过期 / 签名不符 / 格式错误，JwtUtil 一律返回 null
        when(jwtUtil.parseToken("bad-token")).thenReturn(null);

        jwtAuthFilter.doFilter(request, response, chain);

        // 防回归：带着"看起来像 token"的垃圾串也必须 401。
        // 这里是最容易被写错的地方 —— 只要有 Authorization 头就放行，鉴权就完全失效了。
        assertEquals(401, response.getStatus(), "非法 token 必须 401");
        verifyNoInteractions(chain);
        String body = response.getContentAsString();
        assertTrue(body.contains("token 无效或已过期"),
                "带 token 但无效时文案应是'token 无效或已过期'，实际=" + body);
    }

    // ==================== 白名单 + token 非法 → 仍然放行（匿名可用性） ====================

    @Test
    @DisplayName("白名单路径 token 非法：仍按匿名放行（200），身份头为空串而不是 401")
    void whiteList_withInvalidToken_shouldStillPassThroughAsAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", WHITE_LIST_URI);
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jwtUtil.parseToken("expired-token")).thenReturn(null);

        jwtAuthFilter.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captor.capture(), any(ServletResponse.class));
        HttpServletRequest forwarded = (HttpServletRequest) captor.getValue();

        // 防回归：白名单是"尽力解析身份"，token 过期不能把匿名用户挡在门外
        // （否则用户 token 一过期，连匿名答题页/登录页公告都打不开）。
        assertEquals(200, response.getStatus(), "白名单 + 过期 token 必须仍放行");
        assertEquals("", forwarded.getHeader("X-User-Id"), "解析失败时 X-User-Id 必须是空串");
        assertEquals("", forwarded.getHeader("X-User-Role"), "解析失败时 X-User-Role 必须是空串");
    }

    // ==================== 5. ★ token 合法但账号已失效 → 401 ====================
    //
    //  这一组是"账号注销（软删除）"能不能真正生效的关键：JWT 签发后无法吊销、有效期 12 小时，
    //  如果只在登录时判 deleted_at，注销后本人手里那枚 token 在 12 小时内仍是完整通行证。
    //  ⇒ 过滤器每个受保护请求查一次"账号还在不在"（UserService.getById → findById 已过滤 deleted_at）。

    @Test
    @DisplayName("★ 非白名单 + token 合法 + 账号已注销/被删：401，且绝不放行（软删除生效的关键）")
    void protectedPath_withValidTokenButDeletedAccount_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.addHeader("Authorization", "Bearer good-token");
        // 客户端可能还带着伪造身份头，一并验证它不会被透传
        request.addHeader("X-User-Role", "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        Claims claims = claimsWith(42, "admin", "ghost");
        when(jwtUtil.parseToken("good-token")).thenReturn(claims);
        // 关键 stub：这个 userId 在库里**查不到**（已被软删除 → findById 过滤掉了）
        when(userService.getById(42)).thenReturn(null);

        jwtAuthFilter.doFilter(request, response, chain);

        // 防回归（软删除核心）：签名有效、未过期的 token 也必须被拒 —— 否则"注销"只是前端的假动作
        assertEquals(401, response.getStatus(), "账号已注销时，即便 token 合法也必须 401");
        verifyNoInteractions(chain);   // 绝不能进 Controller
        String body = response.getContentAsString();
        assertTrue(body.contains("账号不存在或已注销"),
                "文案要能让用户知道是账号不可用了（而不是笼统的 token 问题），实际=" + body);
    }

    @Test
    @DisplayName("非白名单 + token 合法 + 账号正常：放行（证明上面那条不是把所有请求都拒了）")
    void protectedPath_withValidTokenAndLivingAccount_shouldPass() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.addHeader("Authorization", "Bearer good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        Claims claims = claimsWith(1, "user", "alice");
        when(jwtUtil.parseToken("good-token")).thenReturn(claims);
        givenAccountExists();

        jwtAuthFilter.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captor.capture(), any(ServletResponse.class));
        HttpServletRequest forwarded = (HttpServletRequest) captor.getValue();
        assertEquals("1", forwarded.getHeader("X-User-Id"), "账号正常时必须照常注入身份");
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("token 里没有 userId（畸形/旧格式）：一律 401，不得放行")
    void protectedPath_withTokenMissingUserId_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.addHeader("Authorization", "Bearer weird-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // 签名对但缺 claim —— 例如密钥泄露期被人手工签发、或将来改了 claim 名
        Claims claims = claimsWith(null, "admin", "alice");
        when(jwtUtil.parseToken("weird-token")).thenReturn(claims);

        jwtAuthFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus(), "缺 userId 的 token 不能放行");
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("★ 白名单路径**刻意不查**账号是否存在（匿名答题/登录页/health 必须照常可用）")
    void whiteList_shouldNotQueryAccountExistence() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", WHITE_LIST_URI);
        request.addHeader("Authorization", "Bearer whatever");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // 白名单：即便库里查不到这个用户，也必须放行 ——
        // 否则已注销用户的浏览器会连"匿名答题页 / 登录页公告"都打不开。
        // ★ 注意先把 claims 取出来再 stub：不能写成 when(...).thenReturn(claimsWith(...))
        //   —— 那样会在未完成的外层 stubbing 里嵌套新的 when()，Mockito 报 UnfinishedStubbing
        //   （本类第 1 个用例的注释里也记着同一条坑）。
        Claims claims = claimsWith(999, "user", "ghost");
        when(jwtUtil.parseToken("whatever")).thenReturn(claims);

        jwtAuthFilter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus(), "白名单不能被账号有效性检查拦住");
        verify(userService, never()).getById(anyInt());
    }

    // ============ 6. ★ token_valid_after —— "旧票"必须被拒 ============
    //
    //  背景：JWT 无法吊销。用户**改密码**、管理员**恢复已注销账号**时，
    //  Service 会把 t_user.token_valid_after 推到那一刻；此后"签发时间早于它"的票一律无效。
    //  这一组把过滤器里的这第二道判定钉住（第一道是"账号还在不在"）。

    @Test
    @DisplayName("★ token 合法、账号也在，但签发时间早于 tokenValidAfter（改密码/恢复账号后的旧票）→ 401")
    void protectedPath_withTokenIssuedBeforeValidAfter_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.addHeader("Authorization", "Bearer old-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // 这枚票是 1 小时前签发的
        Claims claims = claimsWith(1, "user", "alice", java.time.LocalDateTime.now().minusHours(1));
        when(jwtUtil.parseToken("old-token")).thenReturn(claims);
        // 而账号在 10 分钟前"重置了令牌生效起点"（= 刚刚改过密码）
        User u = new User();
        u.setId(1);
        u.setTokenValidAfter(java.time.LocalDateTime.now().minusMinutes(10));
        lenient().when(userService.getById(1)).thenReturn(u);

        jwtAuthFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus(), "改密码前签发的票必须被拒（否则'改密码踢会话'是假的）");
        verifyNoInteractions(chain);
        String body = response.getContentAsString();
        assertTrue(body.contains("登录状态已失效"),
                "文案要与'账号已注销'区分开（用户能理解成'我改过密码'），实际=" + body);
    }

    @Test
    @DisplayName("★ 改密码后**重新登录**拿到的票（签发时间晚于 tokenValidAfter）→ 放行")
    void protectedPath_withTokenIssuedAfterValidAfter_shouldPass() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.addHeader("Authorization", "Bearer new-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // 票是 10 分钟前签发的，而生效起点是 11 分钟前 ⇒ 这枚票**晚于**生效起点，仍然有效
        // （等价于"用户 11 分钟前改了密码，10 分钟前重新登录拿到的票"）
        Claims claims = claimsWith(1, "user", "alice", java.time.LocalDateTime.now().minusMinutes(10));
        when(jwtUtil.parseToken("new-token")).thenReturn(claims);
        // tokenValidAfter = 11 分钟前
        User u = new User();
        u.setId(1);
        u.setTokenValidAfter(java.time.LocalDateTime.now().minusMinutes(11));
        lenient().when(userService.getById(1)).thenReturn(u);

        jwtAuthFilter.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captor.capture(), any(ServletResponse.class));
        assertEquals(200, response.getStatus(), "重新登录后的票必须能正常用（否则用户被永久锁在外面）");
    }

    @Test
    @DisplayName("★ 边界：签发时间**恰好等于** tokenValidAfter（同一秒）→ 401（宁可让用户再登一次）")
    void protectedPath_withTokenIssuedExactlyAtValidAfter_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.addHeader("Authorization", "Bearer boundary-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        java.time.LocalDateTime same = java.time.LocalDateTime.now().withNano(0);
        Claims claims = claimsWith(1, "user", "alice", same);
        when(jwtUtil.parseToken("boundary-token")).thenReturn(claims);
        User u = new User();
        u.setId(1);
        u.setTokenValidAfter(same);   // 同一个时刻（同秒）
        lenient().when(userService.getById(1)).thenReturn(u);

        jwtAuthFilter.doFilter(request, response, chain);

        // ★★ 这条钉住的是**边界方向**，而它背后是一次真实的选择：
        //   判定用严格 `iat > validAfter`（不是 >=）。因为 `iat` 只有秒精度，
        //   若同秒放行，就会出现"① 登录 → ② 同一秒内改密码 → ③ ①的旧票仍返回 200"。
        //   代价是"改完密码同一秒内重新登录"会被拒一次（无感，再点一次即可）。
        //   ⇒ 安全目标（一个旧票都不放过）优先于这一秒的便利，这个取舍必须钉在测试里。
        assertEquals(401, response.getStatus(), "同秒的票一律拒（旧票不能因为同秒就活下来）");
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("★ 过了那一秒后签发的票 → 放行（证明上面那条只是'同秒'的代价，不是永久锁死）")
    void protectedPath_withTokenIssuedOneSecondAfterValidAfter_shouldPass() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.addHeader("Authorization", "Bearer next-second-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().withNano(0).minusSeconds(1);
        // iat 比 cutoff 晚一秒（等价于"用户重新登录了一次"）
        Claims claims = claimsWith(1, "user", "alice", cutoff.plusSeconds(1));
        when(jwtUtil.parseToken("next-second-token")).thenReturn(claims);
        User u = new User();
        u.setId(1);
        u.setTokenValidAfter(cutoff);
        lenient().when(userService.getById(1)).thenReturn(u);

        jwtAuthFilter.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(captor.capture(), any(ServletResponse.class));
        assertEquals(200, response.getStatus(), "晚于生效起点的票必须放行（否则用户被永久锁死）");
    }

    @Test
    @DisplayName("token 没有 iat（畸形票）→ 401（无法判定新旧，宁可让人重新登录）")
    void protectedPath_withTokenMissingIssuedAt_shouldReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.addHeader("Authorization", "Bearer no-iat-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        Claims claims = claimsWith(1, "user", "alice", null);   // getIssuedAt() 返回 null
        when(jwtUtil.parseToken("no-iat-token")).thenReturn(claims);
        // ★ 这里刻意用"改过密码的账号"（tokenValidAfter = 1 小时前）：
        //   因为对这个账号来说，**任何**没有 iat 的票都必须被拒。
        //   （另一条分支 —— 账号从未改过密码时缺 iat 的票 —— 见下一个用例，
        //     它钉住的是"先判 iat 存在、再看 validAfter"这个顺序，别把两者合并。）
        User u = new User();
        u.setId(1);
        u.setTokenValidAfter(java.time.LocalDateTime.now().minusHours(1));
        lenient().when(userService.getById(1)).thenReturn(u);

        jwtAuthFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus(), "缺 iat 的票不能放行");
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("★ 账号未改过密码（tokenValidAfter=null）时，缺 iat 的票同样要拒 —— 不能因为'不限制'就跳过检查")
    void protectedPath_withTokenMissingIssuedAtAndNoCutoff_shouldStillReturn401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PROTECTED_URI);
        request.addHeader("Authorization", "Bearer no-iat-token-2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        Claims claims = claimsWith(1, "user", "alice", null);
        when(jwtUtil.parseToken("no-iat-token-2")).thenReturn(claims);
        givenAccountExists();   // 这个账号的 tokenValidAfter = null（= 从未改过密码）

        jwtAuthFilter.doFilter(request, response, chain);

        // ★ 这条钉住的是**判定顺序**：不能写成 "validAfter == null → 直接返回 true"，
        //   那会让"少一个 claim"反而更容易通过 —— 逻辑方向就反了。
        //   正常签发的票一定有 iat，所以不是漏洞，但必须挡住这种"缺字段更宽松"的写法。
        assertEquals(401, response.getStatus(), "缺 iat 的票在任何账号上都不能放行");
        verifyNoInteractions(chain);
    }
}
