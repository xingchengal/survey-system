package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 异常日志实体 —— 对应表 t_error_log。
 *
 * ── 谁写入它 ──────────────────────────────────────────
 *   1. config/GlobalExceptionHandler → 所有 Controller 抛出的异常
 *   2. service/AiGenerateService.generateAndSave() 的 catch → AI 调用失败时写 t_error_log
 *      （Controller 层没有任何 try/catch，AI 失败的记录由 Service 负责）
 *   两者都通过 ErrorLogService.save()（@Async）异步落库。
 *
 * ── 谁读它 ────────────────────────────────────────────
 *   ErrorLogController（page / count）→ 管理端「异常日志」页
 *
 * ── 各字段来源与限制 ──────────────────────────────────
 *   requestUri / requestMethod : 出错的请求
 *   errorType                  : 异常**类名**（如 RuntimeException、ConnectException）
 *   errorMessage               : 异常消息；AI 那条会截断到 900 字符
 *   stackTrace                 : 完整堆栈；**超过 5000 字符会截断并追加 "... [已截断]"**
 *   userId / username          : 同操作日志，username 存的是昵称
 *   ip                         : 优先取 X-Forwarded-For（可伪造，仅供参考）
 *   createTime                 : 数据库默认值填充
 *
 * ── 一个注意点 ────────────────────────────────────────
 *   GlobalExceptionHandler 会**跳过文档/静态资源路径**不记录，
 *   否则"访问文档 404"会每访问一次记一条，形成日志循环。
 */
public class ErrorLog {
    private Integer id;
    private Integer userId;          // 出错的用户 id（未登录为 null）
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