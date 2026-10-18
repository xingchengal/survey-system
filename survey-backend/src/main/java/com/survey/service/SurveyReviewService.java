package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.SurveyStatus;
import com.survey.entity.Survey;
import com.survey.common.SurveyLifecycle;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyReviewMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 举报提交与"我的举报"。
 *
 * ── 规则（每条都会返回 400，而不是静默忽略）────────────
 *   ① 只有**已发布**的问卷能被举报（草稿/已结束的没有对外可见性，举报没有意义）；
 *   ② 不能举报自己的问卷（作者自己下架即可，不需要走举报）；
 *   ③ 同一人对同一问卷**待处理的举报只能有一条**（防止刷屏把审核台淹没）；
 *   ④ 理由必填，长度 1~500（与列宽一致，超长会被 MySQL 拒绝成 500）。
 *
 * ★ 审核动作（通过=下架 / 驳回）在 H1-3，本类只负责"收举报"。
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
    }

    /** 管理端列表（status 为 null 表示全部） */
    public List<Map<String, Object>> listForAdmin(String status) {
        return reviewMapper.findForAdmin(status);
    }

    /**
     * 处理一条举报。
     *
     * @param action SUSPEND（通过并下架）/ IGNORE（驳回）
     * ★ 幂等靠 SQL 的 `AND status='PENDING'`：重复处理影响 0 行 → 抛 400，不会二次下架/二次通知。
     * ★ 下架只改 lifecycle，**不动 status** —— 前端"已发布/已结束"的既有口径不受影响。
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
