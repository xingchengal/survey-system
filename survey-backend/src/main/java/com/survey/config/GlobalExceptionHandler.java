package com.survey.config;

import com.survey.entity.ErrorLog;
import com.survey.entity.User;
import com.survey.mapper.UserMapper;
import com.survey.service.ErrorLogService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理 + 异常日志记录
 */
@Hidden
@RestControllerAdvice(basePackages = "com.survey.controller")
public class GlobalExceptionHandler {

    @Autowired
    private ErrorLogService errorLogService;

    @Autowired
    private UserMapper userMapper;

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.BAD_REQUEST, "参数错误：" + e.getMessage());
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(NullPointerException e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误（空指针）");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统异常：" + e.getMessage());
    }

    /**
     * 保存异常日志到数据库
     */
    private void saveErrorLog(Exception e) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            HttpServletRequest request = attrs.getRequest();

            String uri = request.getRequestURI();

            // ★ 排除文档、静态资源、监控等请求（避免循环记录）
            if (uri.startsWith("/v3/")
                    || uri.startsWith("/swagger-ui")
                    || uri.startsWith("/doc.html")
                    || uri.startsWith("/webjars")
                    || uri.startsWith("/favicon")
                    || uri.startsWith("/knife4j")
                    || uri.startsWith("/actuator")
                    || uri.startsWith("/error")) {
                return;
            }

            ErrorLog log = new ErrorLog();
            log.setRequestUri(uri);
            log.setRequestMethod(request.getMethod());
            log.setErrorType(e.getClass().getSimpleName());

            // 异常消息
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = e.getClass().getName();
            }
            log.setErrorMessage(msg);

            log.setIp(getClientIp(request));

            // 堆栈信息（限制长度，防止过长）
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stackTrace = sw.toString();
            if (stackTrace.length() > 5000) {
                stackTrace = stackTrace.substring(0, 5000) + "\n... [已截断]";
            }
            log.setStackTrace(stackTrace);

            // 用户信息
            String userIdStr = request.getHeader("X-User-Id");
            if (userIdStr != null && !userIdStr.isEmpty()) {
                try {
                    Integer userId = Integer.parseInt(userIdStr);
                    log.setUserId(userId);
                    User user = userMapper.findById(userId);
                    if (user != null) {
                        log.setUsername(user.getNickname() != null ? user.getNickname() : user.getUsername());
                    }
                } catch (Exception ignored) {}
            }

            errorLogService.save(log);
        } catch (Exception ex) {
            // 记录日志失败不能影响业务
            System.err.println("记录异常日志失败：" + ex.getMessage());
        }
    }

    /**
     * 获取真实客户端 IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 统一响应结构
     */
    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", status.value());
        body.put("message", message);
        body.put("timestamp", LocalDateTime.now().toString());
        return new ResponseEntity<>(body, status);
    }
}