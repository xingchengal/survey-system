package com.survey.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新令牌请求体 —— POST /api/user/refresh（logout 也复用它）。
 * ★ 一次性轮换：rotate 成功后旧 token 立即吊销、发新的；服务端只存 SHA-256，7 天过期、可吊销。
 * 这里只做非空：refresh 端点公开可打，格式校验只会给攻击者额外反馈；不要打印明文日志。
 * 没有 token 的 logout 走「吊销该用户全部 refresh token」分支。
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
