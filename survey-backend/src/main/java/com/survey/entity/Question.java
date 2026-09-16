package com.survey.entity;

import java.util.List;

public class Question {
    private Integer id;
    private Integer surveyId;      // 所属问卷ID
    private Integer type;          // 题型：1=单选，2=多选，3=填空
    private String title;          // 题目内容
    private Integer required;      // 是否必填：0=否，1=是
    private Integer sortOrder;     // 排序

    // 关键：用来接收前端传过来的选项列表
    private List<Option> options;

    // ===== Getter/Setter =====
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getSurveyId() { return surveyId; }
    public void setSurveyId(Integer surveyId) { this.surveyId = surveyId; }

    public Integer getType() { return type; }
    public void setType(Integer type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Integer getRequired() { return required; }
    public void setRequired(Integer required) { this.required = required; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public List<Option> getOptions() { return options; }
    public void setOptions(List<Option> options) { this.options = options; }
}