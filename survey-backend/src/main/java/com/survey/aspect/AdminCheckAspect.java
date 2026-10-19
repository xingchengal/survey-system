package com.survey.aspect;

import com.survey.annotation.RequireAdmin;
import com.survey.common.ForbiddenException;
import com.survey.common.Role;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 管理员权限的统一切面 —— 实施 {@link RequireAdmin} 注解。
 *
 * 角色读 JwtAuthFilter 覆盖写入的 X-User-Role，客户端伪造的同名头会被覆盖，因此可信。
 * ★ 失败抛 {@link ForbiddenException} → 403 + {success:false,message:"无权限"}，不写异常日志。
 * 切点用 @annotation（方法级）|| @within（类级），两种标注方式都生效。
 */
@Aspect
@Component
public class AdminCheckAspect {

    private static final Logger LOG = LoggerFactory.getLogger(AdminCheckAspect.class);

    @Before("@annotation(com.survey.annotation.RequireAdmin) || @within(com.survey.annotation.RequireAdmin)")
    public void requireAdmin(JoinPoint joinPoint) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            // 非 Web 线程（内部直接调用该 Controller 方法）拿不到请求，不做角色校验，避免误判成越权。
            return;
        }

        String role = attrs.getRequest().getHeader("X-User-Role");
        if (!Role.isAdmin(role)) {
            LOG.warn("越权拦截：{} 需要管理员权限，当前角色={}",
                    joinPoint.getSignature().toShortString(), role);
            throw new ForbiddenException("无权限");
        }
    }
}
