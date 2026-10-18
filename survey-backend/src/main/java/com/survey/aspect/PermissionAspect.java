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
 * 权限点切面 —— 实施 {@link RequirePermission}：**"这个人有没有这项能力"** 的统一闸门。
 *
 * ── 它解决什么 ────────────────────────────────────────
 *   {@code @RequireAdmin} 只分"管理员/非管理员"；一旦出现协作者（只读、只能看数据），
 *   就必须按**权限点**判。把判断写成注解 + 切面，新增端点只要挂注解就一定有校验，
 *   不会再出现"忘写一行 if → 横向越权"。
 *
 * ── 身份从哪来（可信吗）────────────────────────────────
 *   X-User-Id / X-User-Role 由 filter/JwtAuthFilter 解析 JWT 后**覆盖写入**，
 *   客户端伪造的同名头会被覆盖成真实值 —— 所以这里读它是可信的。
 *   ★ 前提同 AdminCheckAspect：请求经过了 JwtAuthFilter（白名单路径也会注入）。
 *
 * ── 判定顺序（顺序本身是安全口径，别调换）────────────────
 *   ① 非 Web 线程 → **放行**（与 AdminCheckAspect 完全一致：内部调用 Controller 方法时没有请求上下文）
 *   ② 注解未指定权限点 / 写了非法权限点 → **抛 IllegalStateException（→ 500 并记日志）**
 *      ★ 这是配置错误，不是"用户没权限"。若按拒绝处理（403），配错的人会以为"用户权限没配好"，
 *        排查方向从一开始就错了 —— 必须让它响。
 *   ③ 管理员 → **放行**（兜底；不给 admin 逐条配权限点，少一处漏配就少一个越权口子）
 *   ④ 未登录（没有 X-User-Id）→ **403**
 *   ⑤ 权限点**全部**命中才放行（AND 语义，见注解注释）
 *
 * ── 同一请求只查一次库 ────────────────────────────────
 *   权限点集合缓存在 request attribute 上：一个请求里可能命中多个带注解的方法
 *   （Controller 方法 + 内部调用），不缓存就是 N 次 SQL。
 *   ★ 只在一**次请求**内有效，不会跨请求失效问题；角色变更下一个请求即生效。
 *
 * ── 与 H2-3 的关系 ────────────────────────────────────
 *   本切面只管"能力"，不管"归属"。协作者能不能动**这一份**问卷由
 *   OwnershipGuard.canAccess(...) 判（作者 / 协作者 / 管理员三选一）。
 *   两者配合才是完整的授权：能力（能不能编辑）× 对象（能不能动这份）。
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
            // 切点只会在注解存在时命中；走到这里说明 Spring 的代理与运行时注解不一致。
            // 按"配置错误"直接响，不放行（失败方向必须是拒绝）。
            throw new IllegalStateException("@RequirePermission 命中但读不到注解：" + joinPoint.getSignature());
        }

        String[] required = annotation.value();
        if (required == null || required.length == 0) {
            throw new IllegalStateException("@RequirePermission 未指定权限点：" + joinPoint.getSignature());
        }
        for (String code : required) {
            if (!Permission.isValid(code)) {
                // ★ 写错权限点（如 survey:editt）是**代码 bug**：抛 500 让它在上线前被发现，
                //   而不是静默 403 —— 后者会被误判成"用户权限没配好"。
                throw new IllegalStateException("未知权限点：" + code + "（见 common/Permission）");
            }
        }

        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            // ★ 与 AdminCheckAspect 一致：非 Web 线程（内部直接调用 Controller 方法）不做权限校验。
            //   这是**有意**的分支，改之前先确认没有异步线程在调管理方法。
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
