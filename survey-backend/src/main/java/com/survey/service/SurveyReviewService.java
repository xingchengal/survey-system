package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.common.SurveyStatus;
import com.survey.entity.Survey;
import com.survey.common.SurveyLifecycle;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyReviewMapper;
import com.survey.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 举报提交与处理（t_survey_review）：report 收举报，handle 审核（通过=下架 / 驳回）并通知双方。
 * ★ 只允许 PENDING → 终态：SQL 的 WHERE status='PENDING' 保证重复处理影响 0 行，不会二次下架 / 二次通知。
 * ★ 当前 handle **未校验**“处理人 ≠ 举报人”；多管理员场景下需在入口补这一条（已记入 SPRINT 待办）。
 * 通过 = 只改 lifecycle 为 SUSPENDED、不动 status，并发通知作者与举报人。
 */
@Service
public class SurveyReviewService {

    private static final int MAX_REASON = 500;

    @Autowired
    private SurveyReviewMapper reviewMapper;

    @Autowired
    private SurveyMapper surveyMapper;

    @Autowired
    private NotificationService notificationService;

    /** 举报通知按 review:handle 发，不能发给全部管理员。 */
    @Autowired
    private UserMapper userMapper;

    /** 提交举报：只能举报已发布的他人问卷，同一人同一问卷只允许一条待处理举报，理由 1~500 字。 */
    public void report(Survey survey, Integer reporterId, String reason) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (!SurveyStatus.isPublished(survey.getStatus())) {
            throw new BizException("只能举报已发布的问卷");
        }
        if (survey.getUserId() != null && survey.getUserId().equals(reporterId)) {
            throw new BizException("不能举报自己的问卷");
        }
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new BizException("请填写举报理由");
        }
        if (text.length() > MAX_REASON) {
            throw new BizException("举报理由不能超过 " + MAX_REASON + " 个字");
        }
        if (reviewMapper.countPending(survey.getId(), reporterId) > 0) {
            throw new BizException("你对该问卷的举报还在处理中，请等待结果");
        }
        reviewMapper.insert(survey.getId(), reporterId, text);
        // 通知有 review:handle 的人：超管 + 被授权审核的次级管理员；没权限的不打扰。
        for (Integer staffId : userMapper.findStaffIdsByPermission(Permission.REVIEW_HANDLE)) {
            notificationService.send(staffId, "新的举报",
                    "《" + survey.getTitle() + "》被举报：" + text,
                    "review", "/admin/reviews");
        }
    }

    /** 管理端列表（status 为 null 表示全部） */
    public List<Map<String, Object>> listForAdmin(String status) {
        return reviewMapper.findForAdmin(status);
    }

    /**
     * 处理一条举报（幂等靠 SQL 的 AND status='PENDING'：重复处理影响 0 行 → 400）。
     * SUSPEND = 通过并下架，只改 lifecycle、不动 status；IGNORE = 驳回。结果通知作者与举报人。
     *
     * @param action SUSPEND / IGNORE
     */
    public void handle(Integer reviewId, Integer adminId, String action) {
        if (!"SUSPEND".equals(action) && !"IGNORE".equals(action)) {
            throw new BizException("处理动作只能是 SUSPEND 或 IGNORE");
        }
        Map<String, Object> review = reviewMapper.findById(reviewId);
        if (review == null) {
            throw new BizException("举报不存在");
        }
        if (!"PENDING".equals(review.get("status"))) {
            throw new BizException("该举报已处理过");
        }
        Integer surveyId = (Integer) review.get("surveyId");
        Integer reporterId = (Integer) review.get("reporterId");
        Survey survey = surveyMapper.findById(surveyId);

        int affected = reviewMapper.handle(reviewId,
                "SUSPEND".equals(action) ? "APPROVED" : "REJECTED", action, adminId);
        if (affected == 0) {
            throw new BizException("该举报已处理过");   // 并发下第二次处理
        }

        if ("SUSPEND".equals(action) && survey != null) {
            surveyMapper.updateLifecycle(surveyId, SurveyLifecycle.SUSPENDED.code());
            if (survey.getUserId() != null) {
                notificationService.send(survey.getUserId(), "问卷被下架",
                        "你的问卷《" + survey.getTitle() + "》因举报被管理员下架，请修改后联系管理员重新上架",
                        "review", "/my/surveys");
            }
        }
        // 举报人也要知道结果（否则"举报了没下文"）
        notificationService.send(reporterId, "举报处理结果",
                "你对《" + (survey == null ? "问卷" : survey.getTitle()) + "》的举报已处理："
                        + ("SUSPEND".equals(action) ? "内容违规，已被下架" : "经核实未违规，已驳回"),
                "review", "/my/surveys");
    }

    public List<Map<String, Object>> myReports(Integer reporterId) {
        return reviewMapper.findByReporter(reporterId);
    }
}
