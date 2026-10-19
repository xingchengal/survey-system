package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 登录日志 —— 表 t_login_log。单独建表：登录不算写操作、每天高频，且要记「失败」这一维度（status 列）。
 * 写入：UserController.login() → LoginLogService.record()，当前只记成功；读取：管理端/个人中心「最近登录」卡片。
 * ★ 字段名必须叫 createTime：表列 create_time 靠 map-underscore-to-camel-case 映射，
 *   写成 createdTime 时 MyBatis 不报错、只是静默不填，登录时间永远为 null。
 * ★ ip 取自 WebUtils.getClientIp()，优先信 X-Forwarded-For，客户端可伪造，不得用于安全判决。
 */
public class LoginLog {

    private Integer id;
    private Integer userId;      // 谁登录的
    private String username;     // 账号（冗余存：账号改名后旧日志仍能认出是谁）
    private String role;         // 登录时的角色（冗余存）
    private String ip;           // 客户端 IP（可伪造，仅供参考）
    private String userAgent;    // UA（varchar(500)，写入前按列宽截断）
    private Integer status;      // 1=成功，0=失败；当前只写 1，失败留给「登录失败锁定」

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
