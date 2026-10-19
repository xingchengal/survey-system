package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.CollaboratorRole;
import com.survey.common.OwnershipGuard;
import com.survey.common.Permission;
import com.survey.common.Role;
import com.survey.common.SurveyLifecycle;
import com.survey.entity.Survey;
import com.survey.mapper.SurveyCollaboratorMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「这个用户能不能对这份问卷做这件事」的唯一入口。
 * 分工：PermissionAspect 判能力（有没有 survey:edit），本类判对象（这份问卷是不是他的）；
 *   只判能力 → 有 survey:edit 的人能改所有人的问卷；只判归属 → 被邀请的 analyst 也能改题。
 * ★ 协作者身份必须查库（t_survey_collaborator），所以判定规则留在 OwnershipGuard 纯函数，本类只查角色。
 * 管理员与作者不查库（本来就全权），绝大多数请求不会有这条 SQL。
 */
@Service
public class SurveyAccessService {

    @Autowired
    private SurveyCollaboratorMapper collaboratorMapper;

    /**
     * 次级管理员的模块权限来源。
     * 只有"staff 且不是超管"才查；超管由 OwnershipGuard 直接放行，不查这张表。
     * survey:manage → 任何问卷；template:manage → 仅模板（isTemplate=1）。
     */
    @Autowired
    private com.survey.mapper.RoleMapper roleMapper;

    /**
     * 次级管理员是否拥有某个模块级权限点（列表页这类"不针对某一份问卷"的入口用）。
     * 不是对象级授权，不能拿它替代 requirePermission。
     */
    public boolean hasModulePermission(Integer userId, String role, String code) {
        if (Role.isAdmin(role)) {
            return true;
        }
        if (!Role.isStaff(role) || userId == null || code == null) {
            return false;
        }
        List<String> codes = roleMapper.findPermissionCodesByUserId(userId);
        return codes != null && codes.contains(code);
    }

    /**
     * 是否拥有某个问卷级权限（不抛异常，给需要自己组织文案的调用方用）。
     *
     * @param permission 取值见 {@link com.survey.common.Permission}（如 survey:edit）
     */
    public boolean hasPermission(Survey survey, Integer userId, String role, String permission) {
        if (staffModuleAllows(survey, userId, role)) {
            return true;
        }
        return OwnershipGuard.canAccess(survey, userId, role, permission,
                collaboratorRoleCode(survey, userId, role));
    }

    /**
     * 要求某权限，否则 400 +「无权限{action}他人问卷」。
     * 文案被前端与 EndpointAuthMatrixTest 断言，别改。
     *
     * @param action 动作名（如 "修改" / "发布" / "删除" / "查看版本历史"）
     */
    public void requirePermission(Survey survey, Integer userId, String role,
                                  String permission, String action) {
        if (staffModuleAllows(survey, userId, role)) {
            return;
        }
        OwnershipGuard.requirePermission(survey, userId, role, permission,
                collaboratorRoleCode(survey, userId, role), action);
    }

    /**
     * 要求"能看"这份问卷（读路径）。
     * 回收站里只有作者/管理员能看；其余情况作者 / 管理员 / 协作者（任何角色）/ 已发布都能看。
     */
    public void requireVisible(Survey survey, Integer userId, String role, String message) {
        if (staffModuleAllows(survey, userId, role)) {
            return;
        }
        OwnershipGuard.requireVisible(survey, userId, role,
                collaboratorRoleCode(survey, userId, role), message);
    }

