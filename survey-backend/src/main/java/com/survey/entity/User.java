package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * 用户实体 —— 表 t_user（map-underscore-to-camel-case 自动映射 created_time ↔ createdTime）。
 * ★ deleted_at 软删除：NULL = 正常，非 NULL = 已注销；所有查用户的 SQL 都带 deleted_at IS NULL。
 * ★ password 用 @JsonProperty(WRITE_ONLY)：请求体能写入、序列化不返回；不能换 @JsonIgnore，否则注册/登录反序列化不到。
 */
public class User {
    private Integer id;
    private String username;

    /** 密码（PBKDF2 加盐哈希，格式 pbkdf2$迭代次数$盐$哈希；旧 MD5 数据登录时自动升级）。
     * WRITE_ONLY：只从请求体写入、绝不序列化返回；换 @JsonIgnore 会让注册/登录解不出密码。 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private String nickname;         // 昵称（操作日志、回收站里显示的就是它）
    private String role;             // "admin" 或 "user"，权限判断的依据
    private LocalDateTime createdTime;
    private String avatar;           // 头像：base64 字符串直接存库（mediumtext；真实生效上限是解码后 2MB，见 AvatarValidator）
    private String phone;            // 手机号（1[3-9] 开头的 11 位）
    private String email;            // 邮箱

    /** 注销时间（软删除）：NULL = 正常，非 NULL = 已注销；随 /api/user/profile 返回前端。
     * 只由 UserService.deleteAccount / restoreUser 写，没有 DTO 暴露它。
     * 已注销账号仍占着原 username（防抢注），放开需把唯一索引改成 (username, deleted_at)。 */
    private LocalDateTime deletedAt;

    /** 令牌生效起点：JWT 的 iat 必须**严格晚于**它，否则视为失效（NULL = 不限制）。
     * 语义是「此刻之前签发的票全部作废」：JWT 无黑名单，只能靠时间点踢掉旧会话。
     * 写入点只有 restoreUser 与 updatePassword；新增写入点先想清「作废谁的票」。 */
    private LocalDateTime tokenValidAfter;

    /** 禁用标记：0 = 正常，1 = 已禁用（与 deletedAt 的注销不同，禁用是管理员的处置）。
     * 三处生效缺一不可：登录时判、JwtAuthFilter 每请求判、禁用/解禁同时推 tokenValidAfter（否则最长等 12 小时）。
     * 用 Integer 而非 boolean：与 tinyint 列对应，null 表达「老数据没读到」，前端显示正常。 */
    private Integer disabled;

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public LocalDateTime getTokenValidAfter() { return tokenValidAfter; }
    public void setTokenValidAfter(LocalDateTime tokenValidAfter) { this.tokenValidAfter = tokenValidAfter; }
    public Integer getDisabled() { return disabled; }
    public void setDisabled(Integer disabled) { this.disabled = disabled; }
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