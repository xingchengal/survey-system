package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求体 —— 入参规则与实体分离：同一 User 在注册/登录下规则不同，写进实体表达不了。
 * username 非空且 ≤50（列宽）；password 非空且 ≤64，不做最小长度校验（"至少 6 位"是注册规则，登录说出来等于给攻击者反馈），上限只挡超长口令进 PBKDF2。
 * ★ 账号字符正则不放这里：库里存有中文/短账号，写死会让它们立刻登不进去；改由 UserService.login 按 app.strict-username-check 控制。
 */
public class LoginRequest {

    @NotBlank(message = "账号不能为空")
    @Size(max = 50, message = "账号不能超过 50 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码不能超过 64 个字符")
    private String password;

    /** 记住我：勾选后额外签发 7 天 refresh token；不勾选只发 access token（12 小时） */
    private Boolean rememberMe;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Boolean getRememberMe() {
        return rememberMe;
    }

    public void setRememberMe(Boolean rememberMe) {
        this.rememberMe = rememberMe;
    }
}
