package com.survey.dto;

import java.time.LocalDateTime;

/**
 * 协作者列表项（GET /api/survey/{id}/collaborators 的返回元素）。
 *
 * ★ 为什么单独一个 DTO，而不是直接返回实体：
 *   前端要显示"是谁"（昵称/账号），而 t_survey_collaborator 只存 user_id；
 *   让接口层做一次 JOIN 比让前端再发 N 个查用户的请求更省，也避免把整条 User（含头像）带出来。
 * ★ 字段名与 SQL 别名一致（项目开了 map-underscore-to-camel-case，但这里显式起别名，避免依赖配置）。
 */
public class CollaboratorView {

    private Integer userId;
    private String username;
    private String nickname;
    /** viewer / editor / analyst（原始值，前端按它显示中文） */
    private String collaboratorRole;
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

    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
