package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 异常日志 —— 表 t_error_log。
 * 写入：GlobalExceptionHandler（Controller 异常）与 AiGenerateService 的 catch（AI 失败），
 * 均经 ErrorLogService.save()（@Async）异步落库；读取：管理端「异常日志」页。
 * ★ stackTrace 超 5000 字符、AI 那条 errorMessage 超 900 字符会截断并追加「... [已截断]」。
 * ★ GlobalExceptionHandler 跳过文档/静态资源路径，否则每次文档 404 都记一条，形成日志循环。
 */
public class ErrorLog {
    private Integer id;
    private Integer userId;          // 出错用户 id（未登录 null）
    private String username;         // 用户昵称
    private String requestUri;       // 请求路径
    private String requestMethod;    // GET / POST / PUT / DELETE
    private String errorType;        // 异常类名
    private String errorMessage;     // 异常消息（AI 那条截断到 900 字符）
    private String stackTrace;       // 堆栈（超 5000 字符截断）
    private String ip;               // 来源 IP（可伪造，仅供参考）

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getRequestUri() { return requestUri; }
    public void setRequestUri(String requestUri) { this.requestUri = requestUri; }

    public String getRequestMethod() { return requestMethod; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}