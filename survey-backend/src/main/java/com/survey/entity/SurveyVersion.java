package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 问卷版本快照（`t_survey_version` 的一行）。
 *
 * ── 它存什么 ──────────────────────────────────────────
 *   `questions_json` 是**整份问卷题目与选项的完整 JSON 快照**（按 sort_order 排序），
 *   加上当时的标题/描述。回滚与对比都只依赖这份 JSON，不再回查 `t_question`/`t_option`。
 *
 * ── 为什么要冗余存 title / description / question_count ──
 *   · title/description：改标题**不会**生成新版本（否则版本会爆炸），
 *     但对比两个版本时要能看出"标题当时是什么"，所以每个快照都带上；
 *   · question_count：版本列表要显示"这一版有几道题"，存一个整数就不用解析 JSON。
 *
 * ── ★ 列表接口不要返回 questions_json ────────────────────
 *   一份 200 题、每题 50 个选项的问卷，JSON 是 MB 级的；版本列表动辄几十条。
 *   所以 `SurveyVersionMapper.findBySurveyId` **不查这一列**，
 *   列表用 {@link com.survey.dto.SurveyVersionSummary}，只有"读单个版本/对比/回滚"才查全量。
 */
public class SurveyVersion {

    private Integer id;

    /** 所属问卷 */
    private Integer surveyId;

    /** 版本号：同一问卷内从 1 递增。★ 裁剪旧版本不会让它回退（不是行号） */
    private Integer versionNo;

    /** 快照时的问卷标题 */
    private String title;

    /** 快照时的问卷描述 */
    private String description;

    /** 题目数量（列表展示用） */
    private Integer questionCount;

    /** 题目与选项的完整 JSON 快照 */
    private String questionsJson;

    /** 相对上一版本的变更摘要（自动生成，便于列表直接展示） */
    private String changeSummary;

    /** 操作人 id（谁保存/回滚产生这一版） */
    private Integer createdBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getSurveyId() {
        return surveyId;
    }

    public void setSurveyId(Integer surveyId) {
        this.surveyId = surveyId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }

    public String getQuestionsJson() {
        return questionsJson;
    }

    public void setQuestionsJson(String questionsJson) {
        this.questionsJson = questionsJson;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    public void setChangeSummary(String changeSummary) {
        this.changeSummary = changeSummary;
    }

    public Integer getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Integer createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(LocalDateTime createdTime) {
        this.createdTime = createdTime;
    }
}
