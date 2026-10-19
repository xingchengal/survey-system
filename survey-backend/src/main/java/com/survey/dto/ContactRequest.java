package com.survey.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 联系方式修改请求体 —— phone / email 均可空，空白视为"清空该项"。
 * phone 非空须国内 11 位手机号；email 非空须通用邮箱格式（与前端个人资料页的 rules 一致）。
 * ★ 20 / 100 分别等于 t_user.phone / t_user.email 的列宽：Java 放行而 MySQL 拒绝，用户看到的是 500。
 */
public class ContactRequest {

    /** 手机号：可空（含纯空白）；非空须国内 11 位。★ 正则放行纯空白：@Valid 在进方法体前执行，Controller 里 trim 救不回来 */
    @Pattern(regexp = "^\\s*$|^1[3-9]\\d{9}$", message = "手机号格式不正确（应为 11 位国内手机号）")
    @Size(max = 20, message = "手机号不能超过 20 个字符")
    private String phone;

    /** 邮箱：可空（含纯空白）；非空须通用邮箱格式。上限 100 = t_user.email 列宽 */
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
