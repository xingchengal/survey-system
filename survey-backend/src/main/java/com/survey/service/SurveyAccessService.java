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
     * 次级管理员（subadmin）模块权限查询。
     * ★ 只有“staff 且不是超管”时才查；超管由 OwnershipGuard 直接放行，不查这张表。
     * ★ 模块级放行口径（P1）：
     *   · {@code survey:manage} → 任何问卷；
     *   · {@code template:manage} → **仅模板**（isTemplate=1）。
     *   没有这两个权限的次级管理员，仍按普通作者/协作者口径判。
     */
    @Autowired
    private com.survey.mapper.RoleMapper roleMapper;

    /**
     * 次级管理员是否拥有某个**模块级权限点**（列表页这类“不是针对某一份问卷”的入口用）。
     * ★ 超管直接 true（兜底）；普通用户/未登录 false。
     * ★ 注意：这不是对象级授权，不能拿它替代 requirePermission。
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
     * 要求某权限，否则 **400 +「无权限{action}他人问卷」**。
     * ★ 文案格式与改造前的 {@code OwnershipGuard.requireOwnerOrAdmin} 完全一致 ——
     *   前端与 EndpointAuthMatrixTest 都按这个文案断言，别随口改。
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
     * ★ 口径：回收站里只有作者/管理员能看；其余情况"作者 / 管理员 / 协作者（任何角色）/ 已发布"都能看。
     *   与改造前唯一的差别是**多了协作者**——viewer 被邀请后至少要能打开问卷。
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
     *
     * ★ 为什么不能直接用 {@link #requirePermission}：
     *   editor 协作者有 survey:edit，但**不能管理协作者名单、不能复制问卷**（会权限扩散/数据外带）。
     *   这两个动作只开放给作者、超管，以及被授予 survey:manage / template:manage 的次级管理员。
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
     * 已下架（SUSPENDED）问卷的**写操作**闸门。
     *
     * ★ 为什么单独一条、不塞进 {@link #requirePermission}：
     *   版本历史列表用 SURVEY_EDIT 权限去做"只读"校验（文案「查看版本历史」），
     *   若在 requirePermission 里按权限点一刀切，会把"看历史"也挡掉。
     *   所以只让真正的写入口显式调用本方法：改标题/设置、保存题目、发布、回滚、加协作者。
     *
     * ★ 管理员放行：下架是管理员做出的处置，但恢复前若要应急修正内容（比如改掉违规文案），
     *   不该被自己的闸门拦住。作者与协作者一律冻结 —— 下架期间内容不能再变，
     *   否则"下架"就只是藏起来、作者还能继续改，审核失去意义。
     *
     * ★ 不拦的操作：看统计、看版本历史、移入回收站/彻底删除、退出协作。
     *   下架不等于封禁一切 —— 作者仍然有权把自己的问卷删掉。
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
     *
     * ★ 下架收敛（SUSPENDED）：非管理员**拿不到 survey:edit / survey:publish**，
     *   于是编辑器（SurveyEdit.vue）的保存/发布按钮自动禁用 —— 与后端
     *   {@link #requireNotSuspended} 的硬拦同一口径，不是只靠前端藏按钮。
     *   作者保留 survey:data（还能看数据）与 survey:delete（有权删掉自己的问卷）；
     *   协作者只保留其角色里与"写"无关的权限点（editor → data，analyst → data，viewer → 空）。
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
     * 次级管理员（subadmin）是否凭**模块权限**获得这份问卷的对象级权限。
     *
     * ★ 只对“staff 且不是超管”生效；超管走 OwnershipGuard 的 admin 分支，不查库。
     * ★ 两个模块权限的边界：
     *   · survey:manage  → 任何问卷（相当于“全站问卷管理员”）；
     *   · template:manage → 仅 isTemplate=1 的模板（不能让模板管理员顺手改用户问卷）。
     * ★ 没有这两个权限的次级管理员，继续按作者/协作者口径判（可能什么都没有）。
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
