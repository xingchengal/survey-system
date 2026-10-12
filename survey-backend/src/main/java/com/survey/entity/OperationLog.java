package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 操作日志实体 —— 对应表 t_operation_log。
 *
 * ── 谁写入它 ──────────────────────────────────────────
 *   aspect/LogAspect.java：拦截标了 @OperationLog 的方法，在方法正常返回后组装一条并异步落库。
 *   → 所以这个类**没有业务代码手动 new 它**，除了 LogAspect。
 *
 * ── 谁读它 ────────────────────────────────────────────
 *   OperationLogController /page → OperationLog.vue（管理员「操作日志」页）
 *
 * ── 各字段来源 ────────────────────────────────────────
 *   userId / role : 请求头 X-User-Id / X-User-Role（由 JwtAuthFilter 写入，可信）
 *   username      : 拿 userId 反查 t_user 得到的**昵称**（不是登录名），未登录时为"匿名"
 *   operation     : @OperationLog("...") 注解里的描述，如"发布问卷"
 *   method        : 类名.方法名，如 SurveyController.publish
 *   ip            : 优先取 X-Forwarded-For（可被伪造，仅供参考）
 *   createTime    : 数据库默认值填充，Java 侧不传
 *
 * ── 不可修改 ──────────────────────────────────────────
 *   审计用途：mapper 只有 insert 和分页查询，没有 update/delete。
 */
public class OperationLog {
    private Integer id;
    private Integer userId;        // 操作人 id
    private String username;       // 操作人昵称（未登录为"匿名"）
    private String role;           // 操作时的角色 admin / user
    private String operation;      // 操作描述，来自 @OperationLog 注解
    private String method;         // 类名.方法名
    private String ip;             // 来源 IP（可被请求头伪造，仅供参考）

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

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}