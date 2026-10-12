package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 问卷实体 —— 对应表 t_survey，全项目的核心业务对象。
 *
 * ── 三个状态字段（最容易搞混，务必分清）────────────────
 *   status      : 业务状态  0=草稿  1=已发布  2=已结束
 *   deleted     : 是否在回收站  0=正常  1=回收站（逻辑删除，配合 deletedBy 记录操作人）
 *   isTemplate  : 是不是模板  0=普通问卷  1=模板
 *
 * ── 谁在用 ────────────────────────────────────────────
 *   SurveyController / SurveyService / SurveyMapper，
 *   以及前端几乎所有页面（问卷列表、编辑、统计、回收站、模板广场）
 *
 * ── 说明：deletedByName 不是数据库列 ────────────────────
 *   它只用于展示，值来自回收站查询里 JOIN t_user 取到的昵称
 *   （SQL 里 SELECT u.nickname AS deleted_name，再由 mapper 的 @Results 映射过来）。
 *   普通查询拿不到它，所以列表页里该字段是 null。
 */
public class Survey {
    private Integer id;
    private Integer userId;          // 创建者 ID。★ 归属校验就是拿它和登录用户比
    private String title;
    private String description;
    private Integer status;          // 0=草稿，1=已发布，2=已结束
    private String shareCode;        // 分享码（6 位小写字母数字，唯一索引；由服务端生成）

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime; // 定时发布时间（到点后定时任务自动发布）

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;   // 截止时间（到点后定时任务自动关闭）

    private Integer deleted;         // 0=正常，1=在回收站
    private Integer deletedBy;       // 删除人 ID（移入回收站时记录）
    private String deletedByName;    // 删除人昵称（仅用于展示，不映射数据库列）
    private Integer viewCount;       // 访问次数（访客打开分享链接时 +1）

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedTime;

    // ===== Getter/Setter =====
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getShareCode() { return shareCode; }
    public void setShareCode(String shareCode) { this.shareCode = shareCode; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }

    public Integer getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Integer deletedBy) { this.deletedBy = deletedBy; }

    public String getDeletedByName() { return deletedByName; }
    public void setDeletedByName(String deletedByName) { this.deletedByName = deletedByName; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }

    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }

    public Integer getViewCount() { return viewCount; }
    public void setViewCount(Integer viewCount) { this.viewCount = viewCount; }

    private Integer isTemplate;       // 0=普通问卷，1=模板
    private String templateCategory;  // 模板分类

    public Integer getIsTemplate() { return isTemplate; }
    public void setIsTemplate(Integer isTemplate) { this.isTemplate = isTemplate; }

    public String getTemplateCategory() { return templateCategory; }
    public void setTemplateCategory(String templateCategory) { this.templateCategory = templateCategory; }
}