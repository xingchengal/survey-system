package com.survey.common;

import com.survey.entity.Survey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 问卷归属校验的纯单元测试。
 *
 * ── 为什么这个测试值得单写一个类 ─────────────────────────
 *   {@code OwnershipGuard} 管的是**全项目最要紧的安全判断**：
 *   "这份问卷能不能被这个人操作/看到"。收敛成纯静态方法后，可以用**零依赖**的方式把它钉死
 *   （不需要 Spring 容器、不需要 Mockito、不需要数据库）。
 *
 * ── 两条必须守住的防回归（重点）──────────────────────────
 *   1. **ownerId 为 null 时不得抛 NPE**：
 *      {@code t_survey.user_id} 在库里是 **NOT NULL**（预置模板存的是 0 而不是 NULL），
 *      但实体可能由非数据库来源构造；而 {@code survey.getUserId().equals(userId)} 一旦碰上
 *      null 就会 **500 + 异常日志**，而不是给用户一句 400 提示。Test: nullOwner_* 三个用例。
 *   2. **匿名（userId 为 null）不得被误放行**：
 *      判空若写反成"ownerId 为 null 就 return"，匿名访客就能操作任何问卷。
 *      Test: anonymousUser_shouldBeRejected。
 */
class OwnershipGuardTest {

    /** 问卷的归属者 */
    private static final Integer OWNER = 7;
    /** 另一个普通用户（非归属者） */
    private static final Integer OTHER = 8;

    private static Survey survey(Integer ownerId, Integer status) {
        Survey s = new Survey();
        s.setId(100);
        s.setUserId(ownerId);
        s.setStatus(status);
        return s;
    }

    // ==================== requireOwnerOrAdmin ====================

    @Test
    @DisplayName("管理员对任何人的问卷都放行（含 user_id 为 NULL 的脏数据）")
    void admin_shouldAlwaysPass() {
        assertDoesNotThrow(() ->
                OwnershipGuard.requireOwnerOrAdmin(survey(OTHER, SurveyStatus.DRAFT.code()), OWNER, Role.ADMIN, "删除"));
        assertDoesNotThrow(() ->
                OwnershipGuard.requireOwnerOrAdmin(survey(null, SurveyStatus.DRAFT.code()), OWNER, Role.ADMIN, "删除"));
        assertDoesNotThrow(() ->
                OwnershipGuard.requireOwnerOrAdmin(survey(OWNER, SurveyStatus.PUBLISHED.code()), OTHER, Role.ADMIN, "删除"));
    }

    @Test
    @DisplayName("本人操作自己的问卷放行（草稿也一样）")
    void owner_shouldPass() {
        assertDoesNotThrow(() ->
                OwnershipGuard.requireOwnerOrAdmin(survey(OWNER, SurveyStatus.DRAFT.code()), OWNER, Role.USER, "删除"));
        assertDoesNotThrow(() ->
                OwnershipGuard.requireOwnerOrAdmin(survey(OWNER, SurveyStatus.PUBLISHED.code()), OWNER, Role.USER, "发布"));
    }

    @Test
    @DisplayName("非本人的普通用户被拒：400 文案带上动作名")
    void otherUser_shouldBeRejectedWithActionName() {
        BizException ex = assertThrows(BizException.class, () ->
                OwnershipGuard.requireOwnerOrAdmin(survey(OTHER, SurveyStatus.DRAFT.code()), OWNER, Role.USER, "删除"));
        assertEquals("无权限删除他人问卷", ex.getMessage());

        // 换一个动作名，文案随之变化（保证 action 参数真的被用上，而不是写死）
        BizException ex2 = assertThrows(BizException.class, () ->
                OwnershipGuard.requireOwnerOrAdmin(survey(OTHER, SurveyStatus.DRAFT.code()), OWNER, Role.USER, "复制"));
        assertEquals("无权限复制他人问卷", ex2.getMessage());
    }

