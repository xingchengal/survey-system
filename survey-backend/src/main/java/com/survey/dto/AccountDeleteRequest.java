package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 「账号注销」请求体（软删除）：身份只取 JwtAuthFilter 注入的 X-User-Id，不接受 userId 参数。
 * ★ 必须带密码：账号级不可逆操作，不能只凭可能被盗的 token 注销；密码规则由 UserService.verifyPassword 判定。
 * 长度上限 64 只兜超长口令，不做最小长度校验 —— 老账号的短密码也要能注销。
 */
public class AccountDeleteRequest {

    /** 登录密码（必填），校验失败由 GlobalExceptionHandler 统一转 400。 */
    @NotBlank(message = "请输入密码以确认注销")
    @Size(max = 64, message = "密码不能超过 64 位")
    private String password;

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
