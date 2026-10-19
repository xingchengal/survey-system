package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 操作日志 —— 表 t_operation_log。
 * 写入：aspect/LogAspect 拦截 @OperationLog 方法，正常返回后组装一条异步落库（没有业务代码手动 new）；
 * 读取：OperationLogController /page → 管理端「操作日志」页。
 * ★ 审计用途：mapper 只有 insert 与分页查询，没有 update/delete，不要补。
 * 字段来源：userId/role 取 JwtAuthFilter 注入的请求头；username 是反查 t_user 的昵称（未登录为「匿名」）。
 */
public class OperationLog {
    private Integer id;
    private Integer userId;        // 操作人 id
    private String username;       // 操作人昵称（反查 t_user，未登录为「匿名」）
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