package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 反馈 / 客服工单头 —— 表 t_feedback。
 * ★ 只关闭不物理删除；type=SERVICE 时同一用户最多一个 OPEN（由生成列唯一索引保证）。
 * ★ last_message_* 是列表排序与轮询增量的唯一依据，发消息后必须同步更新。
 */
public class Feedback {

    private Integer id;
    private Integer userId;
    /** FEEDBACK=反馈 / SERVICE=客服工单 */
    private String type;
    private String subject;
    /** OPEN=处理中 / CLOSED=已关闭 */
    private String status;
    /** 最近处理的管理员 */
    private Integer handledBy;
    private Long lastMessageId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastMessageTime;
    private String lastMessagePreview;
    private Long userLastReadMessageId;
    private Long adminLastReadMessageId;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getHandledBy() { return handledBy; }
    public void setHandledBy(Integer handledBy) { this.handledBy = handledBy; }

    public Long getLastMessageId() { return lastMessageId; }
    public void setLastMessageId(Long lastMessageId) { this.lastMessageId = lastMessageId; }

    public LocalDateTime getLastMessageTime() { return lastMessageTime; }
    public void setLastMessageTime(LocalDateTime lastMessageTime) { this.lastMessageTime = lastMessageTime; }

    public String getLastMessagePreview() { return lastMessagePreview; }
    public void setLastMessagePreview(String lastMessagePreview) { this.lastMessagePreview = lastMessagePreview; }

    public Long getUserLastReadMessageId() { return userLastReadMessageId; }
    public void setUserLastReadMessageId(Long userLastReadMessageId) { this.userLastReadMessageId = userLastReadMessageId; }

    public Long getAdminLastReadMessageId() { return adminLastReadMessageId; }
    public void setAdminLastReadMessageId(Long adminLastReadMessageId) { this.adminLastReadMessageId = adminLastReadMessageId; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }

    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
}
