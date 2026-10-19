package com.survey.dto;

import java.time.LocalDateTime;

/**
 * 「我的待接受邀请」（GET /api/collaborator/invitations）的元素。
 * ★ 只按当前登录用户的 user_id 查，接口不接受任何"查别人"的参数。
 * ★ 带问卷标题与作者昵称，前端不用再逐个查问卷/用户。
 */
public class InvitationView {

    /** 邀请行 id（接受/拒绝都按它定位） */
    private Integer id;
    /** 被邀请人（服务层校验归属用，前端不需要展示） */
    private Integer userId;
    private Integer surveyId;
    private String surveyTitle;
    /** 问卷作者（接受后扣名额费的对象） */
    private Integer ownerId;
    private String ownerUsername;
    private String ownerNickname;
    private String collaboratorRole;
    /** PENDING / ACCEPTED / REJECTED / REVOKED */
    private String status;
    private LocalDateTime createdTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public Integer getSurveyId() { return surveyId; }
    public void setSurveyId(Integer surveyId) { this.surveyId = surveyId; }

    public String getSurveyTitle() { return surveyTitle; }
    public void setSurveyTitle(String surveyTitle) { this.surveyTitle = surveyTitle; }

    public Integer getOwnerId() { return ownerId; }
    public void setOwnerId(Integer ownerId) { this.ownerId = ownerId; }

    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }

    public String getOwnerNickname() { return ownerNickname; }
    public void setOwnerNickname(String ownerNickname) { this.ownerNickname = ownerNickname; }

    public String getCollaboratorRole() { return collaboratorRole; }
    public void setCollaboratorRole(String collaboratorRole) { this.collaboratorRole = collaboratorRole; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
