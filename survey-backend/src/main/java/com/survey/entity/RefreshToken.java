package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 刷新令牌 —— 表 t_refresh_token，「记住我」用的长效凭证。
 * 双 token：access（JWT，12 小时，无法吊销）× refresh（本表，7 天，可吊销、一次性轮换）。
 * ★ 只存 SHA-256 十六进制、不存明文：库泄露也拿不到可用凭证。
 * ★ 每次 /refresh 成功即把旧 token 标 revoked=1，重放同一枚必失败（被盗会立刻暴露）。
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
