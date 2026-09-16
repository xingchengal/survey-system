package com.survey.entity;

public class AnswerDetail {
    private Integer id;
    private Integer answerId;
    private Integer questionId;
    private String content;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getAnswerId() { return answerId; }
    public void setAnswerId(Integer answerId) { this.answerId = answerId; }

    public Integer getQuestionId() { return questionId; }
    public void setQuestionId(Integer questionId) { this.questionId = questionId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}