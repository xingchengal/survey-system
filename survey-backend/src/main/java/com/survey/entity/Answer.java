package com.survey.entity;

import java.time.LocalDateTime;

/**
 * 答卷表头 —— 表 t_answer，一次提交一行；每题明细在 {@link AnswerDetail}（t_answer_detail）。
 * 统计「收到多少份答卷」用本表，统计「某题选了什么」用明细表。
 */
public class Answer {
    private Integer id;
    private Integer surveyId;        // 答的哪份问卷
    private Integer userId;          // 填写者 ID，NULL = 匿名（凭分享码提交，不要求登录）
    private LocalDateTime submitTime; // 提交时间（数据库默认值填充）

    /** 答题耗时（毫秒），NULL = 未上报。★ 客户端上报可伪造，只用于质控提示，不能当安全边界或计费依据 */
    private Integer durationMs;

    /** 质控标记：0=正常，1=可疑（命中任一无效样本规则） */
    private Integer qualityFlag;

    /** 命中的规则码，逗号分隔（TOO_FAST / STRAIGHT_LINE / OPTION_MISMATCH / DUP_TITLE_CONFLICT） */
    private String qualityReasons;

    /** 本次总分（提交时的配置快照），NULL = 未判分。★ 存快照，作者事后改分值才改不动已出的成绩 */
    private Integer totalScore;

    /** 本次得分，NULL = 未判分 */
    private Integer earnedScore;

    /** 是否及格：NULL = 未设及格线或未判分；0/1 由 {@link com.survey.common.Grader} 判定 */
    private Integer isPassed;

    /** 是否已签到：0=未签到（默认），1=已签到；只有问卷启用签到核销后作者才会改它 */
    private Integer checkedIn;

    /** 签到时间；取消签到时置回 NULL */
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime checkedInAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getSurveyId() { return surveyId; }
    public void setSurveyId(Integer surveyId) { this.surveyId = surveyId; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public LocalDateTime getSubmitTime() { return submitTime; }
    public void setSubmitTime(LocalDateTime submitTime) { this.submitTime = submitTime; }

    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }

    public Integer getQualityFlag() { return qualityFlag; }
    public void setQualityFlag(Integer qualityFlag) { this.qualityFlag = qualityFlag; }

    public String getQualityReasons() { return qualityReasons; }
    public void setQualityReasons(String qualityReasons) { this.qualityReasons = qualityReasons; }

    public Integer getTotalScore() { return totalScore; }
    public void setTotalScore(Integer totalScore) { this.totalScore = totalScore; }

    public Integer getEarnedScore() { return earnedScore; }
    public void setEarnedScore(Integer earnedScore) { this.earnedScore = earnedScore; }

    public Integer getIsPassed() { return isPassed; }
    public void setIsPassed(Integer isPassed) { this.isPassed = isPassed; }

    public Integer getCheckedIn() { return checkedIn; }
    public void setCheckedIn(Integer checkedIn) { this.checkedIn = checkedIn; }

    public LocalDateTime getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(LocalDateTime checkedInAt) { this.checkedInAt = checkedInAt; }
}