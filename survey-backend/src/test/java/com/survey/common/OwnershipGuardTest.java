package com.survey.common;

import com.survey.entity.Survey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
