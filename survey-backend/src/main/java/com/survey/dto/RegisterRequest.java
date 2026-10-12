package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体 —— 对应「登录注册改版」需求。
 *
 * ── 字段口径（前端必须与这里一致，后端是唯一裁判）──────
 *   · username：**账号**（唯一标识，不可修改）。只允许大小写字母与数字、4~20 位
 *     （需求："禁止汉字、空格、特殊符号"；提示文案"账号仅支持字母和数字，4~20个字符"）。
 *   · password：6~20 位（需求）。★ 比上一版（6~64）**更严**：
 *     上一版把上限放到 64 是为了"挡住超长口令进 PBKDF2"，现在按需求收紧到 20。
 *   · nickname：可空，≤50（沿用 t_user.nickname 的列宽）。
 *   · phone：**可选**，国内 11 位手机号。本次只采集存储，
 *     **不实现手机号登录/短信**（需求明确写了），字段留着供后续扩展。
 *
 * ── 为什么校验分两处（别把它们合并）──────────────────
 *   本 DTO 负责**声明式的格式约束** —— 失败由 @Valid 触发，经 GlobalExceptionHandler
 *   统一翻成 400 + 明确文案，且**不写异常日志**。
 *   UserService.register 继续负责**业务规则**：查重、哈希、昵称兜底、角色强制为 user。
 *   两者不重复：注解拦"格式不对"，Service 拦"格式对但业务不允许"。
 *
 * ── 滑块验证码（需求第 3 条）──────────────────────────
 *   captchaToken / captchaLeft **不在本 DTO 里** —— 它们在 UserController.register 里
 *   先经 CaptchaService 二次校验，不过就直接 400（见那边的注释）。
 *   这么摆是有意的：让"验证码"成为**进不了 DTO 的一道门**，而不是一个可选的字段。
 */
public class RegisterRequest {

    /** 账号：字母+数字，4~20 位（与前端提示文案"账号仅支持字母和数字，4~20个字符"一致） */
    @NotBlank(message = "账号不能为空")
    @Pattern(regexp = "^[A-Za-z0-9]{4,20}$", message = "账号仅支持字母和数字，4~20个字符")
    private String username;

    /** 密码：6~20 位；服务端哈希后存储，绝不存明文 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度需在 6~20 位之间")
    private String password;

    /** 昵称可空：Service 会用账号兜底。 */
    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    /**
     * 手机号（可选）：国内 11 位。
     * ★ 用 @Pattern 而不是只限长度：需求要的是"校验国内 11 位手机号格式"，
     *   只限长度会把 00000000000 放进来。正则允许空串（=不填）。
     */
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
