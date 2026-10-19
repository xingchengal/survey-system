package com.survey.common;

import com.survey.entity.Survey;

/**
 * 问卷归属校验唯一口径：管理员 / 作者 / 协作者三选一。
 *
 * ★ 与 {@code @RequireAdmin}（角色）、{@code @RequirePermission}（能力）分工：本类判“对象归谁”。
 *   完整授权 = 能力（切面）× 对象（本类），缺一半就是横向越权。
 * ★ 纯函数、不查库；协作者角色码由 {@code SurveyAccessService} 查好传入。
 * ★ 作者本人全权，不查权限点表 —— 配置漏了不能变成“作者删不了自己的问卷”。
 * ★ 失败抛 {@link BizException}（400，不写异常日志）。
 */
public final class OwnershipGuard {

    private OwnershipGuard() {
    }

    /**
     * 要求：管理员或作者本人；复制仍只给作者/管理员（复制属于数据外带，不开放给协作者）。
     *
     * @param survey 目标问卷（可为 null）
     * @param userId 当前登录用户 id（匿名时为 null）
     * @param role   当前角色（JwtAuthFilter 注入的 X-User-Role）
     * @param action 动作名，用于拼“无权限{action}他人问卷”
     */
    public static void requireOwnerOrAdmin(Survey survey, Integer userId, String role, String action) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (Role.isAdmin(role)) {
            return;   // 管理员放行
        }
        if (isOwner(survey, userId)) {
            return;   // 作者本人
        }
        throw new BizException("无权限" + action + "他人问卷");
    }

    /**
     * 是否拥有某个问卷级权限：管理员 / 作者 / 协作者三选一。
     * ★ 纯函数：不查库；匿名（userId=null）即使误传协作者角色码也必须拒绝。
     *
     * @param permission 见 {@link Permission}（survey:edit / publish / data / delete）
     */
    public static boolean canAccess(Survey survey, Integer userId, String role,
                                    String permission, String collaboratorRoleCode) {
        if (survey == null) {
            return false;
        }
        if (Role.isAdmin(role) || isOwner(survey, userId)) {
            return true;   // 管理员与作者：全权（作者不依赖权限点表）
        }
        // ★★ 匿名（userId=null）不可能有协作者身份：即便调用方误传了角色码也必须拒绝。
        //   这条是**防御性**的 —— 线上 SurveyAccessService 在 userId 为 null 时根本不查库，
        //   但纯函数不能依赖调用方守规矩（单测先把它钉住了）。
        if (userId == null) {
            return false;
        }
        CollaboratorRole collaborator = CollaboratorRole.fromCode(collaboratorRoleCode);
        return collaborator != null && collaborator.has(permission);
    }

    /**
     * 要求某问卷级权限，否则抛「无权限{action}他人问卷」。
     * ★ 文案格式与 {@link #requireOwnerOrAdmin} 一致，测试与前端按它断言。
     */
    public static void requirePermission(Survey survey, Integer userId, String role,
                                         String permission, String collaboratorRoleCode, String action) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (!canAccess(survey, userId, role, permission, collaboratorRoleCode)) {
            throw new BizException("无权限" + action + "他人问卷");
        }
    }

    /**
     * 要求：管理员 / 作者 / 已发布，三者之一才可读；回收站优先。
     * ★ 回收站里只放行管理员与作者 —— moveToRecycle 只改 deleted 不改 status，
     *   若仍按“已发布即可读”，逻辑删除会形同虚设。
     *
     * @param message 不通过时的文案（两处调用点说法不同）
     */
    public static void requireVisible(Survey survey, Integer userId, String role, String message) {
        requireVisible(survey, userId, role, null, message);
    }

    /**
     * 带协作者身份的可见性：协作者可读非回收站问卷；回收站仍只给作者/管理员。
     */
    public static void requireVisible(Survey survey, Integer userId, String role,
                                      String collaboratorRoleCode, String message) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        // ★ **回收站中的问卷只有管理员与作者本人能读**（作者要能看到自己删掉的东西）。
        if (isInRecycleBin(survey)) {
            if (Role.isAdmin(role) || isOwner(survey, userId)) {
                return;
            }
            throw new BizException("问卷已在回收站中");
        }
        if (Role.isAdmin(role) || isOwner(survey, userId)) {
            return;
        }
        // ★ 同上：匿名没有协作者身份，误传角色码也不能放行。
        if (userId != null && CollaboratorRole.fromCode(collaboratorRoleCode) != null) {
            return;   // 协作者：被邀请来看的人至少能打开问卷
        }
        if (SurveyStatus.isPublished(survey.getStatus())) {
            return;   // 已发布 = 公开内容
        }
        throw new BizException(message);
    }

    /**
     * 要求问卷不在回收站（deleted = 0 或 NULL）。
     * ★ 所有入口都要挡：否则回收站里的问卷仍能被“全量覆盖”保存题目，历史答卷的 question_id 会悬空。
     *
     * @param action 动作名，用于拼“问卷已在回收站中，无法{action}”
     */
    public static void requireNotInRecycleBin(Survey survey, String action) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (isInRecycleBin(survey)) {
            throw new BizException("问卷已在回收站中，无法" + action);
        }
    }

    /** 是否作者本人（统一判空口径，不要在各处写 `survey.getUserId().equals(userId)`） */
    private static boolean isOwner(Survey survey, Integer userId) {
        Integer ownerId = survey.getUserId();
        return ownerId != null && ownerId.equals(userId);
    }

    /**
     * 是否在回收站（deleted != 0）。
     * ★ NULL = 未删除：与 SQL 的 deleted = 0 口径一致，不能把历史 NULL 误判成回收站。
     */
    private static boolean isInRecycleBin(Survey survey) {
        return survey.getDeleted() != null && survey.getDeleted() != 0;
    }
}
