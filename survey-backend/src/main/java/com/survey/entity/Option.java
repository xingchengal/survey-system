package com.survey.entity;

public class Option {
    private Integer id;
    private Integer questionId;   // 所属题目ID
    private String content;       // 选项内容
    private Integer sortOrder;    // 排序
    private Integer nextQuestionId;

    // ===== Getter/Setter =====
    public Integer getNextQuestionId() { return nextQuestionId; }
    public void setNextQuestionId(Integer nextQuestionId) { this.nextQuestionId = nextQuestionId; }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getQuestionId() { return questionId; }
    public void setQuestionId(Integer questionId) { this.questionId = questionId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}