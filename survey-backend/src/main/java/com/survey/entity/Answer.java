package com.survey.entity;

import java.time.LocalDateTime;

public class Answer {
    private Integer id;
    private Integer surveyId;
    private Integer userId;          // 填写者ID，NULL表示匿名
    private LocalDateTime submitTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getSurveyId() { return surveyId; }
    public void setSurveyId(Integer surveyId) { this.surveyId = surveyId; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public LocalDateTime getSubmitTime() { return submitTime; }
    public void setSubmitTime(LocalDateTime submitTime) { this.submitTime = submitTime; }
}