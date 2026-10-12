package com.survey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求体 —— 对应「登录注册改版」需求。
 *
 * ── 为什么要建 DTO 层 ─────────────────────────────────
 *   若直接拿实体（{@code User}）当请求体接收入参，校验只能靠 Service 里手写
 *   `if (… == null) throw new BizException(...)`。这种写法的问题：
 *     1. **字段规则和实体绑定** —— 同一个 User 实体在"注册"和"登录"下规则并不一样，
 *        写进实体就表达不了；
 *     2. 新增接口容易**漏写校验**，而且错误文案与状态码口径不统一。
 *   DTO 让"接口入参"和"数据库实体"各管各的，规则用注解声明，失败由
 *   GlobalExceptionHandler 统一翻成 400 + {success:false,message}。
 *
 * ── 字段口径──────────────────────────────────────────
 *   · username：账号。这里只做**非空 + 长度上限**（50，与列宽一致）；
 *     "只允许字母数字 4~20 位"这条**不写在这里**，原因见下。
 *   · password：非空 + 上限 64。★ 登录**不做最小长度校验**（"至少 6 位"是注册规则，
 *     不该由登录接口说出来，那会给攻击者反馈），但保留上限以挡住超长口令进 PBKDF2。
 *   · rememberMe：勾选后额外发一枚 **7 天 refresh token**（双 token 方案）。
 *
 * ── 账号字符校验为什么不在这里（重要）────────────────
 *   需求要求"登录、注册需要校验用户名字符类型"，但本项目库里**存有历史账号**
 *   （含中文、2 位长）。若把正则写死在 DTO 上，那些账号会**立刻登不进去**，
 *   而且没有任何开关可救。所以这条校验放在 UserService.login，
 *   由配置项 {@code app.strict-username-check} 控制（默认 true = 满足需求；
 *   万一被锁在外面，改配置即可放行，不必改代码、不必重新编译）。
 */
public class LoginRequest {

    @NotBlank(message = "账号不能为空")
    @Size(max = 50, message = "账号不能超过 50 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码不能超过 64 个字符")
    private String password;

    /**
     * 记住我：勾选后额外签发 7 天的 refresh token（access token 仍为 12 小时）。
     * ★ 不勾选时只发 access token，12 小时后需重新登录。
     */
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