    @Test
    @DisplayName("★ user_id 为 NULL + 非管理员 → 抛 BizException（不是 NPE）")
    void nullOwner_nonAdmin_shouldThrowBizExceptionNotNpe() {
        BizException ex = assertThrows(BizException.class, () ->
                OwnershipGuard.requireOwnerOrAdmin(survey(null, SurveyStatus.DRAFT.code()), OWNER, Role.USER, "删除"));

        // ★ 不能是 NPE：那会变成 500 + 异常日志，而不是可读的 400 文案
        assertNotNull(ex.getMessage(), "必须有可读的 400 文案，而不是 NPE");
        assertEquals("无权限删除他人问卷", ex.getMessage());
    }

    @Test
    @DisplayName("★ 匿名（userId 为 null）→ 被拒，不得因为 ownerId 判空而误放行")
    void anonymousUser_shouldBeRejected() {
        assertThrows(BizException.class, () ->
                OwnershipGuard.requireOwnerOrAdmin(survey(OWNER, SurveyStatus.DRAFT.code()), null, null, "删除"));
        // 问卷归属为 NULL、请求者也匿名 —— 两个 null 相遇也不能"相等"而放行
        assertThrows(BizException.class, () ->
                OwnershipGuard.requireOwnerOrAdmin(survey(null, SurveyStatus.DRAFT.code()), null, null, "删除"));
    }

    @Test
    @DisplayName("问卷不存在（null）→ BizException(\"问卷不存在\")")
    void nullSurvey_shouldThrowBizException() {
        BizException ex = assertThrows(BizException.class, () ->
                OwnershipGuard.requireOwnerOrAdmin(null, OWNER, Role.USER, "删除"));
        assertEquals("问卷不存在", ex.getMessage());

        // 管理员也不能操作一个不存在的问卷（否则后面必然 NPE）
        assertThrows(BizException.class, () ->
                OwnershipGuard.requireOwnerOrAdmin(null, OWNER, Role.ADMIN, "删除"));
    }

    // ==================== requireVisible ====================

    private static final String VISIBLE_MSG = "无权限查看他人未发布的问卷";

