package com.survey.entity;

import java.time.LocalDateTime;

/**
 * 答卷实体 —— 对应表 t_answer，"一次提交一行"（表头）。
 *
 * ── 与 AnswerDetail 的关系 ─────────────────────────────
 *   t_answer        : 谁、在什么时候、提交了哪份问卷（本类）
 *   t_answer_detail : 这次提交里每题一行（AnswerDetail）
 *   → 统计"收到多少份答卷"用这张表；统计"某题选了什么"用明细表。
 *
 * ── 谁在用 ────────────────────────────────────────────
 *   AnswerService.submit()（插入）、AnswerMapper、
 *   SurveyStatistics.vue（答卷列表表格）
 */
public class Answer {
    private Integer id;
    private Integer surveyId;        // 答的哪份问卷
    private Integer userId;          // 填写者 ID，**NULL 表示匿名**（凭分享码提交，不要求登录）
    private LocalDateTime submitTime; // 提交时间（数据库默认值填充）

    /**
     * 答题耗时（毫秒）。由答题页在提交时上报；NULL = 未上报（老客户端 / 直接调接口）。
     * ★ 这是**客户端提供的值，可以伪造** —— 它只用于"质控提示"，绝不能当安全边界或计费依据。
     */
    private Integer durationMs;

    /** 质控标记：0 = 正常，1 = 可疑（命中任一无效样本规则） */
    private Integer qualityFlag;

    /** 命中的规则码，逗号分隔（TOO_FAST / STRAIGHT_LINE / OPTION_MISMATCH / DUP_TITLE_CONFLICT） */
    private String qualityReasons;

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
}