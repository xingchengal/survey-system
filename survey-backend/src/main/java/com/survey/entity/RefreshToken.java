package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 刷新令牌实体 —— 对应表 t_refresh_token（"记住我"用的长效凭证）。
 *
 * ── 它解决什么问题 ────────────────────────────────────
 *   access token（JWT）有效期只有 12 小时且**无法吊销**（项目没有 token 黑名单）。
 *   "记住我"如果直接把 JWT 有效期拉长到 7 天，等于把一个**无法撤销**的凭证暴露 7 天 ——
 *   一旦 XSS 偷走 localStorage 里那枚 token，攻击者可以畅通 7 天，改密码也救不回来。
 *   所以改用双 token：
 *     · access token（JWT，12 小时）：每次请求带它，过期就过期 —— 短窗口**限制损失**；
 *     · refresh token（本表，7 天）  ：只用来换新的 access token，
 *       而且**存在库里、可吊销、一次性轮换** —— 泄露了也能通过登出/改密立刻作废。
 *
 * ── 安全设计（改这个类之前先读）────────────────────────
 *   · **不存明文**：只存 token 的 SHA-256 十六进制（{@code token_hash}）——
 *     数据库泄露也拿不到可用凭证；比对时按 hash 查，天然是常量时间索引查找。
 *   · **一次性轮换**：每次 /refresh 成功都签发新 token 并把旧的标 {@code revoked=1}，
 *     同一个 refresh token 用第二次必失败 → 被盗用会立刻暴露（旧的那枚失效）。
 *   · **7 天过期**：{@code expires_at} 到了就当无效（且有定时任务清理）。
 *   · 表上还有 {@code idx_expires_at}，清理走索引。
 */
public class RefreshToken {

    private Integer id;
    /** 所属用户（t_user.id） */
    private Integer userId;
    /** 令牌的 SHA-256 十六进制（**不是明文**） */
    private String tokenHash;
    /** 过期时间（签发时 = now + 7 天） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expiresAt;
    /** 是否吊销：0=有效，1=已吊销（轮换、登出、改密时置 1） */
    private Integer revoked;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public Integer getRevoked() { return revoked; }
    public void setRevoked(Integer revoked) { this.revoked = revoked; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
