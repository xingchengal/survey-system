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

    /**
     * 移入回收站的时间：NULL = 不在回收站，非 NULL = 何时被删。
     * ★ 回收站列表展示"什么时候删的"用它，而不是 created_time（创建时间可能是几周前）。
     * ★ 由 SurveyMapper.moveToRecycle 写 NOW()、restoreFromRecycle 置回 NULL；
     *   对历史上已删除的数据，migration-survey-deleted-at.sql 用 updated_time 做了回填。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deletedAt;

    private Integer viewCount;       // 访问次数（访客打开分享链接时 +1）

    // ===== 问卷设置（编辑页「设置」面板；问卷星「基本设置 / 提交后显示」的常用项）=====

    /**
     * 答题密码：NULL = 不需要密码。
     * ★ 非空时，加载题目与提交答卷都必须带请求头 X-Survey-Password，
     *   服务端在两个入口各校验一次（前端弹密码框只是体验层）。
     * ★ 对外接口（/share/{code}）返回前会被置空，只暴露 hasPassword。
     */
    private String accessPassword;

    /** 最多收集多少份答卷：NULL = 不限；达到后 submit 直接 400。 */
    private Integer maxResponses;

    /** 提交后的感谢语：NULL = 用系统默认文案。 */
    private String thankText;

    /** 提交后跳转的链接：NULL = 不跳转；只允许 http/https（防 javascript: 伪协议）。 */
    private String redirectUrl;

    /** 是否公开结果页：0 = 不公开，1 = 开启（匿名可访问 /api/survey/result/{shareCode}）。 */
    private Integer publicResult;

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

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }

    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }

    public Integer getViewCount() { return viewCount; }
    public void setViewCount(Integer viewCount) { this.viewCount = viewCount; }

    public String getAccessPassword() { return accessPassword; }
    public void setAccessPassword(String accessPassword) { this.accessPassword = accessPassword; }

    /**
     * 前端据此决定要不要弹密码框（对外接口返回的字段）。
     * ★ 必须是一个**独立字段**：/share/{code} 会先把 accessPassword 置空再返回，
     *   如果这里是 `isHasPassword()` 这种从 accessPassword 现算的派生 getter，
     *   置空之后 hasPassword 会跟着变成 false —— 前端就不会弹密码框了。
     */
    private Boolean hasPassword;

    public Boolean getHasPassword() { return hasPassword; }
    public void setHasPassword(Boolean hasPassword) { this.hasPassword = hasPassword; }

    /**
     * 内部判定：这份问卷是否设了答题密码。
     * ★ 刻意**不是** getter 命名，避免 Jackson 把它也序列化成 hasPassword（与上面的字段撞名）。
     */
    public boolean passwordRequired() {
        return accessPassword != null && !accessPassword.isBlank();
    }

    public Integer getMaxResponses() { return maxResponses; }
    public void setMaxResponses(Integer maxResponses) { this.maxResponses = maxResponses; }

    public String getThankText() { return thankText; }
    public void setThankText(String thankText) { this.thankText = thankText; }

    public String getRedirectUrl() { return redirectUrl; }
    public void setRedirectUrl(String redirectUrl) { this.redirectUrl = redirectUrl; }

    public Integer getPublicResult() { return publicResult; }
    public void setPublicResult(Integer publicResult) { this.publicResult = publicResult; }

    private Integer isTemplate;       // 0=普通问卷，1=模板
    private String templateCategory;  // 模板分类

    public Integer getIsTemplate() { return isTemplate; }
    public void setIsTemplate(Integer isTemplate) { this.isTemplate = isTemplate; }

    public String getTemplateCategory() { return templateCategory; }
    public void setTemplateCategory(String templateCategory) { this.templateCategory = templateCategory; }
}