package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.CollaboratorRole;
import com.survey.dto.CollaboratorView;
import com.survey.entity.Survey;
import com.survey.entity.User;
import com.survey.mapper.SurveyCollaboratorMapper;
import com.survey.mapper.UserMapper;
import com.survey.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 问卷协作者的管理（邀请 / 改角色 / 移除 / 列表）。
 *
 * ── 谁能管理协作者 ────────────────────────────────────
 *   **只有作者本人与管理员**（Controller 用 {@code OwnershipGuard.requireOwnerOrAdmin} 判）。
 *   ★ 为什么不让 editor 协作者再邀请人：那会出现"权限扩散"——
 *     一个人被邀请，就能把整个部门拉进来；协作者名单只该由问卷的主人掌握。
 *
 * ── 业务校验（都在这里，Controller 只做归属校验）─────────
 *   ① 角色只能是 viewer / editor / analyst（大小写敏感，解析失败即 400）；
 *   ② 被邀请人必须存在且**未注销**（已注销账号留着授权没有意义）；
 *   ③ 不能邀请自己；
 *   ④ 不能邀请作者本人（作者本来就全权，多一条协作者记录只会让列表更乱）；
 *   ⑤ 重复邀请 / 对非协作者改角色或移除 → 400（不静默成功）。
 *
 * ── 为什么没有 @Transactional ─────────────────────────
 *   每个方法都只有**一条**写语句（insert / update / delete），
 *   多语句事务在这里没有意义；加了反而给人"这里有原子性保证"的错觉。
 *   ★ 真正的并发风险是"同一个用户被同时邀请两次"：由唯一键
 *     uk_survey_user(survey_id,user_id) 兜底 —— 第二个请求会拿到 DuplicateKey，
 *     这里不额外捕获，让它变成 500（极端并发下的脏请求，值得被看见）。
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

    /** 某份问卷的协作者列表（含昵称；已注销账号不列出） */
    public List<CollaboratorView> list(Survey survey) {
        return collaboratorMapper.listBySurveyId(survey.getId());
    }

    /**
     * 邀请协作者。
     *
     * @param survey         已通过归属校验的问卷
     * @param targetUserId   被邀请的用户
     * @param roleCode       viewer / editor / analyst
     * @param operatorUserId 邀请人（写进 created_by，便于追溯）
     */
    public void invite(Survey survey, String targetUsername, String roleCode, Integer operatorUserId) {
        requireValidRole(roleCode);
        if (targetUsername == null || targetUsername.isBlank()) {
            throw new BizException("请输入要邀请的用户名");
        }
        // ★ 按用户名而不是 id：前端让作者输账号即可，不用去查对方的数字 id
        User target = userMapper.findByUsername(targetUsername.trim());
        if (target == null) {
            // ★ 文案不区分"不存在"与"已注销"：后者是账号隐私，没必要在接口里透露
            throw new BizException("被邀请的用户不存在或已注销");
        }
        Integer targetUserId = target.getId();
        if (targetUserId.equals(operatorUserId)) {
            throw new BizException("不能把自己加为协作者");
        }
        Integer ownerId = survey.getUserId();
        if (ownerId != null && ownerId.equals(targetUserId)) {
            throw new BizException("作者本人拥有全部权限，不需要被邀请为协作者");
        }
        String existing = collaboratorMapper.findCollaboratorRole(survey.getId(), targetUserId);
        if (existing != null) {
            throw new BizException("该用户已经是协作者（当前角色：" + existing + "），请直接改角色");
        }
        collaboratorMapper.insert(survey.getId(), targetUserId, roleCode, operatorUserId);
        // ★ 通知被邀请人：不通知的话对方根本不知道自己被拉进来了
        notificationService.send(targetUserId, "协作邀请",
                "邀请你协作问卷《" + survey.getTitle() + "》，角色：" + roleCode,
                "collaborator", "/my/surveys");
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
     * 被邀请人**自己退出**协作。
     * ★ 与 remove 的区别：remove 是作者踢人（要归属校验），这个是本人主动退出 ——
     *   只需"我确实是这份问卷的协作者"，不需要对问卷有任何所有权。
     */
    public void leave(Survey survey, Integer userId) {
        String current = collaboratorMapper.findCollaboratorRole(survey.getId(), userId);
        if (current == null) {
            throw new BizException("你不是这份问卷的协作者");
        }
        collaboratorMapper.delete(survey.getId(), userId);
        // ★ 退出也要让作者知道：否则作者只看到名单里少了一个人，不知道是谁、为什么
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
     * ★ 改角色是作者的权力；自动改等于把授权交给申请人。
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

    /** 移除协作者（必须是已存在的协作者） */
    public void remove(Survey survey, Integer targetUserId) {
        requireCollaborator(survey, targetUserId);
        collaboratorMapper.delete(survey.getId(), targetUserId);
    }

    /**
     * 给"我的问卷"列表打上"已共享 N 人"（一次 GROUP BY，不逐条查）。
     * ★ 没有协作者的问卷记为 0（前端据此不显示角标）。
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
     * ★ **必须脱敏**：t_survey 那行带 access_password，协作者不该看到作者设的答题口令
     *   （它只用于"填写人进门"，不是协作授权）。这里统一置空，并保留 hasPassword 供前端提示。
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
     * 角色合法性：只认三种，且**不做大小写/空白归一** ——
     * 与 CollaboratorRole.fromCode 同一口径（写错就是错，不做"宽容解析"）。
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
