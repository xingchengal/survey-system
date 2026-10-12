package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 「账号注销」请求体 —— 对应「个人中心改版」需求 三.5（采用软删除方案）。
 *
 * ── 为什么注销要带密码 ────────────────────────────────
 *   这是**不可逆的账号级操作**（对用户自己而言不可逆；管理员能恢复，但用户看不到那条路）。
 *   只凭一枚可能被盗的 token 就能把账号注销，风险明显高于"多输一次密码"的麻烦 ——
 *   与 `updatePassword` 要求原密码是同一个理由。
 *
 * ── 为什么不用 @Size 的下限来当"密码规则" ──────────────
 *   `@Size(max = 64)` 只兜住**上限**（超长口令没必要送进 PBKDF2 比对流程，与 LoginRequest 同款）。
 *   这里**不该**校验最小长度：密码规则属于注册/改密，注销只是"确认你是本人"，
 *   老账号的密码可能不满足新规则（例如历史 MD5 时代的短口令）——
 *   真在那里拦下来，用户会陷入"想注销却因为密码太短被拒"的死胡同。
 *   长度对不对由 `UserService.verifyPassword` 判定。
 */
public class AccountDeleteRequest {

    /** 登录密码（必填）。失败由 GlobalExceptionHandler 统一翻成 400 + message。 */
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
