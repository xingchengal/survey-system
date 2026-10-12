package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 登录日志实体 —— 对应表 t_login_log。
 *
 * ── 为什么单独建表，而不是复用 t_operation_log ──────────
 *   ① 语义不同：t_operation_log 记的是"带 @OperationLog 的**写操作**"（建问卷、删用户…），
 *      登录不是写操作、而且**每天都发生**，混进去会把操作日志冲淡；
 *   ② 登录**刻意没有 @OperationLog**（避免每次登录都记一条）；
 *   ③ 登录需要"失败"这个维度：@OperationLog 是 @AfterReturning（方法正常返回才记），
 *      而登录失败是抛异常返回的，从机制上就记不到 —— 表里的 status 列正是为此预留。
 *
 * ── 谁写入它 ──────────────────────────────────────────
 *   UserController.login() → LoginLogService.record()（**只记成功**，见那边的说明）
 *
 * ── 谁读它 ────────────────────────────────────────────
 *   UserController.loginLogs() → UserProfile.vue / AdminProfile.vue 的
 *   「最近登录时间 / 登录 IP」卡片（需求 三.3）
 *
 * ── 字段说明 ──────────────────────────────────────────
 *   username / role : **冗余**存一份当时的账号与角色。理由：账号虽然当前不允许改，
 *                     但"历史可读"比"省一列"重要（改名后旧日志仍能认出是谁）。
 *   ip              : varchar(50)，与 t_operation_log.ip 同列宽；取自
 *                     util/WebUtils.getClientIp() ——★ 它优先信 X-Forwarded-For，
 *                     **客户端可伪造**，所以这个值只供用户自己核对，不得用于任何安全判决。
 *   userAgent       : varchar(500)，写入前按列宽截断（超长 insert 会直接失败）。
 *   status          : 1=成功，0=失败。★ 当前实现**只写 1** —— 失败那条留到
 *                     "登录失败锁定"时再写（建表语句的 COMMENT 里写着同一句，
 *                     改这里请一并核对该注释）。
 *
 * ── ★ 字段名必须是 createTime，不能写成 createdTime ────
 *   表列是 `create_time`，而项目开了 `map-underscore-to-camel-case`，
 *   它把 `create_time` 映射到 **createTime**（不是 createdTime）——
 *   写成 createdTime 时 MyBatis **不报错**，只是安静地不填那个字段，
 *   于是个人中心"最近登录"的时间列永远是 null。
 *   （同类：t_operation_log / t_error_log 的实体也都是 createTime。）
 */
public class LoginLog {

    private Integer id;
    private Integer userId;
    private String username;
    private String role;
    private String ip;
    private String userAgent;
    private Integer status;

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
