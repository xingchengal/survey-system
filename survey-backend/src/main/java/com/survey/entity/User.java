package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * 用户实体 —— 对应表 t_user。
 *
 * ── 字段 ↔ 列 ────────────────────────────────────────
 *   id / username / password / nickname / role / created_time / avatar / phone / email /
 *   deleted_at / token_valid_after
 *   （项目开了 map-underscore-to-camel-case，created_time ↔ createdTime 自动映射）
 *
 * ── ★ 表里有、但本实体**故意不映射**的列（别以为是漏了）──
 *   `profile_public`：原「资料公开开关」（需求 三.4）。需求方后来明确"不需要这个需求了，
 *   对应代码也剔除"，所以应用侧已全部删掉（端点、Service、Mapper、界面都没了）。
 *   **列本身留在库里没删**（删列不可逆，要单独确认）—— 见 UserMapper 类注释里的同一句说明。
 *
 * ── ★ deleted_at：软删除（账号注销，需求 三.5）──────────
 *   语义（唯一口径，改之前先读）：**NULL = 正常；非 NULL = 已注销**。
 *   · 所有"查用户"的 SQL 都带 `deleted_at IS NULL`（见 UserMapper 的类注释）——
 *     正因如此，"注销后登不进去、用户列表里看不到、JWT 立刻失效"这三件事
 *     **一处都不用另写判断**；
 *   · 它**会**随 /api/user/profile 返回给前端（前端据此显示"注销账号"还是"已注销"），
 *     所以这里是一个正常的映射字段，不再是"故意不映射"。
 *   · ★ 与 `username` 唯一索引的关系：已注销账号**仍然占着那个用户名**（有意为之，
 *     防他人抢注）；要放开得把唯一索引改成 (username, deleted_at) 组合唯一 —— 那是另一个决定。
 *
 * ── 谁在用 ────────────────────────────────────────────
 *   UserController（注册/登录/资料）、UserService、UserMapper、LogAspect（查昵称填日志）
 *
 * ── ★ 安全要点：password 字段的处理 ────────────────────
 *   用 @JsonProperty(WRITE_ONLY) 而不是 @JsonIgnore，原因很关键：
 *     - WRITE_ONLY：反序列化（请求体 → 对象）照常，序列化（对象 → JSON）时被忽略。
 *       register/login 用 @RequestBody User 接收，密码必须能**传进来**。
 *     - @JsonIgnore：两个方向都忽略 → password 永远读不到 → 注册和登录直接坏掉。
 *   所以这里只能用 WRITE_ONLY。加上它之后，/api/user/list、/api/user/page
 *   再也不会把全站口令哈希发给前端（在此之前是真的会返回的）。
 */
public class User {
    private Integer id;
    private String username;

    /**
     * 密码（PBKDF2 加盐哈希，格式 pbkdf2$迭代次数$盐$哈希；旧数据可能是 MD5，登录时会自动升级）。
     * 只允许"从请求体写入"，绝不序列化返回给前端 —— 这样 /api/user/list、/api/user/page
     * 就不会再把全站口令哈希发给浏览器。
     * 注意：这里不能用 @JsonIgnore，因为 register/login 用的是 @RequestBody User，
     * 加了 @JsonIgnore 会导致 password 反序列化不进来，注册和登录会直接坏掉。
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private String nickname;         // 昵称（操作日志、回收站里显示的就是它）
    private String role;             // "admin" 或 "user"，权限判断的依据
    private LocalDateTime createdTime;
    private String avatar;           // 头像：base64 字符串直接存库（mediumtext；真实生效上限是解码后 2MB，见 AvatarValidator）
    private String phone;            // 手机号（1[3-9] 开头的 11 位）
    private String email;            // 邮箱

    /**
     * 注销时间（软删除）：**NULL = 正常；非 NULL = 已注销**。
     * ★ 对应列 `t_user.deleted_at`（账号注销的软删除标记）。
     * ★ 它随 /api/user/profile 返回给前端（前端据此把「注销账号」换成「已注销」提示）；
     *   但**不要**把它当成"能由请求体写入"的字段 —— 只有 `UserService.deleteAccount`
     *   与 `restoreUser` 两个方法会写它（没有任何 DTO 暴露它）。
     */
    private LocalDateTime deletedAt;

    /**
     * 令牌生效起点：JWT 的 `iat` **早于**它即视为失效（NULL = 不限制）。
     * ★ 列 `t_user.token_valid_after`，语义是"此刻之前签发的票全部作废"。
     * ★ 为什么需要它（JWT 无法吊销，这是唯一能作废已发出票据的办法）：
     *   access token 是自包含的，服务端没有黑名单；要"踢掉别的会话"只能记一个时间点，
     *   然后在过滤器里拒绝签发时间早于它的票（见 `JwtAuthFilter` / `DocAccessFilter`）。
     * ★ 写入点只有两个，都在 Service 里：`restoreUser`（恢复已注销账号）与
     *   `updatePassword`（改密码）——**不要**新增写入点而不想清楚"作废谁的票"。
     */
    private LocalDateTime tokenValidAfter;

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public LocalDateTime getTokenValidAfter() { return tokenValidAfter; }
    public void setTokenValidAfter(LocalDateTime tokenValidAfter) { this.tokenValidAfter = tokenValidAfter; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}