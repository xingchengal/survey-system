package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 系统公告 —— 表 t_announcement。
 * status：1=上架（用户可见）/ 0=下架（仅管理员可见）；expireTime 为 null = 永不过期，
 * findActive 的口径是「已上架且（无过期时间或未过期）」。
 * ★ 已读状态不在库里（前端 localStorage 存 readAnnouncements_&lt;userId&gt;），清缓存会重新弹一次。
 */
public class Announcement {
    private Integer id;
    private String title;
    private String content;
    private Integer publisherId;     // 发布者 ID（Controller 从登录身份填入）
    private String publisherName;    // 发布者昵称；查不到用户才回退「管理员」
    private Integer status;          // 1=上架，0=下架

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;   // null = 永不过期

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getPublisherId() { return publisherId; }
    public void setPublisherId(Integer publisherId) { this.publisherId = publisherId; }

    public String getPublisherName() { return publisherName; }
    public void setPublisherName(String publisherName) { this.publisherName = publisherName; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getExpireTime() { return expireTime; }
    public void setExpireTime(LocalDateTime expireTime) { this.expireTime = expireTime; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}