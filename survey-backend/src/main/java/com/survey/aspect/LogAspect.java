package com.survey.aspect;

import com.survey.annotation.OperationLog;
import com.survey.entity.User;
import com.survey.mapper.UserMapper;
import com.survey.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

@Aspect
@Component
public class LogAspect {

    @Autowired
    private OperationLogService operationLogService;
    @Autowired
    private UserMapper userMapper;

    @Pointcut("@annotation(com.survey.annotation.OperationLog)")
    public void logPointcut() {}

    @AfterReturning(pointcut = "logPointcut()")
    public void doAfterReturning(JoinPoint joinPoint) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            OperationLog annotation = method.getAnnotation(OperationLog.class);
            String operationDesc = annotation != null ? annotation.value() : "";

            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) return;
            HttpServletRequest request = attributes.getRequest();

            String userIdStr = request.getHeader("X-User-Id");
            String role = request.getHeader("X-User-Role");
            String ip = getClientIp(request);

            com.survey.entity.OperationLog log = new com.survey.entity.OperationLog();
            String username = "匿名";

            // ★ 修复问题 4：parseInt 异常保护
            if (userIdStr != null && !userIdStr.isEmpty()) {
                try {
                    Integer userId = Integer.parseInt(userIdStr);
                    log.setUserId(userId);
                    User user = userMapper.findById(userId);
                    if (user != null) {
                        username = user.getNickname() != null ? user.getNickname() : user.getUsername();
                    }
                } catch (NumberFormatException e) {
                    // 非法 userId 格式，忽略用户信息
                    System.err.println("非法 X-User-Id：" + userIdStr);
                }
            }

            log.setRole(role);
            log.setOperation(operationDesc);
            log.setMethod(signature.getDeclaringType().getSimpleName() + "." + method.getName());
            log.setIp(ip);
            log.setUsername(username);

            operationLogService.save(log);
        } catch (Exception e) {
            System.err.println("保存操作日志失败：" + e.getMessage());
        }
    }

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
}