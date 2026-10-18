package com.survey.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 「我答过的问卷」列表项（GET /api/answer/my）。
 *
 * ── 为什么把问卷字段一起查出来 ──────────────────────────
 *   列表要显示"问卷标题 / 是否已结束 / 还能不能再进"，如果只查 t_answer，
 *   前端就得对每条记录再发一次问卷请求（N+1）。这里用一条 JOIN 一次取回。
 *
 * ── 字段口径 ────────────────────────────────────────────
 *   · {@code earnedScore == null} 表示**未判分**（普通问卷或判分上线前的历史答卷）——
 *     前端显示"未判分"，不要显示成 0 分；
 *   · {@code deleted} / {@code status} 用来决定"还能不能打开"：
 *     已删除或非已发布的问卷，列表里仍保留记录（历史不该凭空消失），但禁用"去填写"。
 *   · {@code shareCode} 为 null（草稿/从未发布）时同样没有可打开的链接。
 *
 * ★ 只按登录用户查：匿名答卷没有身份可回溯，永远查不到 —— 这是产品口径，不是缺陷。
 */
public class MyAnswerItem {

    private Integer answerId;
    private Integer surveyId;
    private String surveyTitle;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime submitTime;

    /** 提交时的总分快照；未判分为 null */
    private Integer totalScore;
    /** 本次得分；未判分为 null */
    private Integer earnedScore;
    /** 是否及格：null = 未设及格线或未判分 */
    private Integer isPassed;
    /** 问卷当前的及格线（用于展示"及格线 N 分"） */
    private Integer passScore;
    /** 问卷当前是否开启判分（0/1） */
    private Integer gradeEnabled;

    /** 分享码：可用来打开问卷（null = 草稿/从未发布） */
    private String shareCode;
    /** 问卷状态：0=草稿，1=已发布，2=已结束 */
    private Integer status;
    /** 是否在回收站：0=正常，1=已删除 */
    private Integer deleted;

    public Integer getAnswerId() { return answerId; }
    public void setAnswerId(Integer answerId) { this.answerId = answerId; }

    public Integer getSurveyId() { return surveyId; }
    public void setSurveyId(Integer surveyId) { this.surveyId = surveyId; }

    public String getSurveyTitle() { return surveyTitle; }
    public void setSurveyTitle(String surveyTitle) { this.surveyTitle = surveyTitle; }

    public LocalDateTime getSubmitTime() { return submitTime; }
    public void setSubmitTime(LocalDateTime submitTime) { this.submitTime = submitTime; }

    public Integer getTotalScore() { return totalScore; }
    public void setTotalScore(Integer totalScore) { this.totalScore = totalScore; }

    public Integer getEarnedScore() { return earnedScore; }
    public void setEarnedScore(Integer earnedScore) { this.earnedScore = earnedScore; }

    public Integer getIsPassed() { return isPassed; }
    public void setIsPassed(Integer isPassed) { this.isPassed = isPassed; }

    public Integer getPassScore() { return passScore; }
    public void setPassScore(Integer passScore) { this.passScore = passScore; }

    public Integer getGradeEnabled() { return gradeEnabled; }
    public void setGradeEnabled(Integer gradeEnabled) { this.gradeEnabled = gradeEnabled; }

    public String getShareCode() { return shareCode; }
    public void setShareCode(String shareCode) { this.shareCode = shareCode; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
