package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.CollaboratorRole;
import com.survey.common.WalletFeature;
import com.survey.dto.CollaboratorView;
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
     * 邀请协作者。
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
        // 名额收费：前 FREE_COLLABORATORS 名免费，之后每名 1 点（同一人只扣一次）。
        //   余额不足会在这里 400，邀请不落库（与扣费同事务）。
        int currentCount = collaboratorMapper.countBySurvey(survey.getId());
        if (currentCount >= WalletFeature.FREE_COLLABORATORS) {
            walletService.chargeCollaborator(survey.getId(), survey.getUserId(), targetUserId);
        }
        collaboratorMapper.insert(survey.getId(), targetUserId, roleCode, operatorUserId);
        // 通知被邀请人：不通知的话对方根本不知道自己被拉进来了
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

    /** 移除协作者（必须是已存在的协作者） */
    public void remove(Survey survey, Integer targetUserId) {
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
