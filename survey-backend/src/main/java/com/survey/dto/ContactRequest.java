package com.survey.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 联系方式修改请求体 —— 手机号、邮箱支持查看与编辑，邮箱带格式校验。
 *
 * ── 为什么用 DTO（而不是在 Service 里手写 if）──
 *   校验按"迁一个删一处"拆分：
 *     · **格式**（手机号 11 位、邮箱正则、长度上限）搬到本 DTO 的注解上 ——
 *       —— 新的入参校验统一用 DTO + @Valid；
 *     · **业务**（用户是否存在、空白归一成"清空"）仍留在 Service ——
 *       它们不是格式问题（详见 UserService.updateContact 的注释）。
 *   ★ 迁移时必须**同批删掉** Service 里被搬走的那两个格式校验，
 *     否则同一件事两处判定，迟早两边口径不一致。
 *
 * ── 字段口径（前端 src/views/*Profile.vue 的 rules 必须与此一致）──
 *   · phone：可空（空串=清空该项）；非空时必须是国内 11 位手机号。
 *   · email：可空；非空时是通用邮箱格式。★ 上限 100 = t_user.email 的列宽
 *     （Java 校验上限必须等于数据库列宽，否则超长会先过校验、再被 MySQL 拒绝 → 用户看到 500）。
 *     注意 phone 的 20 也是列宽（t_user.phone = varchar(20)）。
 *
 * ── ★★ 两个正则都写成 `^\s*$|...`，不能写成 `^$|...` ────
 *   `^$|^1[3-9]\d{9}package com.survey.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 联系方式修改请求体 —— 手机号、邮箱支持查看与编辑，邮箱带格式校验。
 *
 * ── 为什么用 DTO（而不是在 Service 里手写 if）──
 *   校验按"迁一个删一处"拆分：
 *     · **格式**（手机号 11 位、邮箱正则、长度上限）搬到本 DTO 的注解上 ——
 *       —— 新的入参校验统一用 DTO + @Valid；
 *     · **业务**（用户是否存在、空白归一成"清空"）仍留在 Service ——
 *       它们不是格式问题（详见 UserService.updateContact 的注释）。
 *   ★ 迁移时必须**同批删掉** Service 里被搬走的那两个格式校验，
 *     否则同一件事两处判定，迟早两边口径不一致。
 *
 * ── 字段口径（前端 src/views/*Profile.vue 的 rules 必须与此一致）──
 *   · phone：可空（空串=清空该项）；非空时必须是国内 11 位手机号。
 *   · email：可空；非空时是通用邮箱格式。★ 上限 100 = t_user.email 的列宽
 *     （约定 10：Java 校验上限必须等于数据库列宽，否则超长会先过校验、再被 MySQL 拒绝 → 用户看到 500）。
 *     注意 phone 的 20 也是列宽（t_user.phone = varchar(20)）。
 *
 只放行**严格空串**。而用户在输入框里
 *   "删干净"时很容易留下一个空格（尤其在手机号这种纯数字框里粘贴后删一半），
 *   于是提交清空得到一个 400「手机号格式不正确」——
 *   用户明确就是想留空，却被格式校验拦住，而且文案还在说"格式不正确"，谁也想不到原因。
 *   ★ 为什么不在 Controller 里先 trim 再校验：`@Valid` 发生在**进入方法体之前**，
 *     方法体里的 trim 救不了它。要么改正则、要么写自定义约束注解 —— 前者代价最小。
 *   ★ 真正的 trim 与"空白→NULL"归一仍在 UserService.updateContact（blankToNull）+
 *     UserMapper 的 NULLIF：注解这一层只负责"别把用户想留空的输入拦下来"。
 *
 * ── 前端的角色 ────────────────────────────────────────
 *   前端也做同样校验（页内红字，不用弹窗，需求 三.2），但那只是**体验**；
 *   真正的判定在这里 —— 需求 四.1 明确要求"所有校验逻辑后端必须二次实现"。
 */
public class ContactRequest {

    /**
     * 手机号：可空（含纯空白）；非空时国内 11 位。
     * ★ 20 = t_user.phone 列宽（约定 10）；`\s*` 放行"用户以为删干净了"的输入，见类注释。
     */
    @Pattern(regexp = "^\\s*$|^1[3-9]\\d{9}$", message = "手机号格式不正确（应为 11 位国内手机号）")
    @Size(max = 20, message = "手机号不能超过 20 个字符")
    private String phone;

    /**
     * 邮箱：可空（含纯空白）；非空时校验格式。
     * ★ 100 = t_user.email 列宽（约定 10）。
     */
    @Pattern(regexp = "^\\s*$|^[\\w.-]+@[\\w.-]+\\.[A-Za-z]{2,}$", message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱不能超过 100 个字符")
    private String email;

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
