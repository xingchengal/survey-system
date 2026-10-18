package com.survey.service;

import com.survey.common.CollaboratorRole;
import com.survey.common.OwnershipGuard;
import com.survey.common.Permission;
import com.survey.common.Role;
import com.survey.entity.Survey;
import com.survey.mapper.SurveyCollaboratorMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「这个用户能不能对**这份问卷**做这件事」的唯一入口。
 *
 * ── 它和 OwnershipGuard / PermissionAspect 的分工 ────────
 *   · {@code PermissionAspect}（@RequirePermission）：**能力**——"这个账号有没有 survey:edit 这类操作权"；
 *   · 本类：**对象**——"这份问卷是不是他的 / 他是不是这份问卷的协作者"。
 *   只有两者都过，才允许落库。缺任何一半都是横向越权：
 *     只判能力 → 有 survey:edit 的人能改**所有人**的问卷；
 *     只判归属 → 被邀请的 analyst 也能改题。
 *
 * ── 为什么要有这个 Service，而不是继续用静态 OwnershipGuard ──
 *   "是不是协作者"必须查库（t_survey_collaborator）。静态工具类不该拿 Mapper，
 *   所以：**判定规则**仍是 OwnershipGuard 的纯函数（可单测、无 Spring），
 *   本类只负责"查协作者角色 + 交给纯函数判"。
 *
 * ── 性能口径 ──────────────────────────────────────────
 *   管理员与作者**不查库**（他们本来就全权），所以绝大多数请求不会有这条 SQL；
 *   只有"非作者、非管理员"的请求才会多一次按唯一键的查询。
 */
@Service
public class SurveyAccessService {

    @Autowired
    private SurveyCollaboratorMapper collaboratorMapper;

    /**
     * 是否拥有某个问卷级权限（不抛异常，给需要自己组织文案的调用方用）。
     *
     * @param permission 取值见 {@link com.survey.common.Permission}（如 survey:edit）
     */
    public boolean hasPermission(Survey survey, Integer userId, String role, String permission) {
        return OwnershipGuard.canAccess(survey, userId, role, permission,
                collaboratorRoleCode(survey, userId, role));
    }

    /**
     * 要求某权限，否则 **400 +「无权限{action}他人问卷」**。
     * ★ 文案格式与改造前的 {@code OwnershipGuard.requireOwnerOrAdmin} 完全一致 ——
     *   前端与 EndpointAuthMatrixTest 都按这个文案断言，别随口改。
     *
     * @param action 动作名（如 "修改" / "发布" / "删除" / "查看版本历史"）
     */
    public void requirePermission(Survey survey, Integer userId, String role,
                                  String permission, String action) {
        OwnershipGuard.requirePermission(survey, userId, role, permission,
                collaboratorRoleCode(survey, userId, role), action);
    }

    /**
     * 要求"能看"这份问卷（读路径）。
     * ★ 口径：回收站里只有作者/管理员能看；其余情况"作者 / 管理员 / 协作者（任何角色）/ 已发布"都能看。
     *   与改造前唯一的差别是**多了协作者**——viewer 被邀请后至少要能打开问卷。
     */
    public void requireVisible(Survey survey, Integer userId, String role, String message) {
        OwnershipGuard.requireVisible(survey, userId, role,
                collaboratorRoleCode(survey, userId, role), message);
    }

    /**
     * 是不是这份问卷的协作者（任何角色）。
     * ★ 编辑器读题目要用它：协作者没有作者身份，但必须能打开问卷看内容，
     *   否则"邀请你来协作"之后连编辑器都进不去（实测踩过：被答题密码挡住）。
     */
    public boolean isCollaborator(Survey survey, Integer userId) {
        return survey != null && survey.getId() != null && userId != null
                && collaboratorMapper.findCollaboratorRole(survey.getId(), userId) != null;
    }

    /**
     * 当前用户对这份问卷的**有效权限点集合**（给前端做按钮显隐用）。
     *
     * ★ 与 canAccess 同源：作者/管理员 = 四项全有；协作者 = 其角色映射的权限点；其他人 = 空。
     *   顺序固定，便于前端按稳定顺序渲染（也让测试断言好写）。
     */
    public List<String> permissionsFor(Survey survey, Integer userId, String role) {
        if (survey == null) {
            return List.of();
        }
        List<String> all = List.of(Permission.SURVEY_EDIT, Permission.SURVEY_PUBLISH,
                Permission.SURVEY_DATA, Permission.SURVEY_DELETE);
        if (Role.isAdmin(role) || isOwner(survey, userId)) {
            return all;
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
        // 按固定顺序过滤，保证返回顺序稳定
        return all.stream().filter(collaborator::has).toList();
    }

    /** 是否作者本人（与 OwnershipGuard 同口径；这里复制一份判空是为了不额外查库） */
    private boolean isOwner(Survey survey, Integer userId) {
        Integer ownerId = survey.getUserId();
        return ownerId != null && ownerId.equals(userId);
    }

    /**
     * 取协作者角色（非协作者返回 null）。
     * ★ 管理员/作者直接返回 null，**不查库**：他们不需要协作者身份就已经全权。
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
