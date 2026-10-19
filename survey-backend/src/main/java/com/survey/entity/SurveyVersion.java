package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 问卷版本快照 —— 表 t_survey_version；questions_json 是题目与选项的完整快照，回滚/对比只依赖它。
 * 只在题目结构真的变化时生成（改标题/描述不产生版本）；每份问卷最多保留 50 版，超出删最旧。
 * 回滚 = 把快照全量覆盖写回（回滚本身也生成新版本）；versionNo 是业务号，裁剪后不回退。
 * ★ 列表接口不要查 questions_json（MB 级）：列表走 SurveyVersionSummary，只有单版/对比/回滚才查全量。
 */
public class SurveyVersion {

    private Integer id;

    /** 所属问卷 */
    private Integer surveyId;

    /** 版本号：同一问卷内从 1 递增；裁剪旧版本不会让它回退（不是行号） */
    private Integer versionNo;

    /** 快照时的问卷标题 */
    private String title;

    /** 快照时的问卷描述 */
    private String description;

    /** 题目数量（列表展示用） */
    private Integer questionCount;

    /** 题目与选项的完整 JSON 快照（按 sort_order 排序；列表接口不查这一列） */
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
