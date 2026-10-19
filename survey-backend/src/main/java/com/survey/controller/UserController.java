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
 * 用户管理：注册、登录/登出、资料维护、管理员用户管理与导出。
 * 权限矩阵：公开 = /register、/login、/refresh；需登录 = /logout、/profile、/login-logs、
 * PUT /nickname|/password|/avatar|/contact、DELETE /account；管理员 = /page、/page-deleted、
 * /delete/{id}、/restore/{id}、/{id}/disabled、/export。
 * 所有"我是谁"只取 X-User-Id（JwtAuthFilter 覆盖写入），端点不接受可伪造的 userId。
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

    /**
     * ★ 登录时直接注入 RoleMapper 是唯一例外：只读查询、无事务，为了一次取回角色码与权限点。
     * 写路径一律走 Service，不要照这里在 Controller 里直连 Mapper。
     */
    @Autowired
    private RoleMapper roleMapper;

    /** "记住我"的双 token：长效、可吊销的 refresh token（access token 仍短命，理由见 login()） */
    @Autowired
    private RefreshTokenService refreshTokenService;

    /**
     * 登录日志（t_login_log）单独写：@OperationLog 是 @AfterReturning，只能记成功的那一次，
     * 而登录日志要按"每次一行 + status 列"落库。
     */
    @Autowired
    private LoginLogService loginLogService;

    private static final String DOC_COOKIE_NAME = "doc_token";

    /** 文档通行证 Cookie 有效期 2 小时，必须与 JwtUtil.generateDocToken() 签发的 exp 一致。 */
    private static final long DOC_COOKIE_MAX_AGE_SECONDS = 2 * 3600;

    @OperationLog("用户注册")
    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public String register(@Valid @RequestBody RegisterRequest req,
                           @RequestParam(value = "captchaToken", required = false) String captchaToken) {
        // 先过滑块验证再看表单：脚本连"账号是否存在"都拿不到。
        // 判定在 POST /api/captcha/verify 一次做完（依赖前端组件的区间与容差），这里只消费已标记的凭证。
        captchaService.consumeVerifiedOrThrow(captchaToken);

        // 格式规则在 RegisterRequest 的注解上，这里只做 DTO → 实体搬运；查重/哈希/角色强制在 UserService.register。
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(req.getPassword());
        user.setNickname(req.getNickname());
        // 手机号只采集存储，不参与登录/短信；为空时保持 null
        user.setPhone(req.getPhone());
        return userService.register(user, req.getPhone());
    }

    /**
     * 登录：刻意不挂 @OperationLog，登录事件记在专用的 t_login_log（个人中心展示最近登录时间/IP）。
     */
    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        // 登录不做最小长度校验（注册规则），但保留 @Size(max = 64) 上限：超长口令没必要送进 PBKDF2。
        User loginUser = userService.login(req.getUsername(), req.getPassword());
        // 失败抛 BizException（400）而不是 200 + success:false：否则 Login.vue 漏掉 else 分支就毫无反应。
        // 文案不区分"账号不存在"与"密码错误"，避免账号探测。
        if (loginUser == null) {
            throw new BizException("账号或密码错误");
        }

        // 禁用判定必须在密码验过之后：否则乱试密码就能从"已被禁用"得知账号存在。
        // 与 JwtAuthFilter 那道判定是两个入口：这里拦新登录，那里拦已签发的旧会话。
        if (loginUser.getDisabled() != null && loginUser.getDisabled() == 1) {
            throw new BizException("该账号已被管理员禁用，请联系管理员解禁");
        }

        // 必须放在密码验过之后、签发 token 之前：否则密码错也会被记成一次成功登录。
        // 只记成功；写日志失败由 LoginLogService 内部吞掉，不能反过来让登录失败。
        loginLogService.recordSuccess(loginUser.getId(), loginUser.getUsername(), loginUser.getRole(), request);

        // 「记住我」用双 token：JWT 无法吊销，拉长有效期等于把不可撤销凭证暴露更久。
        // access token 仍 12 小时；refresh token 7 天、只存 SHA-256、一次性轮换、可吊销。
        String token = jwtUtil.generateToken(loginUser.getId(), loginUser.getUsername(), loginUser.getRole());
        String refreshToken = null;
        if (Boolean.TRUE.equals(req.getRememberMe())) {
            refreshToken = refreshTokenService.issue(loginUser.getId());
        }

        // 文档通行证单独签发（2 小时 + scope=doc）：Cookie 泄露时拿不到完整登录凭证。
        // 非管理员写空值 + maxAge=0，浏览器立即删除。
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
        // 只有勾了"记住我"才有这个字段：null 时前端别写进 localStorage，否则会拿空串白跑一次续期。
        if (refreshToken != null) {
            result.put("refreshToken", refreshToken);
            result.put("refreshExpiresIn", RefreshTokenService.REFRESH_DAYS * 24 * 3600);
        }

        // 只下发全局权限点（决定菜单）；某份问卷上的权限由 GET /api/survey/{id} 的 myPermissions 给。
        // 前端只用于显隐，真正授权在 @RequirePermission + SurveyAccessService。
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
        // 登出必须真正吊销 refresh token，否则 7 天凭证在服务端仍然有效：
        // 带了就精确吊销这一枚（不影响其它设备），没带但有 userId 就吊销该用户全部。
        if (req != null && req.getRefreshToken() != null && !req.getRefreshToken().isBlank()) {
            refreshTokenService.revokeByRawToken(req.getRefreshToken());
        } else if (userId != null) {
            refreshTokenService.revokeAllByUser(userId);
        }
        return "已退出";
    }

    /**
     * 用 refresh token 换新的 access token（白名单）：调用时 access token 通常已失效，
     * 若要求它有效，续期机制永远走不通。安全由 refresh token 自己承担：存 SHA-256、7 天过期、
     * 一次性轮换、可吊销。返回新的 {token, refreshToken}，旧的 refresh token 已被吊销。
     * 失败一律 400「登录状态已失效，请重新登录」，不区分原因。
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
     * 用 Spring 的 ResponseCookie（旧 Cookie API 不支持 SameSite）；SameSite=Strict 从源头挡 CSRF。
     *
     * @param value 空串 + maxAge=0 表示删除
     */
    private void addDocCookie(HttpServletResponse response, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(DOC_COOKIE_NAME, value)
                .httpOnly(true)          // 禁止 JS 读取，防 XSS 偷走 token
                .path("/")               // 全站生效（文档路径都在根下）
                .maxAge(maxAgeSeconds)   // 0 = 让浏览器立即删除
                .sameSite("Strict")      // 禁止跨站携带，挡 CSRF
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/profile")
    public User getProfile(@RequestHeader("X-User-Id") Integer userId) {
        // 个人中心是唯一需要头像的地方：getById 刻意不查 mediumtext avatar，用错方法会"头像消失但接口 200"。
        User user = userService.getByIdWithAvatar(userId);
        // 失败抛 BizException：return null 会变成 200 + 空响应体，前端分不清"用户不存在"和接口故障。
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
        // 格式与长度校验都在 ContactRequest 的注解上，Service 不再保留手写正则（别让两套规则并存）。
        return userService.updateContact(userId, req.getPhone(), req.getEmail());
    }

    // 不要加回「资料公开」端点：端点/Service/Mapper/前端已整体移除，t_user.profile_public 列还在但没有读写路径。

    /**
     * 我的最近登录记录：身份只取 X-User-Id，不接受 userId 参数（没有查别人记录的入口）。
     * 返回最近若干条而不是一条：登录成功即写日志，只给本次登录对用户没有信息量。
     */
    @Operation(summary = "我的最近登录记录")
    @GetMapping("/login-logs")
    public List<LoginLog> loginLogs(@RequestHeader("X-User-Id") Integer userId) {
        return loginLogService.findRecent(userId);
    }

    // 不要再加"一次返回全部用户"的端点：/page 已覆盖分页 + 搜索，全量返回无上限。
    // 管理端点不要收 X-User-Role 形参：角色由 @RequirePermission 统一校验，多收不用只会误导审计。
    @Operation(summary = "分页查询用户（管理员，支持关键词搜索 + 角色/禁用状态筛选）")
    @GetMapping("/page")
    @RequirePermission(Permission.USER_MANAGE)
    public PageResult<User> pageUsers(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer disabled) {
        // role 为空 = 全部角色；只认 admin/user，其余按"全部"处理（见 Service）。
        // disabled：1 = 只看已禁用、0 = 只看正常、空 = 不过滤。
        return userService.listActiveUsersPage(pageNum, pageSize, keyword, role, disabled);
    }

    /**
     * 已注销用户列表（管理员）：恢复账号的唯一入口，按注销时间倒序。
     * 与 /page 是两个不同视图，别合并；软删除后必须能在某个列表里看到，否则 /restore/{id} 有端点没入口。
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
     * 禁用 / 解禁（管理员）：一个端点 + 0/1 参数，两个方向共用同一套保护
     * （不能禁自己、不能禁用超管；次级管理员只有超管能操作）。
     * 批量由前端逐条调，能精确回报每条失败原因；批量端点一条不合法就整批回滚，反而难用。
     */
    @OperationLog("设置用户禁用状态")
    @Operation(summary = "禁用/解禁用户（管理员）")
    @PutMapping("/{id}/disabled")
    @RequirePermission(Permission.USER_MANAGE)
    public String setUserDisabled(@PathVariable Integer id,
                                  @RequestParam(required = false) Integer disabled,
                                  @RequestHeader("X-User-Id") Integer currentUserId) {
        // disabled 不能声明成必填：必填 @RequestParam 在权限切面之前绑定，缺参数会先 400，
        // 普通用户拿到的就不是 403。所以权限先判、参数后判。
        if (disabled == null) {
            throw new BizException("请指定 disabled 参数：1=禁用，0=解禁");
        }
        return userService.setDisabled(id, disabled, currentUserId);
    }

    /**
     * 导出用户为 Excel（管理员）：deleted=true 导已注销，否则导在用；筛选与列表接口同一套。
     * 上限 10000 行，超出明确拒绝；不导 avatar（mediumtext，单张可达 2MB）。
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
        // 文件名两层编码：filename 只能 ASCII，中文名放 filename*（RFC 5987），否则浏览器拿到 %E7... 乱码名。
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
        // 必须传当前登录用户 id：Service 靠它拦"删自己"与"非超管删管理员"，超管账号永远不可删。
        int result = userService.deleteUser(id, currentUserId);
        if (result <= 0) {
            throw new BizException("删除失败");
        }
        return "删除成功";
    }

    // ==================== 账号注销（软删除） ====================

    /**
     * 注销自己的账号（软删除）：身份永远是 X-User-Id，没有任何 id 参数（否则就能注销别人）。
     * 与 /delete/{id} 语义、权限都不同（管理员物理删除别人 vs 本人软删除自己），刻意不复用路径。
     *
     * @param req 只含 password，用于确认身份
     */
    @OperationLog("注销账号")
    @Operation(summary = "注销我的账号（软删除，需密码确认）")
    @DeleteMapping("/account")
    public String deleteAccount(@Valid @RequestBody AccountDeleteRequest req,
                                @RequestHeader("X-User-Id") Integer userId) {
        return userService.deleteAccount(userId, req.getPassword());
    }

    /**
     * 恢复已注销账号（管理员）：把 deleted_at 清零，并推 token_valid_after 让注销前签发的 token 全部作废。
     * 所以"恢复后需要重新登录"是事实，不是提醒。
     */
    @OperationLog("恢复已注销账号")
    @Operation(summary = "恢复已注销账号（管理员）")
    @PutMapping("/restore/{id}")
    @RequirePermission(Permission.USER_MANAGE)
    public String restoreUser(@PathVariable Integer id,
                              @RequestHeader("X-User-Id") Integer operatorId) {
        return userService.restoreUser(id, operatorId);
    }

    // 不要再加"普通用户数量"端点：用户管理页取 /page 的 total，看板取 /system-overview 的 users。
    // countNormalUsers() 保留给 system-overview。
}