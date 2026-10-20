package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.CollaboratorRole;
import com.survey.common.CollaboratorStatus;
import com.survey.common.Role;
import com.survey.common.WalletFeature;
import com.survey.dto.CollaboratorView;
import com.survey.dto.InvitationView;
import com.survey.entity.Survey;
import com.survey.entity.User;
import com.survey.mapper.SurveyCollaboratorMapper;
import com.survey.mapper.UserMapper;
import com.survey.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 问卷协作者：邀请 / 改角色 / 移除 / 列表 / 退出。
 * 只有作者本人与管理员能管理名单：让 editor 再邀请人会权限扩散，名单只该由问卷主人掌握。
 * ★ 角色只认 viewer / editor / analyst（大小写敏感，解析失败即 400）；不能邀请自己或作者本人。
 * 重复邀请、对非协作者改角色/移除一律 400，不静默成功；邀请、改角色、退出都发通知给相关人。
 * ★ invite 带事务：名额扣费与插入必须原子（扣了钱没插进去 = 用户白付）；并发重复邀请由唯一键兜底。
 */
@Service
public class SurveyCollaboratorService {

    @Autowired
    private SurveyCollaboratorMapper collaboratorMapper;

    @Autowired
    private UserMapper userMapper;

    /** 邀请/改角色要通知本人（"被邀请了却不知道"是这类功能最常见的坑） */
    @Autowired
    private NotificationService notificationService;

    /**
     * H5 额度钱包：协作者名额前 {@link WalletFeature#FREE_COLLABORATORS} 名免费，之后每名 1 点。
     * invite 因此加 @Transactional：扣费与插入必须原子。
     */
    @Autowired
    private WalletService walletService;

    /** 某份问卷的协作者列表（含昵称；已注销账号不列出） */
    public List<CollaboratorView> list(Survey survey) {
        return collaboratorMapper.listBySurveyId(survey.getId());
    }

    /**
     * 邀请协作者：只落一行 PENDING 并通知对方，**不扣名额费**；接受时才扣（待接受不占名额）。
     * 被拒绝/已撤销的人再次邀请时把同一行重置回 PENDING，不新增行（唯一键仍是一人一份一行）。
     *
     * @param survey         已通过归属校验的问卷
     * @param targetUsername 被邀请人的用户名
     * @param roleCode       viewer / editor / analyst
     * @param operatorUserId 邀请人（写进 created_by，便于追溯）
     */
    @Transactional
    public void invite(Survey survey, String targetUsername, String roleCode, Integer operatorUserId) {
        requireValidRole(roleCode);
        if (targetUsername == null || targetUsername.isBlank()) {
            throw new BizException("请输入要邀请的用户名");
        }
        // 按用户名而不是 id：前端让作者输账号即可，不用去查对方的数字 id
        User target = userMapper.findByUsername(targetUsername.trim());
        if (target == null) {
            // 文案不区分"不存在"与"已注销"：后者是账号隐私，没必要在接口里透露
            throw new BizException("被邀请的用户不存在或已注销");
        }
        // 协作者只能是普通用户：管理员对任何问卷本就走模块权限全权，进名单只会把账号暴露给作者、
        // 被作者改角色/移除，还会占名额计费；管理端与问卷级授权是两套东西，不能混。
        if (Role.isStaff(target.getRole())) {
            throw new BizException("不能邀请管理员账号作为协作者");
        }
        Integer targetUserId = target.getId();
        if (targetUserId.equals(operatorUserId)) {
            throw new BizException("不能把自己加为协作者");
        }
        Integer ownerId = survey.getUserId();
        if (ownerId != null && ownerId.equals(targetUserId)) {
            throw new BizException("作者本人拥有全部权限，不需要被邀请为协作者");
        }
        String existing = collaboratorMapper.findCollaboratorStatus(survey.getId(), targetUserId);
        if (CollaboratorStatus.ACCEPTED.equals(existing)) {
            String role = collaboratorMapper.findCollaboratorRole(survey.getId(), targetUserId);
            throw new BizException("该用户已经是协作者（当前角色：" + role + "），请直接改角色");
        }
        if (CollaboratorStatus.PENDING.equals(existing)) {
            throw new BizException("已经邀请过该用户，等待对方接受");
        }
        if (existing == null) {
            collaboratorMapper.insert(survey.getId(), targetUserId, roleCode, operatorUserId);
        } else if (collaboratorMapper.resetToPending(survey.getId(), targetUserId, roleCode, operatorUserId) <= 0) {
            // 并发下对方刚好接受了这条邀请：不覆盖已生效的角色
            throw new BizException("邀请状态已变化，请刷新后重试");
        }
        notificationService.send(targetUserId, "协作邀请",
                "邀请你协作问卷《" + survey.getTitle() + "》，角色：" + roleCode + "。请到「我的问卷」页接受或拒绝。",
                "collaborator", "/my/surveys");
    }