    @Test
    @DisplayName("可见性：管理员 / 本人 / 已发布 —— 三者任一都放行")
    void requireVisible_shouldPassForAdminOwnerOrPublished() {
        // 已发布：任何人可读（与分享链接口径一致）
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(
                survey(OTHER, SurveyStatus.PUBLISHED.code()), OWNER, Role.USER, VISIBLE_MSG));
        // 本人：草稿也能读
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(
                survey(OWNER, SurveyStatus.DRAFT.code()), OWNER, Role.USER, VISIBLE_MSG));
        // 管理员：草稿也能读
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(
                survey(OTHER, SurveyStatus.DRAFT.code()), OWNER, Role.ADMIN, VISIBLE_MSG));
    }

    @Test
    @DisplayName("可见性：别人的草稿被拒（横向越权防线），文案由调用方传入")
    void requireVisible_shouldRejectOthersDraft() {
        BizException ex = assertThrows(BizException.class, () -> OwnershipGuard.requireVisible(
                survey(OTHER, SurveyStatus.DRAFT.code()), OWNER, Role.USER, VISIBLE_MSG));
        assertEquals(VISIBLE_MSG, ex.getMessage());
    }

    @Test
    @DisplayName("可见性：★ status 为 NULL（历史脏数据）按草稿处理 —— 别人的 NULL 状态问卷不可读")
    void requireVisible_nullStatusTreatedAsDraft() {
        assertThrows(BizException.class, () -> OwnershipGuard.requireVisible(
                survey(OTHER, null), OWNER, Role.USER, VISIBLE_MSG));
        // 已结束（status=2）同样不是"公开可读"，只有已发布才是
        assertThrows(BizException.class, () -> OwnershipGuard.requireVisible(
                survey(OTHER, SurveyStatus.ENDED.code()), OWNER, Role.USER, VISIBLE_MSG));
        // 但本人的 NULL 状态问卷仍可读
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(
                survey(OWNER, null), OWNER, Role.USER, VISIBLE_MSG));
    }

    @Test
    @DisplayName("可见性：★ user_id 为 NULL 的草稿 + 匿名 → 被拒且不抛 NPE")
    void requireVisible_nullOwnerAnonymous_shouldNotThrowNpe() {
        BizException ex = assertThrows(BizException.class, () -> OwnershipGuard.requireVisible(
                survey(null, null), null, null, VISIBLE_MSG));
        assertEquals(VISIBLE_MSG, ex.getMessage());
    }

    // ==================== canAccess / requirePermission（H2：能力 × 对象）====================

    /** 回收站里的问卷（deleted=1） */
    private static Survey recycled(Integer ownerId) {
        Survey s = survey(ownerId, SurveyStatus.PUBLISHED.code());
        s.setDeleted(1);
        return s;
    }

    @Test
    @DisplayName("★ 作者拥有四种问卷权限：不依赖权限点表（作者对自己的数据不该被配置锁住）")
    void ownerHasEveryPermission() {
        Survey s = survey(OWNER, SurveyStatus.PUBLISHED.code());
        for (String p : List.of(Permission.SURVEY_EDIT, Permission.SURVEY_PUBLISH,
                Permission.SURVEY_DATA, Permission.SURVEY_DELETE)) {
            assertTrue(OwnershipGuard.canAccess(s, OWNER, Role.USER, p, null), "作者应拥有 " + p);
        }
    }

    @Test
    @DisplayName("管理员：任何问卷、任何权限都放行（corroborator 角色码传 null 也一样）")
    void adminHasEveryPermission() {
        Survey s = survey(OTHER, SurveyStatus.DRAFT.code());
        for (String p : Permission.ALL) {
            assertTrue(OwnershipGuard.canAccess(s, OWNER, Role.ADMIN, p, null), "管理员应拥有 " + p);
        }
    }

    @Test
    @DisplayName("★★ 协作者权限矩阵：viewer 全无 / editor 能改能看数据、不能删不能发布 / analyst 只看数据")
    void collaboratorPermissionMatrix() {
        Survey s = survey(OWNER, SurveyStatus.PUBLISHED.code());

        for (String p : Permission.ALL) {
            assertFalse(OwnershipGuard.canAccess(s, OTHER, Role.USER, p, "viewer"),
                    "viewer 不该拥有 " + p);
        }

        assertTrue(OwnershipGuard.canAccess(s, OTHER, Role.USER, Permission.SURVEY_EDIT, "editor"));
        assertTrue(OwnershipGuard.canAccess(s, OTHER, Role.USER, Permission.SURVEY_DATA, "editor"));
        assertFalse(OwnershipGuard.canAccess(s, OTHER, Role.USER, Permission.SURVEY_DELETE, "editor"),
                "editor 不能删是 H2 的验收口径之一");
        assertFalse(OwnershipGuard.canAccess(s, OTHER, Role.USER, Permission.SURVEY_PUBLISH, "editor"),
                "发布让问卷对外可见，只给作者/管理员");

        assertTrue(OwnershipGuard.canAccess(s, OTHER, Role.USER, Permission.SURVEY_DATA, "analyst"));
        assertFalse(OwnershipGuard.canAccess(s, OTHER, Role.USER, Permission.SURVEY_EDIT, "analyst"));
        assertFalse(OwnershipGuard.canAccess(s, OTHER, Role.USER, Permission.SURVEY_DELETE, "analyst"));
    }

    @Test
    @DisplayName("★ 脏数据/空值：协作者角色码为 null、空串、未知值、大小写不符 → 一律无权限（失败方向必须是拒绝）")
    void unknownCollaboratorRoleIsDenied() {
        Survey s = survey(OWNER, SurveyStatus.PUBLISHED.code());
        for (String bad : List.of("", "  ", "Editor", "owner", "admin", "superuser")) {
            assertFalse(OwnershipGuard.canAccess(s, OTHER, Role.USER, Permission.SURVEY_EDIT, bad),
                    "非法协作者角色码不该放行：" + bad);
        }
        assertFalse(OwnershipGuard.canAccess(s, OTHER, Role.USER, Permission.SURVEY_EDIT, null));
    }

    @Test
    @DisplayName("★ 匿名（userId=null）不是作者也不是管理员 → 拒绝，且不 NPE")
    void anonymousHasNoPermission() {
        Survey s = survey(OWNER, SurveyStatus.PUBLISHED.code());
        assertFalse(OwnershipGuard.canAccess(s, null, null, Permission.SURVEY_EDIT, null));
        assertFalse(OwnershipGuard.canAccess(s, null, null, Permission.SURVEY_DATA, "analyst"),
                "匿名请求不该因为「恰好传了角色码」被放行");
    }

    @Test
    @DisplayName("★ 问卷为 null → canAccess=false，requirePermission 抛「问卷不存在」")
    void nullSurveyIsSafe() {
        assertFalse(OwnershipGuard.canAccess(null, OWNER, Role.ADMIN, Permission.SURVEY_EDIT, null));
        BizException ex = assertThrows(BizException.class, () ->
                OwnershipGuard.requirePermission(null, OWNER, Role.USER, Permission.SURVEY_EDIT, null, "修改"));
        assertEquals("问卷不存在", ex.getMessage());
    }

    @Test
    @DisplayName("★ requirePermission 的文案必须与改造前的 requireOwnerOrAdmin 完全一致（前端与矩阵测试按它断言）")
    void requirePermissionKeepsLegacyMessage() {
        Survey s = survey(OWNER, SurveyStatus.DRAFT.code());
        BizException legacy = assertThrows(BizException.class, () ->
                OwnershipGuard.requireOwnerOrAdmin(s, OTHER, Role.USER, "删除"));
        BizException now = assertThrows(BizException.class, () ->
                OwnershipGuard.requirePermission(s, OTHER, Role.USER, Permission.SURVEY_DELETE, null, "删除"));
        assertEquals(legacy.getMessage(), now.getMessage());
        assertEquals("无权限删除他人问卷", now.getMessage());
    }

    @Test
    @DisplayName("★ editor 协作者可以保存题目（survey:edit）；analyst 不行")
    void collaboratorEditPermissionIsEnforced() {
        Survey s = survey(OWNER, SurveyStatus.PUBLISHED.code());
        assertDoesNotThrow(() ->
                OwnershipGuard.requirePermission(s, OTHER, Role.USER, Permission.SURVEY_EDIT, "editor", "修改"));
        BizException ex = assertThrows(BizException.class, () ->
                OwnershipGuard.requirePermission(s, OTHER, Role.USER, Permission.SURVEY_EDIT, "analyst", "修改"));
        assertEquals("无权限修改他人问卷", ex.getMessage());
    }

    // ==================== requireVisible 与协作者 ====================

    @Test
    @DisplayName("★ 协作者（任何角色）能读**非回收站**的草稿 —— 否则被邀请来看的人连问卷都打不开")
    void collaboratorCanViewDraft() {
        Survey draft = survey(OWNER, SurveyStatus.DRAFT.code());
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(draft, OTHER, Role.USER, "viewer", "无权查看"));
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(draft, OTHER, Role.USER, "editor", "无权查看"));
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(draft, OTHER, Role.USER, "analyst", "无权查看"));
        // 而陌生人不给看
        assertEquals("无权查看", assertThrows(BizException.class,
                () -> OwnershipGuard.requireVisible(draft, OTHER, Role.USER, null, "无权查看")).getMessage());
    }

    @Test
    @DisplayName("★★ 回收站优先：协作者**读不到**回收站里的问卷（只有作者/管理员能）")
    void collaboratorCannotViewRecycled() {
        Survey inBin = recycled(OWNER);
        for (String role : List.of("viewer", "editor", "analyst")) {
            BizException ex = assertThrows(BizException.class, () ->
                    OwnershipGuard.requireVisible(inBin, OTHER, Role.USER, role, "无权查看"));
            assertEquals("问卷已在回收站中", ex.getMessage(), "回收站口径必须优先于协作者身份");
        }
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(inBin, OWNER, Role.USER, null, "无权查看"));
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(inBin, OTHER, Role.ADMIN, null, "无权查看"));
    }

    @Test
    @DisplayName("已发布问卷：陌生人与匿名都能读（口径不变）")
    void publishedIsVisibleToEveryone() {
        Survey published = survey(OWNER, SurveyStatus.PUBLISHED.code());
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(published, OTHER, Role.USER, null, "无权查看"));
        assertDoesNotThrow(() -> OwnershipGuard.requireVisible(published, null, null, null, "无权查看"));
    }

}
