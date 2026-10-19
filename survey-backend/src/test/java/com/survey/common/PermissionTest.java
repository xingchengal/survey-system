package com.survey.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权限点与协作者角色的**口径**测试。
 *
 * ── 为什么值得单独测 ──────────────────────────────────
 *   这两处是"授权"的字面来源：权限点字符串写错、或协作者角色的权限集合被改宽，
 *   都不会让编译失败，只会让某个端点**悄悄放行**。所以把它们钉死：
 *   · 7 个权限点的字符串值（数据库种子、注解、审计表都按这些值对齐）；
 *   · 协作者三种角色的权限边界（viewer 什么都不能、editor 能改不能删、analyst 只能看数据）。
 */
class PermissionTest {

    @Test
    @DisplayName("★ 13 个权限点字符串固定：改一个就会与数据库种子/审计表脱钩")
    void permissionCodesAreStable() {
        assertEquals("survey:edit", Permission.SURVEY_EDIT);
        assertEquals("survey:publish", Permission.SURVEY_PUBLISH);
        assertEquals("survey:data", Permission.SURVEY_DATA);
        assertEquals("survey:delete", Permission.SURVEY_DELETE);
        assertEquals("user:manage", Permission.USER_MANAGE);
        assertEquals("review:handle", Permission.REVIEW_HANDLE);
        assertEquals("wallet:manage", Permission.WALLET_MANAGE);
        // 模块级（次级管理员可分配）
        assertEquals("survey:manage", Permission.SURVEY_MANAGE);
        assertEquals("template:manage", Permission.TEMPLATE_MANAGE);
        assertEquals("announcement:manage", Permission.ANNOUNCEMENT_MANAGE);
        assertEquals("log:view", Permission.LOG_VIEW);
        assertEquals("feedback:handle", Permission.FEEDBACK_HANDLE);
        assertEquals("system:view", Permission.SYSTEM_VIEW);
        // ALL 必须恰好覆盖这 13 个（新增权限点时这里会先红）
        assertEquals(13, Permission.ALL.size(),
                "新增/删除权限点时必须同步 sql/init.sql 与 migration 的种子");
    }

    @Test
    @DisplayName("★ ADMIN_GRANTABLE：只含模块级权限，不含对象级 survey:edit/publish/data/delete")
    void adminGrantableShouldExcludeObjectPermissions() {
        assertEquals(9, Permission.ADMIN_GRANTABLE.size());
        for (String code : Permission.ADMIN_GRANTABLE) {
            assertTrue(Permission.isValid(code), "可分配权限点必须也是合法权限点：" + code);
            assertFalse(code.equals(Permission.SURVEY_EDIT), "对象级权限不能直接授给次级管理员");
            assertFalse(code.equals(Permission.SURVEY_PUBLISH));
            assertFalse(code.equals(Permission.SURVEY_DATA));
            assertFalse(code.equals(Permission.SURVEY_DELETE));
        }
        assertTrue(Permission.isAdminGrantable(Permission.TEMPLATE_MANAGE));
        assertTrue(Permission.isAdminGrantable(Permission.FEEDBACK_HANDLE));
        assertFalse(Permission.isAdminGrantable(Permission.SURVEY_EDIT));
        assertFalse(Permission.isAdminGrantable(null));
        assertFalse(Permission.isAdminGrantable("template:manage "));
    }

    @Test
    @DisplayName("isValid：区分大小写，null/空串/未知值一律 false")
    void isValidRejectsUnknown() {
        assertTrue(Permission.isValid(Permission.SURVEY_EDIT));
        assertFalse(Permission.isValid(null));
        assertFalse(Permission.isValid(""));
        assertFalse(Permission.isValid("  "));
        assertFalse(Permission.isValid("SURVEY:EDIT"), "权限点全小写，不做大小写归一");
        assertFalse(Permission.isValid("survey:editt"), "写错一个字母必须被发现（切面会抛 500）");
        assertFalse(Permission.isValid(" survey:edit "), "不允许带空白");
    }

    @Test
    @DisplayName("★ viewer：什么权限都没有（只能看问卷本身）")
    void viewerHasNoPermission() {
        assertTrue(CollaboratorRole.VIEWER.getPermissions().isEmpty());
        for (String code : Permission.ALL) {
            assertFalse(CollaboratorRole.VIEWER.has(code), "viewer 不该拥有 " + code);
        }
    }

    @Test
    @DisplayName("★ editor：能改、能看数据，但**不能删**（删除只留给作者/管理员）")
    void editorCanEditAndSeeDataButNotDelete() {
        assertTrue(CollaboratorRole.EDITOR.has(Permission.SURVEY_EDIT));
        assertTrue(CollaboratorRole.EDITOR.has(Permission.SURVEY_DATA));
        assertFalse(CollaboratorRole.EDITOR.has(Permission.SURVEY_DELETE), "editor 不能删是验收口径之一");
        assertFalse(CollaboratorRole.EDITOR.has(Permission.USER_MANAGE));
        assertFalse(CollaboratorRole.EDITOR.has(Permission.SURVEY_PUBLISH), "发布涉及对外可见，仍归作者/管理员");
    }

    @Test
    @DisplayName("★ analyst：只能看数据")
    void analystOnlySeesData() {
        assertEquals(Set.of(Permission.SURVEY_DATA), CollaboratorRole.ANALYST.getPermissions());
        assertFalse(CollaboratorRole.ANALYST.has(Permission.SURVEY_EDIT));
        assertFalse(CollaboratorRole.ANALYST.has(Permission.SURVEY_DELETE));
    }

    @Test
    @DisplayName("协作者角色只能配合法权限点：与 common/Permission 的 ALL 对齐（防止拼错字符串）")
    void collaboratorPermissionsMustBeKnownCodes() {
        for (CollaboratorRole r : CollaboratorRole.values()) {
            for (String code : r.getPermissions()) {
                assertTrue(Permission.isValid(code),
                        "角色 " + r.getCode() + " 里出现了未知权限点：" + code);
            }
        }
    }

    @Test
    @DisplayName("fromCode：未知/空/null 一律 null（调用方按无权限处理，不抛异常）")
    void fromCodeIsNullSafe() {
        assertEquals(CollaboratorRole.VIEWER, CollaboratorRole.fromCode("viewer"));
        assertEquals(CollaboratorRole.EDITOR, CollaboratorRole.fromCode("editor"));
        assertEquals(CollaboratorRole.ANALYST, CollaboratorRole.fromCode("analyst"));
        assertNull(CollaboratorRole.fromCode(null));
        assertNull(CollaboratorRole.fromCode(""));
        assertNull(CollaboratorRole.fromCode("Editor"), "不做大小写归一 —— 与 Role.isAdmin 一致");
        assertNull(CollaboratorRole.fromCode("owner"), "脏数据/手工改库写错 → null，别炸整份问卷");
    }
}
