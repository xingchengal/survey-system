package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.common.Role;
import com.survey.entity.Survey;
import com.survey.mapper.SurveyCollaboratorMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link SurveyAccessService} 的单元测试 —— 它把"**能力 × 对象**"里需要查库的那一半接起来：
 * 查出协作者角色，再交给 OwnershipGuard 的纯函数判。
 *
 * ── 为什么单独测它，而不是只测 OwnershipGuard ─────────────
 *   OwnershipGuard 证明的是"给定角色码时的判定"；本类证明的是：
 *     ① **该不该查库**（作者/管理员必须不查 —— 这是热路径上的性能口径）；
 *     ② 查到的脏数据（未知角色码）不会被当成放行；
 *     ③ 拒绝时的**文案**与改造前一致（前端与矩阵测试都按它断言）。
 *
 * ── 关于 strictness ───────────────────────────────────
 *   LENIENT：本类有些用例只需要"不抛异常"，不会触发所有 stub；用严格模式会误报未使用 stub。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SurveyAccessServiceTest {

    @Mock
    private SurveyCollaboratorMapper collaboratorMapper;

    @Mock
    private com.survey.mapper.RoleMapper roleMapper;

    @InjectMocks
    private SurveyAccessService accessService;

    private static final Integer OWNER = 7;
    private static final Integer STRANGER = 8;

    private static Survey survey(Integer ownerId) {
        Survey s = new Survey();
        s.setId(100);
        s.setUserId(ownerId);
        s.setStatus(1);
        return s;
    }

    @Test
    @DisplayName("★ 作者：不查库（作者全权），任何权限都通过")
    void ownerSkipsDatabase() {
        Survey s = survey(OWNER);
        assertTrue(accessService.hasPermission(s, OWNER, Role.USER, Permission.SURVEY_DELETE));
        assertDoesNotThrow(() ->
                accessService.requirePermission(s, OWNER, Role.USER, Permission.SURVEY_EDIT, "修改"));
        verifyNoInteractions(collaboratorMapper);
    }

    @Test
    @DisplayName("★ 管理员：不查库，任何权限都通过")
    void adminSkipsDatabase() {
        Survey s = survey(OWNER);
        assertTrue(accessService.hasPermission(s, STRANGER, Role.ADMIN, Permission.SURVEY_DELETE));
        verifyNoInteractions(collaboratorMapper);
    }

    @Test
    @DisplayName("★ 匿名：不查库，且一律拒绝（不能因为「查不到协作者」就当成放行）")
    void anonymousIsRejectedWithoutQuery() {
        Survey s = survey(OWNER);
        assertFalse(accessService.hasPermission(s, null, null, Permission.SURVEY_DATA));
        verifyNoInteractions(collaboratorMapper);
    }

    @Test
    @DisplayName("★ 陌生人（不是协作者）：查一次库 → false / 400，文案与旧口径一致")
    void strangerIsRejected() {
        Survey s = survey(OWNER);
        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn(null);

        assertFalse(accessService.hasPermission(s, STRANGER, Role.USER, Permission.SURVEY_EDIT));

        BizException ex = assertThrows(BizException.class, () ->
                accessService.requirePermission(s, STRANGER, Role.USER, Permission.SURVEY_EDIT, "修改"));
        assertEquals("无权限修改他人问卷", ex.getMessage());
        verify(collaboratorMapper, times(2)).findCollaboratorRole(100, STRANGER);
    }

    @Test
    @DisplayName("★ editor 协作者：能改（survey:edit）、能看数据，但**不能删**（survey:delete）")
    void editorCollaboratorCanEditButNotDelete() {
        Survey s = survey(OWNER);
        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn("editor");

        assertTrue(accessService.hasPermission(s, STRANGER, Role.USER, Permission.SURVEY_EDIT));
        assertTrue(accessService.hasPermission(s, STRANGER, Role.USER, Permission.SURVEY_DATA));
        assertFalse(accessService.hasPermission(s, STRANGER, Role.USER, Permission.SURVEY_DELETE));

        BizException ex = assertThrows(BizException.class, () ->
                accessService.requirePermission(s, STRANGER, Role.USER, Permission.SURVEY_DELETE, "操作"));
        assertEquals("无权限操作他人问卷", ex.getMessage());
    }

    @Test
    @DisplayName("★ analyst 协作者：只能看数据，不能改")
    void analystCollaboratorOnlySeesData() {
        Survey s = survey(OWNER);
        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn("analyst");

        assertTrue(accessService.hasPermission(s, STRANGER, Role.USER, Permission.SURVEY_DATA));
        assertFalse(accessService.hasPermission(s, STRANGER, Role.USER, Permission.SURVEY_EDIT));
        assertThrows(BizException.class, () ->
                accessService.requirePermission(s, STRANGER, Role.USER, Permission.SURVEY_EDIT, "修改"));
    }

    @Test
    @DisplayName("★ 库里的脏角色码（未知值）→ 不放行")
    void unknownRoleCodeInDatabaseIsDenied() {
        Survey s = survey(OWNER);
        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn("superuser");
        assertFalse(accessService.hasPermission(s, STRANGER, Role.USER, Permission.SURVEY_EDIT));
    }

    @Test
    @DisplayName("★ requireVisible：协作者能读草稿；陌生人读草稿仍按原文案拒绝")
    void collaboratorCanViewDraft() {
        Survey draft = survey(OWNER);
        draft.setStatus(0);
        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn("viewer");

        assertDoesNotThrow(() ->
                accessService.requireVisible(draft, STRANGER, Role.USER, "无权查看他人未发布的问卷"));

        // 换个人（不是协作者）
        reset(collaboratorMapper);
        when(collaboratorMapper.findCollaboratorRole(100, 99)).thenReturn(null);
        assertEquals("无权查看他人未发布的问卷", assertThrows(BizException.class, () ->
                accessService.requireVisible(draft, 99, Role.USER, "无权查看他人未发布的问卷")).getMessage());
    }

    @Test
    @DisplayName("★ 问卷 id 为 null（未落库的内存对象）→ 不查库，按无权限处理")
    void unsavedSurveyIsDeniedWithoutQuery() {
        Survey s = survey(OWNER);
        s.setId(null);
        assertFalse(accessService.hasPermission(s, STRANGER, Role.USER, Permission.SURVEY_EDIT));
        verifyNoInteractions(collaboratorMapper);
    }
    // ==================== permissionsFor（给前端做按钮显隐）====================

    @Test
    @DisplayName("★ permissionsFor：作者 / 管理员 = 四项全有（且不查库）")
    void permissionsForOwnerAndAdmin() {
        Survey s = survey(OWNER);
        List<String> expected = List.of(Permission.SURVEY_EDIT, Permission.SURVEY_PUBLISH,
                Permission.SURVEY_DATA, Permission.SURVEY_DELETE);
        assertEquals(expected, accessService.permissionsFor(s, OWNER, Role.USER));
        assertEquals(expected, accessService.permissionsFor(s, STRANGER, Role.ADMIN));
        verifyNoInteractions(collaboratorMapper);
    }

    @Test
    @DisplayName("★ permissionsFor：editor=编辑+数据、analyst=仅数据、viewer=空、陌生人=空")
    void permissionsForCollaborators() {
        Survey s = survey(OWNER);

        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn("editor");
        assertEquals(List.of(Permission.SURVEY_EDIT, Permission.SURVEY_DATA),
                accessService.permissionsFor(s, STRANGER, Role.USER));

        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn("analyst");
        assertEquals(List.of(Permission.SURVEY_DATA),
                accessService.permissionsFor(s, STRANGER, Role.USER));

        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn("viewer");
        assertEquals(List.of(), accessService.permissionsFor(s, STRANGER, Role.USER));

        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn(null);
        assertEquals(List.of(), accessService.permissionsFor(s, STRANGER, Role.USER));
    }

    @Test
    @DisplayName("permissionsFor：匿名 / null 问卷 → 空列表，不抛异常、不查库")
    void permissionsForAnonymous() {
        assertEquals(List.of(), accessService.permissionsFor(null, OWNER, Role.USER));
        assertEquals(List.of(), accessService.permissionsFor(survey(OWNER), null, null));
        verifyNoInteractions(collaboratorMapper);
    }

    // ==================== 次级管理员（subadmin）的模块级数据范围 ====================

    @Test
    @DisplayName("★ subadmin + survey:manage：任何问卷都放行，且不查协作者表")
    void subAdminWithSurveyManageCanAccessAnySurvey() {
        Survey s = survey(OWNER);
        when(roleMapper.findPermissionCodesByUserId(STRANGER))
                .thenReturn(List.of(Permission.SURVEY_MANAGE));

        assertTrue(accessService.hasPermission(s, STRANGER, Role.SUB_ADMIN, Permission.SURVEY_EDIT));
        assertDoesNotThrow(() ->
                accessService.requirePermission(s, STRANGER, Role.SUB_ADMIN, Permission.SURVEY_DELETE, "删除"));
        assertDoesNotThrow(() ->
                accessService.requireOwnerOrStaff(s, STRANGER, Role.SUB_ADMIN, "复制"));
        assertEquals(List.of(Permission.SURVEY_EDIT, Permission.SURVEY_PUBLISH,
                        Permission.SURVEY_DATA, Permission.SURVEY_DELETE),
                accessService.permissionsFor(s, STRANGER, Role.SUB_ADMIN));
        verifyNoInteractions(collaboratorMapper);
    }

    @Test
    @DisplayName("★ subadmin + template:manage：只对 isTemplate=1 放行，用户问卷仍按普通口径拒绝")
    void subAdminWithTemplateManageOnlyCoversTemplates() {
        Survey template = survey(OWNER);
        template.setIsTemplate(1);
        Survey normal = survey(OWNER);
        normal.setIsTemplate(0);
        when(roleMapper.findPermissionCodesByUserId(STRANGER))
                .thenReturn(List.of(Permission.TEMPLATE_MANAGE));
        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn(null);

        assertTrue(accessService.hasPermission(template, STRANGER, Role.SUB_ADMIN, Permission.SURVEY_EDIT));
        assertNotNull(accessService.permissionsFor(template, STRANGER, Role.SUB_ADMIN));
        // 普通问卷：模板权限不放行，且不是协作者 → 拒绝
        assertFalse(accessService.hasPermission(normal, STRANGER, Role.SUB_ADMIN, Permission.SURVEY_EDIT));
        assertThrows(BizException.class, () ->
                accessService.requireOwnerOrStaff(normal, STRANGER, Role.SUB_ADMIN, "复制"));
    }

    @Test
    @DisplayName("★ subadmin 只有无关权限（feedback:handle）：不能碰问卷")
    void subAdminWithoutSurveyPermissionIsDenied() {
        Survey s = survey(OWNER);
        when(roleMapper.findPermissionCodesByUserId(STRANGER))
                .thenReturn(List.of(Permission.FEEDBACK_HANDLE));
        when(collaboratorMapper.findCollaboratorRole(100, STRANGER)).thenReturn(null);

        assertFalse(accessService.hasPermission(s, STRANGER, Role.SUB_ADMIN, Permission.SURVEY_DATA));
        assertThrows(BizException.class, () ->
                accessService.requirePermission(s, STRANGER, Role.SUB_ADMIN, Permission.SURVEY_EDIT, "修改"));
    }
}
