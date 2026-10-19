package com.survey.controller;

import com.alibaba.excel.EasyExcel;
import com.survey.annotation.RequirePermission;
import com.survey.annotation.OperationLog;
import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.common.PageResult;
import com.survey.dto.AccountDeleteRequest;
import com.survey.dto.ContactRequest;
import com.survey.dto.LoginRequest;
import com.survey.dto.RefreshRequest;
import com.survey.dto.RegisterRequest;
import com.survey.entity.LoginLog;
import com.survey.entity.User;
import com.survey.mapper.RoleMapper;
import com.survey.service.CaptchaService;
import com.survey.service.LoginLogService;
import com.survey.service.RefreshTokenService;
import com.survey.service.UserService;
import com.survey.util.JwtUtil;
import com.survey.util.WebUtils;
import com.survey.common.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户管理：注册、登录/登出、个人资料维护、管理员的用户管理。
 *
 * ── 谁在调我（前端页面）────────────────────────────────
 *   Login.vue / Register.vue            → 登录、注册
 *   AdminLayout.vue（顶栏下拉）          → 登出
 *   UserProfile.vue / AdminProfile.vue  → 资料、昵称、密码、头像、联系方式
 *   UserManage.vue                      → 管理员看用户列表（在用 / 已注销两个 tab，含彻底删除与恢复）
 *
 * ── 权限矩阵（详见 docs/问卷系统-端点权限审计表.md，随仓库入库）──
 *   公开(白名单) : POST /register、POST /login、POST /refresh、
 *                  GET /api/captcha/slider（见 CaptchaController）
 *   需登录       : POST /logout、GET /profile、GET /login-logs、
 *                  PUT /nickname|/password|/avatar|/contact、
 *                  DELETE /account（★ 注销自己的账号，软删除）
 *   需管理员     : GET /page、GET /page-deleted（★ 已注销用户列表）、
 *                  DELETE /delete/{id}、PUT /restore/{id}（★ 恢复已注销账号）、
 *                  PUT /{id}/disabled（★ 禁用/解禁）、GET /export（★ 导出 Excel）
 *   ★ GET /list 与 GET /count **不存在**（原因见方法处注释）：用户列表走 /page，
 *     用户总数从 /page 的 total 或 /api/survey/system-overview 的 users 字段拿。
 *
 * ── 关键设计 ──────────────────────────────────────────
 *   1. 密码用 PBKDF2 加盐哈希存储（见 UserService），旧 MD5 账号登录时自动升级；
 *   2. 登录成功会给管理员额外下发 doc_token Cookie（HttpOnly + SameSite=Strict），
 *      由 filter/DocAccessFilter 校验，把「接口文档」限制给管理员；
 *   3. 实体的 password 字段标了 WRITE_ONLY，只收不发 —— 所以接口返回里永远不会带密码。
 */
