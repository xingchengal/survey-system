package com.survey.aspect;

import com.survey.annotation.RequirePermission;
import com.survey.common.ForbiddenException;
import com.survey.common.Permission;
import com.survey.common.Role;
import com.survey.mapper.RoleMapper;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link PermissionAspect} 的纯单测 —— 钉住「能力这一半」的判定口径。
 *
 * ── 和 AdminCheckAspectTest 的分工 ─────────────────────
 *   那个测"是不是管理员"；本类测"有没有权限点"，重点在三条容易写错的语义：
 *     ① admin 兜底放行**且不查库**（查了也没错，但多一次 SQL，且暗示"admin 的权限点是判定依据"）；
 *     ② 权限点写错 / 注解为空 → **500（IllegalStateException）**，绝不能静默 403 或放行；
 *     ③ 多权限点是 **AND**，不是 OR。
 *
 * ── 关于"非 Web 线程直接放行" ─────────────────────────
 *   与 AdminCheckAspect 完全一致的有意分支（内部调用 Controller 方法时没有请求上下文）。
 *   这里显式钉住：行为一旦变化，测试会先响。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionAspectTest {

    @Mock
    private RoleMapper roleMapper;

    @InjectMocks
    private PermissionAspect aspect;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    // ==================== 夹具 ====================

    /** 方法级注解的宿主 */
    static class MethodFixtures {
        @RequirePermission(Permission.USER_MANAGE)
        public void manage() { }

        @RequirePermission(Permission.SURVEY_EDIT)
        public void edit() { }

        @RequirePermission({Permission.SURVEY_EDIT, Permission.SURVEY_DATA})
        public void editAndSeeData() { }

        @RequirePermission("survey:editt")
        public void typoPermission() { }

        @RequirePermission({})
        public void emptyPermission() { }
    }

    /** 类级注解的宿主（对应"整个 Controller 都要这个权限"的写法） */
    @RequirePermission(Permission.USER_MANAGE)
    static class ClassAnnotated {
        public void anyMethod() { }
    }

    private static JoinPoint joinPointOf(String methodName) throws NoSuchMethodException {
        Method method = MethodFixtures.class.getMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.toShortString()).thenReturn("MethodFixtures." + methodName + "()");
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(new MethodFixtures());
        return joinPoint;
    }

    private static JoinPoint classAnnotatedJoinPoint() throws NoSuchMethodException {
        Method method = ClassAnnotated.class.getMethod("anyMethod");
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(signature.toShortString()).thenReturn("ClassAnnotated.anyMethod()");
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(new ClassAnnotated());
        return joinPoint;
    }

    /** 构造一次"已过鉴权"的请求：身份头由 JwtAuthFilter 注入，这里直接给出 */
    private static MockHttpServletRequest withIdentity(String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userId != null) {
            request.addHeader("X-User-Id", userId);
        }
        if (role != null) {
            request.addHeader("X-User-Role", role);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("★ 管理员兜底放行，且**一次库都不查**（admin 不靠权限点判定）")
    void adminBypassesWithoutQuery() throws Exception {
        withIdentity("1", Role.ADMIN);
        assertDoesNotThrow(() -> aspect.requirePermission(joinPointOf("manage")));
        verifyNoInteractions(roleMapper);
    }

    @Test
    @DisplayName("有该权限点 → 放行")
    void userWithPermissionPasses() throws Exception {
        withIdentity("7", Role.USER);
        when(roleMapper.findPermissionCodesByUserId(7)).thenReturn(List.of(Permission.USER_MANAGE));
        assertDoesNotThrow(() -> aspect.requirePermission(joinPointOf("manage")));
    }

    @Test
    @DisplayName("★ 没有该权限点 → 403（ForbiddenException，文案「无权限」）")
    void userWithoutPermissionIsForbidden() throws Exception {
        withIdentity("7", Role.USER);
        when(roleMapper.findPermissionCodesByUserId(7)).thenReturn(List.of(Permission.SURVEY_EDIT));
        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> aspect.requirePermission(joinPointOf("manage")));
        assertEquals("无权限", ex.getMessage());
    }

    @Test
    @DisplayName("★ 多权限点是 AND：只满足一个也必须拒绝")
    void multiplePermissionsRequireAll() throws Exception {
        withIdentity("7", Role.USER);
        when(roleMapper.findPermissionCodesByUserId(7)).thenReturn(List.of(Permission.SURVEY_EDIT));
        assertThrows(ForbiddenException.class, () -> aspect.requirePermission(joinPointOf("editAndSeeData")));

        // ★ 换一次请求再验放行：权限缓存是**按请求**存的（withIdentity 会新建一份请求上下文），
        //   同一个请求里改库也不会让缓存失效 —— 这正是"权限变更下一个请求生效"的设计。
        withIdentity("7", Role.USER);
        when(roleMapper.findPermissionCodesByUserId(7))
                .thenReturn(List.of(Permission.SURVEY_EDIT, Permission.SURVEY_DATA));
        assertDoesNotThrow(() -> aspect.requirePermission(joinPointOf("editAndSeeData")));
    }

    @Test
    @DisplayName("★ 匿名（没有 X-User-Id）→ 403，且不查库")
    void anonymousIsForbidden() throws Exception {
        withIdentity(null, null);
        assertThrows(ForbiddenException.class, () -> aspect.requirePermission(joinPointOf("manage")));
        verifyNoInteractions(roleMapper);
    }

    @Test
    @DisplayName("★ 非 Web 线程（拿不到请求上下文）→ 放行：与 AdminCheckAspect 同一条有意分支")
    void nonWebThreadBypasses() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        assertDoesNotThrow(() -> aspect.requirePermission(joinPointOf("manage")),
                "内部调用 Controller 方法时没有请求上下文；若改成拒绝，异步任务里调管理方法会被误伤");
    }

    @Test
    @DisplayName("类级注解同样生效（@within 切点）")
    void classLevelAnnotationWorks() throws Exception {
        withIdentity("7", Role.USER);
        when(roleMapper.findPermissionCodesByUserId(7)).thenReturn(List.of());
        assertThrows(ForbiddenException.class, () -> aspect.requirePermission(classAnnotatedJoinPoint()));
    }

    @Test
    @DisplayName("★★ 权限点写错 → IllegalStateException（500），不是 403：这是代码 bug，不能被当成「用户没权限」")
    void unknownPermissionIsProgrammingError() throws Exception {
        withIdentity("7", Role.USER);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> aspect.requirePermission(joinPointOf("typoPermission")));
        assertTrue(ex.getMessage().contains("未知权限点"), "实际文案：" + ex.getMessage());
        verifyNoInteractions(roleMapper);
    }

    @Test
    @DisplayName("★ 注解没写权限点 → IllegalStateException（宁可 500 也不放行）")
    void emptyPermissionIsProgrammingError() throws Exception {
        withIdentity("7", Role.USER);
        assertThrows(IllegalStateException.class,
                () -> aspect.requirePermission(joinPointOf("emptyPermission")));
    }

    @Test
    @DisplayName("★ 同一请求里多次校验只查一次库（权限点缓存在 request attribute 上）")
    void permissionsAreCachedPerRequest() throws Exception {
        withIdentity("7", Role.USER);
        when(roleMapper.findPermissionCodesByUserId(7)).thenReturn(List.of(Permission.USER_MANAGE));

        aspect.requirePermission(joinPointOf("manage"));
        aspect.requirePermission(joinPointOf("manage"));

        verify(roleMapper, times(1)).findPermissionCodesByUserId(7);
    }
}
