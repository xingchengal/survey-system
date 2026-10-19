package com.survey.aspect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.survey.annotation.OperationLog;
import com.survey.entity.User;
import com.survey.service.OperationLogService;
import com.survey.service.UserService;
import com.survey.util.WebUtils;
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

/**
 * 操作日志切面：把标了 @OperationLog 的方法自动记进 t_operation_log。
 *
 * @AfterReturning 只在方法正常返回后记录，抛异常的操作不会被记成「成功」。
 * 日志内容：注解 value、类名.方法名、用户、角色、IP；用户昵称要按 X-User-Id 查 t_user。
 * ★ 整段包在 try-catch 里：记日志失败绝不能影响业务，只打印一行错误。
 */
@Aspect
@Component
public class LogAspect {

    /** 统一用 SLF4J 记录：System.out/err/printStackTrace 在生产会直接丢失。 */
    private static final Logger LOG = LoggerFactory.getLogger(LogAspect.class);

    @Autowired
    private OperationLogService operationLogService;
    /** 注入 UserService 而非 UserMapper：与 GlobalExceptionHandler 同口径，也便于以后给「取用户」加缓存。 */
    @Autowired
    private UserService userService;

    /** 切点：所有标注了 @OperationLog 的方法（本项目里都是 Controller 的写操作）。 */
    @Pointcut("@annotation(com.survey.annotation.OperationLog)")
    public void logPointcut() {}

    /**
     * 方法正常返回后执行：组装一条 OperationLog 并异步落库。
     * @param joinPoint 连接点，用来取方法签名与注解
     */
    @AfterReturning(pointcut = "logPointcut()")
    public void doAfterReturning(JoinPoint joinPoint) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            OperationLog annotation = method.getAnnotation(OperationLog.class);
            String operationDesc = annotation != null ? annotation.value() : "";

            // 取当前请求（非 Web 线程调用时为 null，直接放弃记录）
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) return;
            HttpServletRequest request = attributes.getRequest();

            String userIdStr = request.getHeader("X-User-Id");   // 由 JwtAuthFilter 写入
            String role = request.getHeader("X-User-Role");      // 由 JwtAuthFilter 写入
            String ip = WebUtils.getClientIp(request);

            com.survey.entity.OperationLog log = new com.survey.entity.OperationLog();
            String username = "匿名";   // 未登录（如注册接口）时显示"匿名"

            // parseInt 异常保护：请求头不是数字也不会让整个切面挂掉
            if (userIdStr != null && !userIdStr.isEmpty()) {
                try {
                    Integer userId = Integer.parseInt(userIdStr);
                    log.setUserId(userId);
                    User user = userService.getById(userId);
                    if (user != null) {
                        username = user.getNickname() != null ? user.getNickname() : user.getUsername();
                    }
                } catch (NumberFormatException e) {
                    // 非法 userId 格式，忽略用户信息
                    LOG.warn("非法 X-User-Id：{}", userIdStr);
                }
            }

            log.setRole(role);
            log.setOperation(operationDesc);
            log.setMethod(signature.getDeclaringType().getSimpleName() + "." + method.getName());
            log.setIp(ip);
            log.setUsername(username);

            operationLogService.save(log);
        } catch (Exception e) {
            // ★ 兜底：记日志失败只打印，绝不影响业务
            LOG.error("保存操作日志失败：{}", e.getMessage(), e);
        }
    }
}
