package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 系统公告实体 —— 对应表 t_announcement。
 *
 * ── 谁在用 ────────────────────────────────────────────
 *   AnnouncementController / AnnouncementService / AnnouncementMapper、
 *   AnnouncementManage.vue（管理员管理页）、AdminLayout.vue（用户端弹窗展示 /active）
 *
 * ── 两个控制字段 ──────────────────────────────────────
 *   status     : 1=上架（用户能看到）  0=下架（仅管理员可见）
 *   expireTime : 过期时间，**null 表示永不过期**；
 *                findActive 查询的条件是"已上架 且（无过期时间 或 未过期）"。
 *
 * ── 关于"已读" ────────────────────────────────────────
 *   公告的已读状态**不在数据库里**，前端存在 localStorage
 *   （AdminLayout.vue 的 readAnnouncements_&lt;userId&gt; 键）。
 *   所以清浏览器缓存后，公告会重新弹一次。
 */
public class Announcement {
    private Integer id;
    private String title;
    private String content;
    private Integer publisherId;     // 发布者 ID（Controller 从登录身份填入）
    private String publisherName;    // 发布者昵称：由 Controller 取真实昵称（查不到用户才回退"管理员"）
    private Integer status;          // 1=发布，0=下架

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;   // 过期时间，null = 永不过期

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