    /**
     * 接受邀请：PENDING → ACCEPTED；名额费在这一步扣（只有生效的协作者占名额，拒绝/撤销无需退款）。
     * 名额判断与扣费必须同事务，余额不足时邀请保持 PENDING，不会出现"钱扣了没生效"。
     *
     * @param role 当前用户的系统角色（X-User-Role）；管理员账号不接受协作邀请，防止存量 PENDING 生效
     */
    @Transactional
    public void accept(Integer invitationId, Integer userId, String role) {
        // 与 invite 同一口径：管理员账号不能成为协作者，存量 PENDING 也只能撤销/拒绝，不能靠接受洗白。
        if (Role.isStaff(role)) {
            throw new BizException("管理员账号不能作为协作者");
        }
        InvitationView invitation = requirePendingInvitation(invitationId, userId);
        int accepted = collaboratorMapper.countBySurvey(invitation.getSurveyId());
        if (accepted >= WalletFeature.FREE_COLLABORATORS) {
            walletService.chargeCollaborator(invitation.getSurveyId(), invitation.getOwnerId(), userId);
        }
        if (collaboratorMapper.updateStatus(invitationId, CollaboratorStatus.PENDING,
                CollaboratorStatus.ACCEPTED) <= 0) {
            throw new BizException("邀请已处理或已失效");
        }
        notificationService.send(invitation.getOwnerId(), "协作者已接受",
                "对方已接受《" + invitation.getSurveyTitle() + "》的协作邀请，角色："
                        + invitation.getCollaboratorRole(),
                "collaborator", "/my/surveys");
    }

    /** 拒绝邀请：PENDING → REJECTED；不占名额、不扣费，作者下次还能再邀请。 */
    public void reject(Integer invitationId, Integer userId) {
        InvitationView invitation = requirePendingInvitation(invitationId, userId);
        if (collaboratorMapper.updateStatus(invitationId, CollaboratorStatus.PENDING,
                CollaboratorStatus.REJECTED) <= 0) {
            throw new BizException("邀请已处理或已失效");
        }
        notificationService.send(invitation.getOwnerId(), "协作邀请被拒绝",
                "对方拒绝了《" + invitation.getSurveyTitle() + "》的协作邀请",
                "collaborator", "/my/surveys");
    }

    /** 作者撤销未接受的邀请：PENDING → REVOKED，撤销后接受接口一律 400。 */
    public void revoke(Survey survey, Integer targetUserId) {
        if (targetUserId == null) {
            throw new BizException("请选择要撤销的邀请");
        }
        String status = collaboratorMapper.findCollaboratorStatus(survey.getId(), targetUserId);
        if (!CollaboratorStatus.PENDING.equals(status)) {
            throw new BizException("该用户没有待接受的邀请");
        }
        if (collaboratorMapper.revokePending(survey.getId(), targetUserId) <= 0) {
            throw new BizException("邀请已处理或已失效");
        }
        notificationService.send(targetUserId, "协作邀请已撤销",
                "《" + survey.getTitle() + "》的协作邀请已被作者撤销", "collaborator", "/my/surveys");
    }

    /** 我的待接受邀请（只按当前用户）。 */
    public List<InvitationView> listMyInvitations(Integer userId) {
        return collaboratorMapper.findPendingInvitationsByUserId(userId);
    }

    /** 取一条仍待处理、且确实属于当前用户的邀请；不存在与不属于自己用同一句文案。 */
    private InvitationView requirePendingInvitation(Integer invitationId, Integer userId) {
        if (invitationId == null) {
            throw new BizException("邀请不存在或已失效");
        }
        InvitationView invitation = collaboratorMapper.findInvitationById(invitationId);
        if (invitation == null || !userId.equals(invitation.getUserId())) {
            throw new BizException("邀请不存在或已失效");
        }
        if (!CollaboratorStatus.PENDING.equals(invitation.getStatus())) {
            throw new BizException("邀请已处理或已失效");
        }
        return invitation;
    }

    /** 改协作者角色（必须是已存在的协作者） */
    public void changeRole(Survey survey, Integer targetUserId, String roleCode) {
        requireValidRole(roleCode);
        requireCollaborator(survey, targetUserId);
        collaboratorMapper.updateRole(survey.getId(), targetUserId, roleCode);
        notificationService.send(targetUserId, "协作角色变更",
                "你在问卷《" + survey.getTitle() + "》里的角色已改为 " + roleCode,
                "collaborator", "/my/surveys");
    }