    /**
     * 要求"作者本人 / 超管 / 有对应模块权限的次级管理员"。
     * 不能直接用 requirePermission：editor 协作者有 survey:edit，但协作者名单管理与复制问卷会扩散权限。
     */
    public void requireOwnerOrStaff(Survey survey, Integer userId, String role, String action) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (Role.isAdmin(role) || isOwner(survey, userId)) {
            return;
        }
        if (staffModuleAllows(survey, userId, role)) {
            return;
        }
        throw new BizException("无权限" + action + "他人问卷");
    }

    /**
     * 已下架（SUSPENDED）问卷的写操作闸门。
     * 不塞进 requirePermission：版本历史列表拿 SURVEY_EDIT 做只读校验，一刀切会把"看历史"也挡掉。
     * 管理员放行（下架是处置，仍需能应急修内容）；作者与协作者冻结，否则下架只是藏起来。
     * 不拦：看统计 / 看版本历史 / 移入回收站 / 彻底删除 / 退出协作。
     *
     * @param action 动作名，拼进 400 文案（如 "修改" / "发布"）
     */
    public void requireNotSuspended(Survey survey, String role, String action) {
        if (survey == null) {
            return;
        }
        if (!SurveyLifecycle.SUSPENDED.code().equals(survey.getLifecycle())) {
            return;
        }
        if (Role.isAdmin(role)) {
            return;
        }
        throw new BizException(
                "该问卷已被下架，暂不能" + action + "；请先在「我的问卷」申请恢复上架");
    }

    /**
     * 是不是这份问卷的协作者（任何角色）。
     * 编辑器读题目要用它：协作者没有作者身份，但必须能打开问卷，否则进不了编辑器。
     */
    public boolean isCollaborator(Survey survey, Integer userId) {
        return survey != null && survey.getId() != null && userId != null
                && collaboratorMapper.findCollaboratorRole(survey.getId(), userId) != null;
    }

    /**
     * 当前用户对这份问卷的有效权限点集合（前端按钮显隐用）。
     * 与 canAccess 同源：作者/管理员四项全有，协作者按角色映射，其他人空；顺序固定便于渲染与断言。
     * 下架时非管理员拿不到 survey:edit / survey:publish（按钮自动禁用），与 requireNotSuspended 同一口径；
     *   作者保留 survey:data / survey:delete，协作者只保留与"写"无关的权限点。
     */
    public List<String> permissionsFor(Survey survey, Integer userId, String role) {
        if (survey == null) {
            return List.of();
        }
        List<String> all = List.of(Permission.SURVEY_EDIT, Permission.SURVEY_PUBLISH,
                Permission.SURVEY_DATA, Permission.SURVEY_DELETE);
        if (Role.isAdmin(role)) {
            return all;
        }
        boolean suspended = SurveyLifecycle.SUSPENDED.code().equals(survey.getLifecycle());
        if (staffModuleAllows(survey, userId, role)) {
            // 与作者同口径：下架期间收掉“能改”，保留看数据/删除
            return suspended
                    ? List.of(Permission.SURVEY_DATA, Permission.SURVEY_DELETE)
                    : all;
        }
        if (isOwner(survey, userId)) {
            return suspended
                    ? List.of(Permission.SURVEY_DATA, Permission.SURVEY_DELETE)
                    : all;
        }
        Integer surveyId = survey.getId();
        if (userId == null || surveyId == null) {
            return List.of();
        }
        CollaboratorRole collaborator = CollaboratorRole.fromCode(
                collaboratorMapper.findCollaboratorRole(surveyId, userId));
        if (collaborator == null) {
            return List.of();
        }
        // 按固定顺序过滤，保证返回顺序稳定；下架时把"能改"收掉
        return all.stream()
                .filter(collaborator::has)
                .filter(permission -> !suspended || !Permission.SURVEY_EDIT.equals(permission))
                .toList();
    }

    /**
     * 次级管理员是否凭模块权限获得这份问卷的对象级权限。
     * ★ survey:manage → 任何问卷；template:manage → 仅 isTemplate=1 的模板（否则模板管理员能顺手改用户问卷）。
     * Role.isStaff 只用于数据范围，不能替代模块权限；超管走 OwnershipGuard 的 admin 分支。
     * 没有这两个权限的次级管理员，继续按作者/协作者口径判。
     */
    private boolean staffModuleAllows(Survey survey, Integer userId, String role) {
        if (survey == null || userId == null
                || !Role.isStaff(role) || Role.isAdmin(role)) {
            return false;
        }
        List<String> codes = roleMapper.findPermissionCodesByUserId(userId);
        if (codes == null || codes.isEmpty()) {
            return false;
        }
        if (codes.contains(Permission.SURVEY_MANAGE)) {
            return true;
        }
        return Integer.valueOf(1).equals(survey.getIsTemplate())
                && codes.contains(Permission.TEMPLATE_MANAGE);
    }

    /** 是否作者本人（与 OwnershipGuard 同口径；复制一份判空是为了不额外查库） */
    private boolean isOwner(Survey survey, Integer userId) {
        Integer ownerId = survey.getUserId();
        return ownerId != null && ownerId.equals(userId);
    }

    /**
     * 取协作者角色（非协作者返回 null）。
     * 管理员/作者直接返回 null、不查库：他们不需要协作者身份就已经全权。
     */
    private String collaboratorRoleCode(Survey survey, Integer userId, String role) {
        if (survey == null || survey.getId() == null || userId == null) {
            return null;
        }
        if (Role.isAdmin(role)) {
            return null;
        }
        Integer ownerId = survey.getUserId();
        if (ownerId != null && ownerId.equals(userId)) {
            return null;
        }
        return collaboratorMapper.findCollaboratorRole(survey.getId(), userId);
    }
}
