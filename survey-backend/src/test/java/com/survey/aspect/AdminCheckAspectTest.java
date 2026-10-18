package com.survey.aspect;

import com.survey.annotation.RequireAdmin;
import com.survey.common.ForbiddenException;
import com.survey.common.Role;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AdminCheckAspect} 的纯单测 —— 钉住「角色这一半」的判定口径。
 *
 * ── 为什么单独测切面，而不是只靠 MockMvc ─────────────────
 *   MockMvc 用例证明的是「这一个端点在 HTTP 上确实拒了」；
 *   本类证明的是「拒的依据是 X-User-Role，而且只有 admin 放行」。
 *   两边的失败原因不同，缺一个都可能漏掉：
 *     · 只测切面：新端点忘挂 {@code @RequireAdmin} 照样全绿（端点覆盖由
 *       {@code EndpointAuthMatrixTest} 负责）；
 *     · 只测端点：切面哪天把 {@code Role.isAdmin} 写成 {@code role != null}，
 *       只有恰好用普通用户打管理端点才会发现。
 *
 * ── 关于"非 Web 线程直接放行" ────────────────────────────
 *   这是切面里**有意**的分支（内部直接调用 Controller 方法时拿不到请求）。
 *   它同时是一个风险点：若将来有人在异步线程里调管理方法，校验会被跳过。
 *   所以这里把它显式钉成用例 —— 行为一旦变化，测试会先响。
 */
class AdminCheckAspectTest {

    private final AdminCheckAspect aspect = new AdminCheckAspect();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void withRoleHeader(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (role != null) {
            request.addHeader("X-User-Role", role);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /**
     * 拒绝分支会打一行 warn 日志（格式是 {@code joinPoint.getSignature().toShortString()}），
     * 所以 JoinPoint 必须能给出一个签名 —— 否则切面在"记日志"这一步就 NPE 了，
     * 测试看起来像"没抛 ForbiddenException"，其实是日志语句先炸了。
     */
    private static JoinPoint joinPoint() {
        Signature signature = mock(Signature.class);
        when(signature.toShortString()).thenReturn("AnnouncementController.updateStatus()");
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        return joinPoint;
    }

    @Test
    @DisplayName("角色 = admin → 放行（不抛异常）")
    void adminRole_shouldPass() {
        withRoleHeader(Role.ADMIN);
        assertDoesNotThrow(() -> aspect.requireAdmin(joinPoint()));
    }

    @Test
    @DisplayName("角色 = user → 400「无权限」，且是 ForbiddenException（不写异常日志）")
    void userRole_shouldBeRejected() {
        withRoleHeader(Role.USER);
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> aspect.requireAdmin(joinPoint()));
        assertEquals("无权限", ex.getMessage(),
                "文案必须是「无权限」：前端与审计表都按这一个口径断言");
    }

    @Test
    @DisplayName("角色头缺失 → 拒绝（不能把 null 当成管理员）")
    void missingRole_shouldBeRejected() {
        withRoleHeader(null);
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> aspect.requireAdmin(joinPoint()));
        assertEquals("无权限", ex.getMessage());
    }

    @Test
    @DisplayName("角色头非规范写法（ADMIN / \" admin \"）→ 仍然拒绝：口径只在 Role.isAdmin 一处，切面不做归一化")
    void nonCanonicalRole_shouldStillBeRejected() {
        // 角色值来自服务端签发的 JWT claim，正常永远是 "admin"/"user"，
        // 所以不需要"大小写/空格容错"；这里钉住的是"切面不自作主张放宽"。
        withRoleHeader("ADMIN");
        assertFalse(Role.isAdmin("ADMIN"), "Role.isAdmin 是区分大小写的常量比较");
        assertThrows(ForbiddenException.class, () -> aspect.requireAdmin(joinPoint()));

        withRoleHeader(" admin ");
        assertThrows(ForbiddenException.class, () -> aspect.requireAdmin(joinPoint()),
                "带空白的角色不当成管理员 —— 若要放宽，先改 Role.isAdmin，别在切面里另写一套");
    }

    @Test
    @DisplayName("★ 非 Web 线程（拿不到请求上下文）→ 放行：这是有意分支，改它之前先想清后果")
    void nonWebThread_shouldBypass() {
        RequestContextHolder.resetRequestAttributes();
        assertDoesNotThrow(() -> aspect.requireAdmin(joinPoint()),
                "内部直接调用 Controller 方法时没有请求上下文，切面有意不做角色校验；"
                        + "若改成拒绝，异步任务里调用管理方法会被误伤 —— 改之前请先确认调用点");
    }

    /** 类级注解是"整个 Controller 都是管理接口"的写法（ErrorLogController 等在用）。 */
    @Test
    @DisplayName("@RequireAdmin 必须同时可用于 TYPE 与 METHOD")
    void annotationTarget_mustCoverTypeAndMethod() {
        Target target = RequireAdmin.class.getAnnotation(Target.class);
        assertNotNull(target, "缺少 @Target 时注解无法限定使用位置");
        assertTrue(Arrays.asList(target.value()).contains(ElementType.TYPE),
                "少了 TYPE：类级注解编译不过（AdminCheckAspect 的 @within 切点也就永远命不中）");
        assertTrue(Arrays.asList(target.value()).contains(ElementType.METHOD),
                "少了 METHOD：方法级注解失效 —— 管理端点的唯一角色闸门就没了");
    }
}