@Tag(name = "用户管理", description = "注册、登录、用户列表（含管理员与禁用状态）、禁用/解禁、导出")
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    /** 注册前的滑块二次校验：register() 只认"已被标记 verified 的凭证"并消费它 */
    @Autowired
    private CaptchaService captchaService;

    /** ★ RBAC：登录时一并下发"角色码 + 权限点"，前端据此渲染菜单/按钮（只是体验层）。 */
    @Autowired
    private RoleMapper roleMapper;

    /** "记住我"的双 token：长效、可吊销的 refresh token（access token 仍短命，理由见 login()） */
    @Autowired
    private RefreshTokenService refreshTokenService;

    /**
     * 登录日志（t_login_log）。
     * ★ 为什么不给 login() 挂 @OperationLog 了事：那个切面是 @AfterReturning，
     *   只能记**成功**的那一次；而登录日志表是按"每次登录一行 + status 列"设计的
     *   （将来要写失败记录、做失败锁定）。两件事语义不同，各写各的入口。
     */
    @Autowired
    private LoginLogService loginLogService;

    private static final String DOC_COOKIE_NAME = "doc_token";

    /**
     * 文档通行证 Cookie 的有效期：2 小时。
     * ★ 必须与 JwtUtil.generateDocToken() 里签发的 token 有效期一致 ——
     *   两者各写各的（一个在 Controller、一个在 yaml），改一处忘了另一处就会出现
     *   "Cookie 还在但票据已过期"（或反过来）的错位。数字集中在这里，
     *   并在下方注释指回 JwtUtil。
     */
    private static final long DOC_COOKIE_MAX_AGE_SECONDS = 2 * 3600;

    @OperationLog("用户注册")
    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public String register(@Valid @RequestBody RegisterRequest req,
                           @RequestParam(value = "captchaToken", required = false) String captchaToken) {
        // ★★ 第 1 步：滑块验证**必须先过**，再看表单。
        //   顺序是有意的：验证码是"防机器"的第一道门，让它挡在字段校验之前，
        //   脚本连"哪些账号已存在"这类信息都拿不到（每个请求都要先解一道滑块题）。
        //
        //   ★ 注意配合关系的**两步走**（别只看这个方法，容易误解）：
        //     ① 前端拖动结束 → POST /api/captcha/verify → 后端判定并把凭证标记为 verified；
        //     ② 这里只检查"标记 + 未过期 + 未用过"，然后**消费**凭证。
        //   为什么不在这一层重新判定 left：判定要用"下发 offset 时的那一套区间与容差"
        //   （offset 的合法区间依赖前端组件的 l/r/w，见 CaptchaService.createChallenge），
        //   所以判定被放在 /api/captcha/verify 里一次做完，这里只认标记。
        //   ★ 这里**不再接收** captchaLeft —— 判定已前移到 POST /api/captcha/verify，
        //     这个接口只认"凭证已被标记为 verified"，然后消费它。
        captchaService.consumeVerifiedOrThrow(captchaToken);

        // ★ 第 2 步：
        //   入参用 DTO + @Valid —— 格式规则（账号 4~20 字母数字、密码 6~20、手机号格式、
        //   昵称长度）都声明在 dto/RegisterRequest 上，失败由 GlobalExceptionHandler
        //   统一翻成 400 + {success:false,message}；这里只做"DTO → 实体"的搬运，
        //   业务规则（查重、哈希、角色强制、手机号落库）仍在 UserService.register。
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(req.getPassword());
        user.setNickname(req.getNickname());
        // 手机号只采集存储，不参与登录/短信；为空时保持 null
        user.setPhone(req.getPhone());
        return userService.register(user, req.getPhone());
    }

    /**
     * 登录。
     *
     * ★ 这里**没有** @OperationLog（刻意不加，避免每次登录都往操作日志里写一条）：
     *   "登录"这件事记在**专门的登录日志表** t_login_log 里，
     *   供个人中心展示"最近登录时间 / IP"（需求 三.3）—— 两者语义不同，别混。
     */
    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        // ★ 同上改用 DTO + @Valid。口径：登录**不做最小长度校验**（"密码至少 6 位"属于注册规则，
        //   不该由登录接口说出来），但保留**上限**校验 —— LoginRequest 上的 @Size(max = 64)
        //   是**启用**的，传 65 位密码会得到 400「密码不能超过 64 位」（超长口令没必要
        //   送进 PBKDF2 比对流程）。
        User loginUser = userService.login(req.getUsername(), req.getPassword());
        // ★ 登录失败**抛 BizException**（400），不是「HTTP 200 + {success:false,message}」：
        //   失败返 200 时前端只能靠 data.success 判断，Login.vue 一旦漏掉 else 分支，
        //   用户输错密码点登录就**毫无反应**。现在前端只认状态码：
        //   2xx 即成功，非 2xx 读 message 弹红字。
        if (loginUser == null) {
            // ★ 这里只有一句、且**不区分**"账号不存在"与"密码错误" ——
            //   有账号的人输错密码，和没这个账号的人，拿到的响应**完全一致**
            //   （同状态码 400、同 message、同响应体结构），所以探测不出账号是否存在
            //   （需求："账号、密码错误统一提示文案，避免账号探测"）。
            //   ★ 文案用"账号"而不是"用户名"：username 在本系统里就是唯一登录标识
            //     （需求："账号为唯一标识""原有用户名统一合并为账号"），
            //     两个词混用会让用户在登录页对不上号。
            throw new BizException("账号或密码错误");
        }

        // ★★ 禁用判定必须在"密码已验过"之后：
        //   放在密码校验**之前**（或与登录失败共用一句文案）会多出一条账号探测信号
        //   —— 攻击者只要乱试密码，就能从"账号已被禁用"这句话得知该账号存在且被处置过。
        //   现在只有**知道该账号密码**的人才会看到这句提示，不构成信息泄露。
        // ★ 与 JwtAuthFilter 里那道判定是**两个入口**，缺一不可：
        //   这里拦"新登录"，那里拦"已经拿着票的老会话"。
        if (loginUser.getDisabled() != null && loginUser.getDisabled() == 1) {
            throw new BizException("该账号已被管理员禁用，请联系管理员解禁");
        }

        // ★★ 记一条登录日志（需求 三.3「最近登录时间、登录IP，数据取自系统登录日志」）。
        //
        //   ★ 位置很关键：**必须放在"账号密码已验过"之后**，也就是上面那个
        //     `if (loginUser == null) throw` 的下方 —— 否则"密码错"也会被记成一次成功登录，
        //     个人中心就会显示出一个根本不是本人操作的登录时间。
        //   ★ 同时也放在**签发 token 之前**：写日志失败绝不能让登录失败
        //     （LoginLogService.recordSuccess 内部整体 try-catch，吞掉异常只打日志），
        //     但顺序上仍然按"业务已完成 → 再记审计"来摆，避免将来有人把这里的 try 去掉。
        //   ★ 只记成功（status=1）：失败记录留到"登录失败锁定"时再写，理由见 LoginLogService 类注释。
        loginLogService.recordSuccess(loginUser.getId(), loginUser.getUsername(), loginUser.getRole(), request);

        // ★ 「记住我」用**双 token** 实现，而不是把 JWT 签成 7 天。
        //   原因：JWT 无法吊销，拉长有效期 = 把一个不可撤销的凭证暴露更久（被 XSS 偷走后
        //   改密码都救不回来）。所以：
        //     · access token：仍然 12 小时（短窗口限制损失）；
        //     · refresh token：**7 天**、存库（只存 SHA-256）、可吊销、一次性轮换，
        //       勾了"记住我"才发（见 RefreshTokenService）。
        String token = jwtUtil.generateToken(loginUser.getId(), loginUser.getUsername(), loginUser.getRole());
        String refreshToken = null;
        if (Boolean.TRUE.equals(req.getRememberMe())) {
            refreshToken = refreshTokenService.issue(loginUser.getId());
        }

        // ===== 接口文档通行证 Cookie（doc_token）=====
        // 作用：管理员登录后拿到一张"文档通行证"，DocAccessFilter 校验它才放行 /doc.html 等文档路径；
        //       普通用户登录时把它清空，于是他们打不开接口文档。
        // 关联：filter/DocAccessFilter.java（读这个 Cookie 做鉴权）、下方 addDocCookie()（构造方法）
        //
        // ★ doc_token **单独签发**，不复用主 JWT —— 它只有 **2 小时** + scope=doc：
        //   若 Cookie 里放的是**登录 token 本身**，Cookie 一旦泄露，攻击者拿到的是完整登录凭证，
        //   而不只是一张文档门票；单独签发则泄露了也只能看接口文档。
        //   Cookie 的 maxAge 必须与 token 的 exp 一致（都是 2 小时），否则会出现
        //   "Cookie 还在但 token 已过期"的错位。
        if (Role.isAdmin(loginUser.getRole())) {
            String docToken = jwtUtil.generateDocToken(
                    loginUser.getId(), loginUser.getUsername(), loginUser.getRole());
            addDocCookie(response, docToken, DOC_COOKIE_MAX_AGE_SECONDS);
        } else {
            addDocCookie(response, "", 0);                  // 非管理员：maxAge=0 → 浏览器立即删除
        }

        result.put("success", true);
        result.put("message", "登录成功");
        result.put("token", token);
        // ★ 只有勾了"记住我"才有这个字段（为 null 时前端不该把它写进 localStorage，
        //   否则下次会拿一个空串去刷 token，白跑一次请求）
        if (refreshToken != null) {
            result.put("refreshToken", refreshToken);
            result.put("refreshExpiresIn", RefreshTokenService.REFRESH_DAYS * 24 * 3600);
        }

        // ===== RBAC：下发角色码与权限点 =====
        // ★ 只下发"全局"权限点（t_user_role → t_role_permission）；
        //   "对某一份问卷"的权限（作者/协作者）由 GET /api/survey/{id} 的 myPermissions 单独给。
        //   前者决定菜单，后者决定这份问卷上的按钮，两者不要混。
        // ★ 前端**只用来做显隐**；真正的授权在后端（@RequirePermission + SurveyAccessService）。
        List<String> roleCodes = roleMapper.findRoleCodesByUserId(loginUser.getId());
        List<String> permissionCodes = roleMapper.findPermissionCodesByUserId(loginUser.getId());
        result.put("roles", roleCodes == null ? List.of() : roleCodes);
        result.put("permissions", permissionCodes == null ? List.of() : permissionCodes);

        loginUser.setPassword(null);
        result.put("user", loginUser);
        return result;
    }

    @OperationLog("退出登录")
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public String logout(@RequestBody(required = false) RefreshRequest req,
                         @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                         HttpServletResponse response) {
        // 退出时把文档通行证一并作废（maxAge=0 → 浏览器立即删除）
        addDocCookie(response, "", 0);
        // ★ 登出要**真正吊销 refresh token**，否则"我登出了"只是前端删了本地存储，
        //   那枚 7 天的凭证在服务端依然有效（别人捡到照样能续期）。
        //   两种吊销都做，覆盖"客户端带了 refresh token"与"没带但有登录身份"两种情况：
        //     · 带了 → 精确吊销这一枚（多设备登录时不影响其它设备）；
        //     · 没带但有 userId → 吊销该用户全部（"退出登录"语义上就是结束会话）。
        if (req != null && req.getRefreshToken() != null && !req.getRefreshToken().isBlank()) {
            refreshTokenService.revokeByRawToken(req.getRefreshToken());
        } else if (userId != null) {
            refreshTokenService.revokeAllByUser(userId);
        }
        return "已退出";
    }

    /**
     * 用 refresh token 换新的 access token（"记住我"续期）。
     *
     * ── 为什么这个端点在白名单里 ──────────────────────────
     *   调用它的**时刻**，access token 已经**失效** —— 而且实际触发点是
     *   "某个请求被 401 拒绝之后"（前端 http.js 收到 401 → 静默续期 → 重放原请求），
     *   不是"token 一过期就主动刷新"（提前续期尚未实现）。
     *   若要求它带有效的 access token，续期机制永远走不通。
     *   安全性由 refresh token 自己承担：存 SHA-256、7 天过期、一次性轮换、可吊销。
     *
     * ── 响应口径 ─────────────────────────────────────────
     *   返回新的 {token, refreshToken}：
     *     · token 每次都是新的（access）；
     *     · refreshToken 也是新的 —— **一次性轮换**，旧的那枚在 Service 里已被吊销。
     *       ★ 前端必须把两枚都存下来（沿用旧的 refresh token 下次会失败，这是有意的：
     *         凭证被盗用时"旧的那枚失效"能立刻暴露）。
     *
     * ── 失败一律 400「登录状态已失效，请重新登录」──────────
     *   不区分"没这个 token / 过期 / 已吊销 / 用户没了" —— 对客户端都是同一件事，
     *   也没有必要给它更多信息。
     */
    @Operation(summary = "刷新 access token（记住我续期，公开）")
    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshRequest req, HttpServletResponse response) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(req.getRefreshToken());
        User user = rotation.getUser();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "续期成功");
        result.put("token", jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole()));
        result.put("refreshToken", rotation.getRefreshToken());
        result.put("refreshExpiresIn", RefreshTokenService.REFRESH_DAYS * 24 * 3600);

        // doc_token 同步续期（否则管理员刷新页面后能看到业务页、却打不开接口文档）
        if (Role.isAdmin(user.getRole())) {
            addDocCookie(response, jwtUtil.generateDocToken(user.getId(), user.getUsername(), user.getRole()),
                    DOC_COOKIE_MAX_AGE_SECONDS);
        }

        user.setPassword(null);
        result.put("user", user);
        return result;
    }

    /**
     * 下发 / 清除接口文档专用 Cookie（doc_token）。
     *
     * ★ 为什么不用 jakarta.servlet.http.Cookie：
     *   旧版 Cookie API 不提供 SameSite 设置，只能手工拼 Set-Cookie 响应头。
     *   这里用 Spring 的 ResponseCookie —— 它能直接输出 SameSite，最后用 addHeader 写进响应。
     *
     * ★ SameSite=Strict 的意义：
     *   禁止浏览器在"跨站请求"里携带这个 Cookie，从源头挡住 CSRF。
     *   否则第三方网站可以诱导已登录管理员的浏览器带着 doc_token 去请求接口文档。
     *
     * @param response       HTTP 响应
     * @param value          Cookie 值；传空串 + maxAge=0 表示删除
     * @param maxAgeSeconds  有效秒数；传 0 表示立即过期
     */
    private void addDocCookie(HttpServletResponse response, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(DOC_COOKIE_NAME, value)
                .httpOnly(true)          // 禁止 JS 读取，防 XSS 偷走 token
                .path("/")               // 全站生效（文档路径都在根下）
                .maxAge(maxAgeSeconds)   // 0 = 让浏览器立即删除
                .sameSite("Strict")      // ★ 禁止跨站携带，挡 CSRF
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/profile")
    public User getProfile(@RequestHeader("X-User-Id") Integer userId) {
        // ★ 用 getByIdWithAvatar 而不是 getById：**个人中心是唯一需要头像的地方**。
        //   getById 刻意不查 mediumtext 的 avatar（每个请求白读一张最多 2MB 的图），
        //   见 UserMapper.findById 的注释。这里若用错了方法，症状是"个人中心头像消失"，
        //   而接口仍然 200 —— 属于沙箱里最容易漏掉的那类视觉回归。
        User user = userService.getByIdWithAvatar(userId);
        // ★ 失败抛 BizException（400 + 明确文案），与全项目其它端点一致：
        //   若直接 return null，就是 **HTTP 200 + 空响应体**，前端只能拿到 undefined，
        //   分不清"用户不存在"和"接口出问题"。
        if (user == null) {
            throw new BizException("用户不存在或登录状态已失效");
        }
        user.setPassword(null);
        return user;
    }

    @OperationLog("修改昵称")
    @Operation(summary = "修改昵称")
    @PutMapping("/nickname")
    public String updateNickname(@RequestBody Map<String, String> body,
                                 @RequestHeader("X-User-Id") Integer userId) {
        return userService.updateNickname(userId, body.get("nickname"));
    }

    @OperationLog("修改密码")
    @Operation(summary = "修改密码")
    @PutMapping("/password")
    public String updatePassword(@RequestBody Map<String, String> body,
                                 @RequestHeader("X-User-Id") Integer userId) {
        return userService.updatePassword(userId, body.get("oldPassword"), body.get("newPassword"));
    }

    @OperationLog("修改头像")
    @Operation(summary = "修改头像")
    @PutMapping("/avatar")
    public String updateAvatar(@RequestBody Map<String, String> body,
                               @RequestHeader("X-User-Id") Integer userId) {
        return userService.updateAvatar(userId, body.get("avatar"));
    }

    @OperationLog("修改联系方式")
    @Operation(summary = "修改手机号和邮箱")
    @PutMapping("/contact")
    public String updateContact(@Valid @RequestBody ContactRequest req,
                                @RequestHeader("X-User-Id") Integer userId) {
        // ★ 入参用 dto/ContactRequest + @Valid：手机号/邮箱的**格式与长度**校验
        //   都在 DTO 的注解上，失败由 GlobalExceptionHandler 翻成 400 + message；
        //   Service 里**不再**保留手写正则（"迁一个删一处"，别让两套规则并存）。
        return userService.updateContact(userId, req.getPhone(), req.getEmail());
    }

    // ★ 这里**不要**再加 `PUT /api/user/profile-public`（原「资料公开开关」，需求 三.4）：
    //   端点、UserService.updateProfilePublic、UserMapper.updateProfilePublic、User.profilePublic 字段、
    //   前端两个个人中心的「隐私设置」开关与 api.user.updateProfilePublic 都已整体移除 ——
    //   只加回端点而不补齐 Service/Mapper/实体/前端，调用链是断的。
    //   ★ 库里那一列 `t_user.profile_public` **还在**（删列不可逆，未随之删除）：
    //   真要恢复这个功能，先确认这一列再动手。

    /**
     * 查当前登录用户最近的登录记录（需求 三.3「账号安全信息展示：最近登录时间、登录IP」）。
     *
     * ── 为什么返回一个列表而不是"一条 lastLogin"──────────
     *   需求要的是"最近登录时间 + IP"。若只返回一条，那条就是**本次**登录
     *   （因为登录成功即写日志），对用户没有信息量；真正有用的是"上一次是谁、从哪登的"。
     *   所以返回最近若干条（上限写死在 LoginLogService 里，前端不传分页参数），
     *   由前端决定展示形态：第一行当"上次登录"，其余作为可展开的历史。
     *
     * ── 为什么没有 userId 参数 ────────────────────────────
     *   身份只从 `X-User-Id` 取（由 JwtAuthFilter 覆盖写入）。
     *   没有"按账号名查别人登录记录"的口子 —— 那是管理员审计功能，本需求不涉及。
     */
    @Operation(summary = "我的最近登录记录")
    @GetMapping("/login-logs")
    public List<LoginLog> loginLogs(@RequestHeader("X-User-Id") Integer userId) {
        return loginLogService.findRecent(userId);
    }

    // ★ 这里**不要**再加"一次返回全部普通用户"的端点（用下面的 /page）：
    //   ① /page 已经是"分页 + 关键字搜索"的完整替代（UserManage.vue 的用户列表走的就是它）；
    //   ② "一次返回全部普通用户"既没有上限、也绕过分页，数据量大时又慢又把响应体撑大；
    //   ③ 它对应的 UserService.listNormalUsers() 同样不保留（已无调用方）。
    //   ★ 下面这些端点**没有** @RequestHeader("X-User-Role") 形参（角色由类/方法上的
    //     @RequireAdmin → aspect/AdminCheckAspect 统一校验）：多一个"收了不用"的形参，
    //     只会让端点审计误判成"这里另有一道校验"。
    @Operation(summary = "分页查询用户（管理员，支持关键词搜索 + 角色/禁用状态筛选）")
    @GetMapping("/page")
    @RequirePermission(Permission.USER_MANAGE)
    public PageResult<User> pageUsers(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer disabled) {
        // ★ role 为空 = 全部角色（含管理员）；只认 admin/user，其余按"全部"处理（见 Service 注释）。
        // ★ disabled：1 = 只看已禁用、0 = 只看正常、空 = 全部（默认**不过滤**）。
        return userService.listActiveUsersPage(pageNum, pageSize, keyword, role, disabled);
    }

    /**
     * 分页查询**已注销**的用户（管理员）—— 「恢复账号」的入口列表。
     *
     * ★ 为什么需要单独一个端点（而不是给 /page 加个 includeDeleted 开关）：
     *   两者是**不同的视图**（"在用的人" vs "注销的人"），排序也不同
     *   （这份按注销时间倒序，管理员最常找的就是刚误点注销的那个）。
     *   而且软删除之后，已注销的人**必须**能在某个列表里被看到 ——
     *   否则 `/restore/{id}` 就是一个"有端点、没入口"的功能。
     *   消费方是 UserManage.vue：它按当前 tab 在 /page 与 /page-deleted 之间切换，
     *   并对选中行调 remove（彻底删除）或 restore（恢复）—— 两两成对，别单独删任一个。
     * ★ 同样支持 role 筛选（已注销的管理员也要能被筛出来并恢复）。
     */
    @Operation(summary = "分页查询已注销用户（管理员，用于恢复）")
    @GetMapping("/page-deleted")
    @RequirePermission(Permission.USER_MANAGE)
    public PageResult<User> pageDeletedUsers(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role) {
        return userService.listDeletedUsersPage(pageNum, pageSize, keyword, role);
    }

    /**
     * 禁用 / 解禁一个账号（管理员）。
     *
     * ── 为什么是"一个端点 + 布尔参数"而不是 /disable/{id} 与 /enable/{id} 两条 ──
     *   两者是**同一格状态的两个方向**，保护规则也完全一样（不能禁自己、不能禁用最后一个可用管理员）。
     *   拆成两条必然要把那段保护复制两份，改一处就会漏一处。
     *
     * ── 批量怎么用 ──────────────────────────────────────────
     *   前端多选后**逐条调用本端点**（Promise.allSettled），不另设"批量端点"：
     *   逐条调用能精确告诉管理员"哪几个成功、哪几个为什么失败"，
     *   而一个批量端点只要有一条不合法就得整批回滚 —— 对"清理违规账号"这种场景反而更难用。
     *
     * ★ disabled 只接受 0/1（Service 校验），非法值 → 400。
     * ★ 禁用/解禁都会作废该账号此前签发的 token（见 UserService.setDisabled）。
     */
    @OperationLog("设置用户禁用状态")
    @Operation(summary = "禁用/解禁用户（管理员）")
    @PutMapping("/{id}/disabled")
    @RequirePermission(Permission.USER_MANAGE)
    public String setUserDisabled(@PathVariable Integer id,
                                  @RequestParam(required = false) Integer disabled,
                                  @RequestHeader("X-User-Id") Integer currentUserId) {
        // ★ 这里**不能**把 disabled 声明成必填（required 默认 true）：
        //   必填 @RequestParam 的绑定发生在方法调用**之前**，缺参数会直接 400，
        //   于是权限切面（@RequireAdmin → AdminCheckAspect）根本没机会执行 ——
        //   普通用户不带参数调本端点会拿到 400 而不是 403，
        //   端点权限矩阵测试（EndpointAuthMatrixTest 的管理档逐条 403）会因此变红。
        //   改为非必填、在这里自己判：权限先判，参数后判。
        if (disabled == null) {
            throw new BizException("请指定 disabled 参数：1=禁用，0=解禁");
        }
        return userService.setDisabled(id, disabled, currentUserId);
    }

    /**
     * 导出用户列表为 Excel（管理员）。
     *
     * ★ 与页面上的两个视图一一对应：`deleted=true` 导「已注销」，否则导「在用」。
     *   筛选条件（keyword / role）与列表接口**同一套**，所以"导出的就是屏幕上筛出来的"。
     * ★ 上限 10000 行（UserService.EXPORT_MAX_ROWS），超出明确拒绝而不是静默截断。
     * ★ 不导出 avatar（mediumtext，单张可达 2MB）：导出表里只有账号/昵称/角色/状态/时间。
     */
    @Operation(summary = "导出用户列表为 Excel（管理员）")
    @GetMapping("/export")
    @RequirePermission(Permission.USER_MANAGE)
    public void exportUsers(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String role,
                            @RequestParam(defaultValue = "false") boolean deleted,
                            @RequestParam(required = false) Integer disabled,
                            HttpServletResponse response) throws IOException {
        List<User> users = userService.exportUsers(keyword, role, deleted, disabled);

        List<List<String>> head = List.of(
                List.of("ID"), List.of("账号"), List.of("昵称"), List.of("角色"),
                List.of("状态"), List.of("注册时间"), List.of(deleted ? "注销时间" : "禁用状态"));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<List<String>> rows = new ArrayList<>(users.size());
        for (User u : users) {
            List<String> row = new ArrayList<>(7);
            row.add(u.getId() == null ? "" : String.valueOf(u.getId()));
            row.add(nullToEmpty(u.getUsername()));
            row.add(nullToEmpty(u.getNickname()));
            row.add(Role.isAdmin(u.getRole()) ? "超级管理员"
                    : (Role.SUB_ADMIN.equals(u.getRole()) ? "次级管理员" : "普通用户"));
            row.add(deleted ? "已注销" : (u.getDisabled() != null && u.getDisabled() == 1 ? "已禁用" : "正常"));
            row.add(u.getCreatedTime() == null ? "" : u.getCreatedTime().format(fmt));
            row.add(deleted
                    ? (u.getDeletedAt() == null ? "" : u.getDeletedAt().format(fmt))
                    : (u.getDisabled() != null && u.getDisabled() == 1 ? "是" : "否"));
            rows.add(row);
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        // ★ 文件名两层编码（同 AnswerController.export）：filename 只能 ASCII，
        //   中文名放 filename*（RFC 5987），否则浏览器拿到的是 %E7%94%A8... 这种乱码名。
        String baseName = deleted ? "用户列表_已注销" : "用户列表";
        String encodedName = URLEncoder.encode(baseName, "UTF-8").replace("+", "%20");
        String asciiName = deleted ? "users_deleted.xlsx" : "users.xlsx";
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName + ".xlsx");

        EasyExcel.write(response.getOutputStream())
                .head(head)
                .sheet("用户列表")
                .doWrite(rows);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @OperationLog("删除用户")
    @Operation(summary = "删除用户（管理员）")
    @DeleteMapping("/delete/{id}")
    @RequirePermission(Permission.USER_MANAGE)
    public String deleteUser(@PathVariable Integer id,
                             @RequestHeader("X-User-Id") Integer currentUserId) {
        // ★ 必须把"当前登录用户 id"传给 Service —— Service 靠它挡住"管理员删自己"；
        //   权限本身由类/方法上的 @RequireAdmin 保证。
        int result = userService.deleteUser(id, currentUserId);
        if (result <= 0) {
            throw new BizException("删除失败");
        }
        return "删除成功";
    }

    // ==================== 账号注销（软删除，需求 三.5） ====================

    /**
     * 注销**自己的**账号（软删除）。
     *
     * ── 为什么路径是 /account 而不是 /delete/{id} ────────────
     *   `/delete/{id}` 是**管理员删别人**（物理删除 + 级联 7 张表）；
     *   本端点是**用户注销自己**（软删除 + 什么都不删）。两者语义完全不同、
     *   权限也不同（一个要管理员、一个只要本人），所以**刻意不复用**同一个路径 ——
     *   混在一起最容易出的错就是"哪天有人给 /delete 放宽了权限"。
     *
     * ── 身份不来自请求体 ──────────────────────────────────
     *   要注销的账号**永远是 `X-User-Id` 那一个**，没有任何 id 参数可传
     *   （否则这就是一个"能注销别人账号"的接口）。
     *
     * @param req    只含 password（确认身份）
     * @param userId 当前登录用户（JwtAuthFilter 覆盖写入）
     */
    @OperationLog("注销账号")
    @Operation(summary = "注销我的账号（软删除，需密码确认）")
    @DeleteMapping("/account")
    public String deleteAccount(@Valid @RequestBody AccountDeleteRequest req,
                                @RequestHeader("X-User-Id") Integer userId) {
        return userService.deleteAccount(userId, req.getPassword());
    }

    /**
     * 恢复一个**已注销**的账号（管理员）。
     *
     * ★ 与 `/delete/{id}` 的关系：那条是"彻底删除"（物理、级联），这条是"撤销注销"（把 deleted_at 清零）。
     *   为什么必须有它：软删除对用户自己不可逆，误点注销或账号被盗后被恶意注销，
     *   没有这条就只能去改数据库 —— 而本项目的取向是"能通过界面做的，就不要让人去动库"。
     * ★ 恢复后：注销时吊销的 refresh token 不会复活，**且注销前签发的 access token 也一律作废**
     *   （Service 会把 `token_valid_after` 推到此刻，过滤器按 JWT 的 `iat` 拒绝旧票）——
     *   所以"恢复后需要重新登录"不是提醒，而是事实。
     */
    @OperationLog("恢复已注销账号")
    @Operation(summary = "恢复已注销账号（管理员）")
    @PutMapping("/restore/{id}")
    @RequirePermission(Permission.USER_MANAGE)
    public String restoreUser(@PathVariable Integer id,
                              @RequestHeader("X-User-Id") Integer operatorId) {
        return userService.restoreUser(id, operatorId);
    }

    // ★ 这里**不要**再加"普通用户数量"的独立端点：
    //   这个数字**已经有两个来源** —— 用户管理页从 /page 的 total 字段拿，
    //   管理员看板从 /api/survey/system-overview 的 users 字段拿
    //   （SurveyController.systemOverview() 里 put("users", userService.countNormalUsers())）。
    //   ★ 再多一个入口就多一处需要同步的口径；countNormalUsers() 本身**保留** ——
    //   system-overview 那条路径还在用它。
}