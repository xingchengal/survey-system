package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体 —— 格式约束在本 DTO（@Valid 失败由 GlobalExceptionHandler 统一转 400），业务规则在 UserService.register。
 * username 字母数字 4~20；password 6~20；nickname 可空 ≤50（列宽）；phone 可选、国内 11 位，只存不用（不做手机号登录/短信）。
 * ★ 滑块 captchaToken 不在本 DTO：UserController.register 在 @Valid 之前强制消费，未通过直接 400，绕不过去。
 */
public class RegisterRequest {

    /** 账号：字母 + 数字，4~20 位；不可修改 */
    @NotBlank(message = "账号不能为空")
    @Pattern(regexp = "^[A-Za-z0-9]{4,20}$", message = "账号仅支持字母和数字，4~20个字符")
    private String username;

    /** 密码：6~20 位；服务端哈希后存储，绝不存明文 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度需在 6~20 位之间")
    private String password;

    /** 昵称：可空，Service 用账号兜底；上限 50 = t_user.nickname 列宽 */
    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    /** 手机号（可选）：国内 11 位，空串 = 不填。★ 用 @Pattern 而不是只限长度：否则 00000000000 也能过 */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确（应为 11 位国内手机号）")
    private String phone;

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

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
