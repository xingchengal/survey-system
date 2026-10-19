package com.survey.aspect;

import com.survey.annotation.RequirePermission;
import com.survey.common.ForbiddenException;
import com.survey.common.Permission;
import com.survey.common.Role;
import com.survey.mapper.RoleMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限点切面 —— 实施 {@link RequirePermission}：判「这个人有没有这项能力」的统一闸门。
 *
 * ★ 判定顺序是安全口径，别调换：非 Web 线程放行 → 配置错误抛 500 → admin 兜底放行 → 未登录 403 → 权限点全部命中才放行。
 * 注解没写权限点或写了非法值时抛 IllegalStateException（500），不能按 403 处理：那是代码 bug。
 * 只管「能力」；「能不能动这一份问卷」由 OwnershipGuard.canAccess 判，两者合起来才是完整授权。
 * 权限点集合缓存在 request attribute：一次请求最多查一次库，随请求结束释放。
 */
@Aspect
@Component
public class PermissionAspect {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionAspect.class);

    /** 请求级权限缓存（挂在 HttpServletRequest 上，随请求结束释放） */
    private static final String PERMISSION_CACHE_ATTR = "com.survey.aspect.PermissionAspect.permissions";

    @Autowired
    private RoleMapper roleMapper;

    @Before("@annotation(com.survey.annotation.RequirePermission) "
            + "|| @within(com.survey.annotation.RequirePermission)")
    public void requirePermission(JoinPoint joinPoint) {
        RequirePermission annotation = findAnnotation(joinPoint);
        if (annotation == null) {
            // 切点只会在注解存在时命中；走到这里说明代理与运行时注解不一致，按配置错误直接响。
            throw new IllegalStateException("@RequirePermission 命中但读不到注解：" + joinPoint.getSignature());
        }

        String[] required = annotation.value();
        if (required == null || required.length == 0) {
            throw new IllegalStateException("@RequirePermission 未指定权限点：" + joinPoint.getSignature());
        }
        for (String code : required) {
            if (!Permission.isValid(code)) {
                // ★ 写错权限点（如 survey:editt）是代码 bug：抛 500 让它上线前被发现，而不是静默 403。
                throw new IllegalStateException("未知权限点：" + code + "（见 common/Permission）");
            }
        }

        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            // 非 Web 线程（内部直接调用 Controller 方法）不做权限校验，与 AdminCheckAspect 一致。
            // 改之前先确认没有异步线程在调管理方法。
            return;
        }

        HttpServletRequest request = attrs.getRequest();
        if (Role.isAdmin(request.getHeader("X-User-Role"))) {
            return;   // 管理员兜底放行
        }

        String userIdHeader = request.getHeader("X-User-Id");
        Integer userId = parseUserId(userIdHeader);
        if (userId == null) {
            LOG.warn("越权拦截：{} 需要 {}，但请求没有有效身份（X-User-Id={}）",
                    joinPoint.getSignature().toShortString(), String.join(",", required), userIdHeader);
            throw new ForbiddenException("无权限");
        }

        Set<String> owned = permissionsOf(request, userId);
        for (String code : required) {
            if (!owned.contains(code)) {
                LOG.warn("越权拦截：{} 需要 {}，userId={} 实际拥有 {}",
                        joinPoint.getSignature().toShortString(), String.join(",", required), userId, owned);
                throw new ForbiddenException("无权限");
            }
        }
    }

    /** 取注解：先看方法级（@annotation），再看类级（@within）。 */
    private RequirePermission findAnnotation(JoinPoint joinPoint) {
        if (joinPoint.getSignature() instanceof MethodSignature ms) {
            Method method = ms.getMethod();
            RequirePermission onMethod = AnnotationUtils.findAnnotation(method, RequirePermission.class);
            if (onMethod != null) {
                return onMethod;
            }
        }
        Object target = joinPoint.getTarget();
        if (target != null) {
            return AnnotationUtils.findAnnotation(target.getClass(), RequirePermission.class);
        }
        return null;
    }

    /** 该用户在该请求内的权限点集合（request 级缓存，避免一次请求查多次库） */
    private Set<String> permissionsOf(HttpServletRequest request, Integer userId) {
        Object cached = request.getAttribute(PERMISSION_CACHE_ATTR);
        if (cached instanceof Set<?> cachedSet) {
            @SuppressWarnings("unchecked")
            Set<String> typed = (Set<String>) cachedSet;
            return typed;
        }
        List<String> codes = roleMapper.findPermissionCodesByUserId(userId);
        Set<String> result = codes == null || codes.isEmpty() ? Set.of() : new HashSet<>(codes);
        request.setAttribute(PERMISSION_CACHE_ATTR, result);
        return result;
    }

    /** 只接受"纯数字"的用户 id；非法值一律当匿名（与 JwtAuthFilter 写空串的行为兼容）。 */
    private Integer parseUserId(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
