package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

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

    /**
     * 考试模式：0 = 普通问卷（不判分），1 = 判分。
     * ★ 只有 1 时 {@link com.survey.common.Grader} 才会在提交时算分并把结果写进 t_answer。
     * ★ 开启时必须至少有一道题 {@code score > 0}（校验在 SurveyService.normalizeSurveySettings）。
     */
    private Integer gradeEnabled;

    /**
     * 及格分：NULL = 不判及格（只出分）。
     * ★ 取值必须在 0 ~ 该问卷总分之间（总分 = 所有 score&gt;0 的题目分值之和）。
     */
    private Integer passScore;

    /**
     * 答题限时（秒）：NULL/0 = 不限时。
     * ★ 前端按它做倒计时，服务端在提交时按**上报的 duration_ms** 校验（超过限时 + 10 秒宽限就拒收）。
     * ★ 注意这不是防作弊：duration_ms 来自前端、可伪造（见《待解决问题》C8），
     *   它挡的是"页面倒计时结束还继续答题"的正常超时，不是恶意绕过。
     */
    private Integer timeLimitSeconds;

    /**
     * 提交后是否展示正确答案：0 = 否（默认，只出分），1 = 是。
     * ★ 只在 gradeEnabled=1 时有意义；关闭考试模式时会被归一成 0。
     */
    private Integer showAnswerAfterSubmit;

    /**
     * 是否仅限登录用户填写：0 = 匿名可填（默认，与改造前完全一致），1 = 必须先登录。
     * ★ 开启后：填写页未登录时只显示登录引导、不加载题目；
     *   服务端只接受 {@code POST /answer/submit/{surveyId}}（带 token），
     *   {@code submit-share} 会被拒收（userId 恒为 null）。
     * ★ 实名答卷因此受 {@code uk_survey_user}（一人一份）约束，且统计里能看到答题人 id。
     */
    private Integer requireLogin;

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

    /**
     * 是否**已经收满**（答卷数 ≥ maxResponses）。只有 /share/{code} 会算它，其它接口为 null。
     *
     * ★ 为什么要暴露给填写页：限量问卷以前只有"提交时"才判满 ——
     *   填写人把整份问卷填完、点提交，才看到「已收满」，白填一遍。
     *   现在进页面就能看到"已收满，别填了"，并直接给结果页入口。
     * ★ 与提交时那道守卫（AnswerService.submit 第六道）**同一口径**：
     *   maxResponses 为 null 或 ≤ 0 视为不限量。
     */
    private Boolean responsesFull;

    public Boolean getResponsesFull() { return responsesFull; }
    public void setResponsesFull(Boolean responsesFull) { this.responsesFull = responsesFull; }

    /**
     * 当前登录用户对**这一份**问卷的有效权限点（survey:edit / survey:publish / survey:data / survey:delete）。
     *
     * ★ 只由 GET /api/survey/{id} 填充（作者接口），其它接口为 null。
     * ★ 为什么由后端算、而不是前端拼：权限取决于"你是谁 × 这份问卷是谁的 × 你是不是协作者"，
     *   其中"是不是协作者"只有后端知道。前端拿这个列表**只做显隐**，
     *   真正的拦截仍在后端 —— localStorage 可改，前端判断永远只是体验层。
     */
    private java.util.List<String> myPermissions;

    public java.util.List<String> getMyPermissions() { return myPermissions; }
    public void setMyPermissions(java.util.List<String> myPermissions) { this.myPermissions = myPermissions; }

    /**
     * 这份问卷共享给了几个协作者（只在 GET /api/survey/my 里填充）。
     * ★ 作者需要知道"哪几份是共享出去的" —— 否则只有被邀请的人看得见，
     *   作者本人反而不知道自己共享过什么。
     */
    private Integer collaboratorCount;

    public Integer getCollaboratorCount() { return collaboratorCount; }
    public void setCollaboratorCount(Integer collaboratorCount) { this.collaboratorCount = collaboratorCount; }

    /**
     * 业务生命周期（DRAFT/PUBLISHED/SUSPENDED/ENDED/ARCHIVED），与 status 并存。
     * ★ 由三条状态 SQL 与举报下架共同维护；读取方**不要**自己拼，用 common/SurveyLifecycle。
     */
    private String lifecycle;

    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String lifecycle) { this.lifecycle = lifecycle; }

    public Integer getMaxResponses() { return maxResponses; }
    public void setMaxResponses(Integer maxResponses) { this.maxResponses = maxResponses; }

    public String getThankText() { return thankText; }
    public void setThankText(String thankText) { this.thankText = thankText; }

    public String getRedirectUrl() { return redirectUrl; }
    public void setRedirectUrl(String redirectUrl) { this.redirectUrl = redirectUrl; }

    public Integer getPublicResult() { return publicResult; }
    public void setPublicResult(Integer publicResult) { this.publicResult = publicResult; }

    public Integer getGradeEnabled() { return gradeEnabled; }
    public void setGradeEnabled(Integer gradeEnabled) { this.gradeEnabled = gradeEnabled; }

    public Integer getPassScore() { return passScore; }
    public void setPassScore(Integer passScore) { this.passScore = passScore; }

    public Integer getTimeLimitSeconds() { return timeLimitSeconds; }
    public void setTimeLimitSeconds(Integer timeLimitSeconds) { this.timeLimitSeconds = timeLimitSeconds; }

    public Integer getShowAnswerAfterSubmit() { return showAnswerAfterSubmit; }
    public void setShowAnswerAfterSubmit(Integer showAnswerAfterSubmit) { this.showAnswerAfterSubmit = showAnswerAfterSubmit; }

    public Integer getRequireLogin() { return requireLogin; }
    public void setRequireLogin(Integer requireLogin) { this.requireLogin = requireLogin; }

    /**
     * IP 限制：0=关闭，1=同一 IP 只能提交一次（属于「高级设置包」）。
     * ★ 默认 null 按 0 处理（老数据/老客户端不传时不会意外开启限制）。
     */
    private Integer ipLimit;

    public Integer getIpLimit() { return ipLimit; }
    public void setIpLimit(Integer ipLimit) { this.ipLimit = ipLimit; }

    private Integer isTemplate;       // 0=普通问卷，1=模板
    private String templateCategory;  // 模板分类

    /**
     * 该模板被「从模板创建」使用的次数（热度）。
     * ★ 只在 SurveyService.createFromTemplate 里用数据库自增累加；
     *   普通问卷恒为 0，模板广场/模板管理按它降序展示"热门"。
     */
    private Integer templateUseCount;

    /**
     * 答卷数 —— **不是数据库列**，只在 `/api/survey/my` 与 `/my-statistics` 的 recent 里回填。
     * ★ 由 SurveyService 用一次 `countBySurveyIds` 批量填（避免 N+1）；
     *   NON_NULL 保证其它接口（详情/管理列表）不会多出 `"responseCount": null`。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer responseCount;

    public Integer getIsTemplate() { return isTemplate; }
    public void setIsTemplate(Integer isTemplate) { this.isTemplate = isTemplate; }

    public String getTemplateCategory() { return templateCategory; }
    public void setTemplateCategory(String templateCategory) { this.templateCategory = templateCategory; }

    public Integer getTemplateUseCount() { return templateUseCount; }
    public void setTemplateUseCount(Integer templateUseCount) { this.templateUseCount = templateUseCount; }

    public Integer getResponseCount() { return responseCount; }
    public void setResponseCount(Integer responseCount) { this.responseCount = responseCount; }
}