package com.survey.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求体 —— {@code POST /api/user/refresh}。
 *
 * ── 为什么要校验非空 ──────────────────────────────────
 *   这个端点在 JwtAuthFilter 白名单里（调用它时 access token **已经过期**，
 *   不可能要求带有效的 access token，否则续期机制永远走不通），
 *   所以它是**公开可打**的；一个 @NotBlank 就能把空请求挡在 Service 之前，
 *   省掉一次无意义的 SHA-256 与查库。
 *
 * ── 安全说明（写给下一个人）──────────────────────────
 *   这里**只做非空**，真正的判定全在 {@link com.survey.service.RefreshTokenService#rotate}：
 *   存在性（按 SHA-256 查库）、未过期、未吊销、用户仍存在，成功即**一次性轮换**
 *   （旧的立即吊销、发新的）。不要在这里加"看起来像不像 token"的格式校验 ——
 *   那只会给攻击者额外反馈；也不要因为"服务端已经哈希存储"就在日志里打印明文。
 */
public class RefreshRequest {

    @NotBlank(message = "缺少刷新令牌")
    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