    /**
     * 被邀请人自己退出协作；只需"我确实是协作者"，不需要问卷所有权（remove 是作者踢人）。
     */
    public void leave(Survey survey, Integer userId) {
        String current = collaboratorMapper.findCollaboratorRole(survey.getId(), userId);
        if (current == null) {
            throw new BizException("你不是这份问卷的协作者");
        }
        collaboratorMapper.delete(survey.getId(), userId);
        // 退出也要让作者知道，否则名单里少了一个人却不知是谁、为什么
        User leaver = userMapper.findById(userId);
        String who = leaver != null && leaver.getNickname() != null && !leaver.getNickname().isBlank()
                ? leaver.getNickname() : (leaver != null ? leaver.getUsername() : ("用户" + userId));
        if (survey.getUserId() != null) {
            notificationService.send(survey.getUserId(), "协作者退出",
                    who + " 退出了问卷《" + survey.getTitle() + "》的协作（原角色：" + current + "）",
                    "collaborator", "/my/surveys");
        }
    }

    /**
     * 协作者**申请**变更自己的角色：只给作者发一条通知，**不自动改**。
     * 改角色是作者的权力；自动改等于把授权交给申请人。
     */
    public void applyRole(Survey survey, Integer userId, String roleCode) {
        requireValidRole(roleCode);
        String current = collaboratorMapper.findCollaboratorRole(survey.getId(), userId);
        if (current == null) {
            throw new BizException("你不是这份问卷的协作者");
        }
        if (current.equals(roleCode)) {
            throw new BizException("你已经是 " + roleCode + " 角色，无需申请");
        }
        User me = userMapper.findById(userId);
        String who = me == null ? ("用户" + userId)
                : (me.getNickname() != null && !me.getNickname().isBlank() ? me.getNickname() : me.getUsername());
        if (survey.getUserId() != null) {
            notificationService.send(survey.getUserId(), "协作者权限申请",
                    who + " 申请把《" + survey.getTitle() + "》的协作角色从 " + current + " 改为 " + roleCode,
                    "collaborator", "/my/surveys");
        }
    }

    /** 移除协作者（必须是已生效的协作者；待接受的邀请走撤销，不动名单）。 */
    public void remove(Survey survey, Integer targetUserId) {
        if (targetUserId == null) {
            throw new BizException("请选择要操作的协作者");
        }
        String status = collaboratorMapper.findCollaboratorStatus(survey.getId(), targetUserId);
        if (CollaboratorStatus.PENDING.equals(status)) {
            throw new BizException("该邀请还没被接受，请用「撤销邀请」");
        }
        requireCollaborator(survey, targetUserId);
        collaboratorMapper.delete(survey.getId(), targetUserId);
    }

    /**
     * 给"我的问卷"列表打上"已共享 N 人"（一次 GROUP BY，不逐条查）。
     * 没有协作者的问卷记为 0（前端据此不显示角标）。
     */
    public void fillCollaboratorCounts(List<Survey> surveys) {
        if (surveys == null || surveys.isEmpty()) {
            return;
        }
        List<Integer> ids = surveys.stream().map(Survey::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return;
        }
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row : collaboratorMapper.countBySurveyIds(ids)) {
            Object sid = row.get("surveyId");
            Object total = row.get("total");
            if (sid instanceof Number s && total instanceof Number n) {
                counts.put(s.intValue(), n.intValue());
            }
        }
        for (Survey s : surveys) {
            s.setCollaboratorCount(counts.getOrDefault(s.getId(), 0));
        }
    }

    /**
     * 共享给我的问卷。
     * 必须脱敏 access_password（只用于填写人进门，不是协作授权），保留 hasPassword 供前端提示。
     */
    public List<Survey> listSharedWithMe(Integer userId) {
        List<Survey> list = collaboratorMapper.findSharedSurveys(userId);
        for (Survey s : list) {
            s.setHasPassword(s.passwordRequired());
            s.setAccessPassword(null);
        }
        return list;
    }

    /**
     * 角色合法性：只认三种，且不做大小写 / 空白归一（与 CollaboratorRole.fromCode 同一口径）。
     */
    private void requireValidRole(String roleCode) {
        if (CollaboratorRole.fromCode(roleCode) == null) {
            throw new BizException("协作者角色只能是 viewer / editor / analyst");
        }
    }

    private void requireCollaborator(Survey survey, Integer targetUserId) {
        if (targetUserId == null) {
            throw new BizException("请选择要操作的协作者");
        }
        if (collaboratorMapper.findCollaboratorRole(survey.getId(), targetUserId) == null) {
            throw new BizException("该用户不是这份问卷的协作者");
        }
    }
}
