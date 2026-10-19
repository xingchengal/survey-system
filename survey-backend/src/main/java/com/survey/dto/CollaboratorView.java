package com.survey.dto;

import java.time.LocalDateTime;

/**
 * 协作者名单项（GET /api/survey/{id}/collaborators 的元素）：账号 / 昵称 / 角色 / 邀请时间。
 * ★ 角色到权限点：viewer 只看问卷本身；editor = survey:edit + survey:data；analyst = survey:data。
 * 单独 DTO 是为了连表带出账号昵称，省掉前端按 user_id 逐个查人。
 */
public class CollaboratorView {

    private Integer userId;
    private String username;
    private String nickname;
    /** viewer / editor / analyst（原始值，前端按它显示中文） */
    private String collaboratorRole;
    /** PENDING=待接受 / ACCEPTED=已生效（列表只列这两种） */
    private String status;
    /** 邀请人 id */
    private Integer createdBy;
    private LocalDateTime createdTime;

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getCollaboratorRole() { return collaboratorRole; }
    public void setCollaboratorRole(String collaboratorRole) { this.collaboratorRole = collaboratorRole; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
