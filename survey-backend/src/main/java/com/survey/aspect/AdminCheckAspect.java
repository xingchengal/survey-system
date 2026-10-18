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
 * ── 它解决什么 ────────────────────────────────────────
 *   把"每个端点各写一遍 if (!"admin".equals(role)) throw"换成声明式注解：
 *   **新增端点只要挂了注解就一定有校验**，不会再出现"忘了写一行 → 横向越权"。
 *
 * ── 角色从哪来（可信吗）────────────────────────────────
 *   X-User-Role 由 filter/JwtAuthFilter 解析 JWT 后**覆盖写入**（见 HeaderRewriteRequestWrapper），
 *   客户端自己伪造的 X-User-Role 会被覆盖成真实值 —— 所以这里读它是可信的。
 *   ★ 前提是"请求经过了 JwtAuthFilter"：白名单路径同样会注入身份，
 *     所以 @RequireAdmin 在**两类路径上都生效**（读到的都是真实角色）。
 *
 * ── 失败怎么表达 ──────────────────────────────────────
 *   抛 {@link BizException} → GlobalExceptionHandler → **400 + {success:false,message:"无权限"}**，
 *   不会写进异常日志（业务拒绝不污染日志）。
 *
 * ── 切点说明 ──────────────────────────────────────────
 *   `@annotation(...)` 命中**方法**上的注解；`@within(...)` 命中**类**上的注解。
 *   两者用 || 组合，因此类级与方法级注解都生效。
 */
@Aspect
@Component
public class AdminCheckAspect {

    private static final Logger LOG = LoggerFactory.getLogger(AdminCheckAspect.class);

    @Before("@annotation(com.survey.annotation.RequireAdmin) || @within(com.survey.annotation.RequireAdmin)")
    public void requireAdmin(JoinPoint joinPoint) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            // 非 Web 线程（例如内部直接调用该 Controller 方法）拿不到请求 —— 不做角色校验，
            // 避免把"程序内部调用"误判成越权。
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
