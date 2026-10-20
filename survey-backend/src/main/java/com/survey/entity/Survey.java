package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 问卷实体 —— 表 t_survey，全项目核心业务对象。
 * 三态字段勿混：status 业务状态（0 草稿 / 1 已发布 / 2 已结束）；deleted 回收站（0 正常 / 1 在回收站）；
 * isTemplate 是否模板（0 普通问卷 / 1 模板）。
 * ★ deletedByName 不是数据库列：仅回收站查询 JOIN t_user 取昵称回填，普通查询为 null，只读展示。
 */
public class Survey {
    private Integer id;
    private Integer userId;          // 创建者 ID；归属校验就是拿它和登录用户比
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
    private String deletedByName;    // 删除人昵称，仅展示（JOIN 回填，非数据库列）

    /** 移入回收站时间：NULL = 未删除。回收站列表按它展示删除时间（created_time 是创建时间）。
     *  由 SurveyMapper.moveToRecycle 写 NOW()、restoreFromRecycle 置回 NULL。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deletedAt;

    private Integer viewCount;       // 访问次数（访客打开分享链接时 +1）

    // ===== 问卷设置（编辑页「设置」面板）=====

    /** 答题密码：NULL = 不需要密码。
     * 非空时，加载题目与提交答卷两个入口都校验请求头 X-Survey-Password（前端弹框只是体验层）。
     * ★ 对外接口 /share/{code} 返回前置空，只暴露 hasPassword。 */
    private String accessPassword;

    /** 最多收集多少份答卷：NULL = 不限；达到后 submit 直接 400。 */
    private Integer maxResponses;

    /** 提交后的感谢语：NULL = 用系统默认文案。 */
    private String thankText;

    /** 提交后跳转的链接：NULL = 不跳转；只允许 http/https（防 javascript: 伪协议）。 */
    private String redirectUrl;

    /** 是否公开结果页：0 = 不公开，1 = 开启（匿名可访问 /api/survey/result/{shareCode}）。 */
    private Integer publicResult;

    /** 考试模式：0 = 普通问卷（不判分），1 = 判分。
     * 只有 1 时 {@link com.survey.common.Grader} 才在提交时算分；开启时必须至少一道题 score &gt; 0。 */
    private Integer gradeEnabled;

    /** 及格分：NULL = 不判及格（只出分）；取值须在 0 ~ 总分（所有 score&gt;0 之和）之间。 */
    private Integer passScore;

    /** 答题限时（秒）：NULL/0 = 不限时；前端只负责倒计时。
     * 服务端按上报的 duration_ms 校验（超过限时 + 10 秒宽限就拒收）；该值可伪造，只挡正常超时，不是防作弊。 */
    private Integer timeLimitSeconds;

    /** 提交后是否展示正确答案：0 = 否（默认），1 = 是；仅 gradeEnabled=1 有意义，关闭考试模式时归一成 0。 */
    private Integer showAnswerAfterSubmit;

    /** 是否仅限登录用户填写：0 = 匿名可填（默认），1 = 必须先登录。
     * 服务端只接受 POST /answer/submit/{surveyId}（带 token）；submit-share 的 userId 恒为 null，一律拒收。
     * 实名答卷受 uk_survey_user（一人一份）约束，统计里能看到答题人 id。 */
    private Integer requireLogin;

    /** 是否启用签到核销：0=否（默认），1=是。作者在答卷列表里打勾/取消。 */
    private Integer checkinEnabled;

    /** 是否公开报名名单：0=否（默认），1=是。开启后填写页显示前 50 位报名者。 */
    private Integer showParticipants;

    /** 填写页题目乱序：0=按 sort_order（默认），1=随机。只影响填写页；编辑器/统计/回看仍按原顺序。 */
    private Integer shuffleQuestions;

    /** 填写页选项乱序：0=按 sort_order（默认），1=随机。判分按选项原文，不受顺序影响。 */
    private Integer shuffleOptions;

    /** 每份答卷抽题数：0=不抽（默认），N=从全部题目随机抽 N 道；判分总分按抽到的题算。 */
    private Integer questionsPerAttempt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedTime;

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

    /** 对外返回：前端据此决定是否弹密码框。
     * 必须是独立字段，不能由 accessPassword 现算：/share/{code} 会先置空密码，派生 getter 会跟着变 false。 */
    private Boolean hasPassword;

    public Boolean getHasPassword() { return hasPassword; }
    public void setHasPassword(Boolean hasPassword) { this.hasPassword = hasPassword; }

    /** 内部判定是否设了答题密码。刻意不用 getter 命名，避免与 hasPassword 字段撞名被序列化。 */
    public boolean passwordRequired() {
        return accessPassword != null && !accessPassword.isBlank();
    }

    /** 是否已收满（答卷数 ≥ maxResponses），只有 /share/{code} 会算；NULL/≤0 视为不限量，与提交守卫同口径。
     * 提前暴露给填写页，避免填完点提交才看到「已收满」。 */
    private Boolean responsesFull;

    public Boolean getResponsesFull() { return responsesFull; }
    public void setResponsesFull(Boolean responsesFull) { this.responsesFull = responsesFull; }

    /** 当前用户对这份问卷的权限点，只由 GET /api/survey/{id} 填充，其它接口为 null。
     * 由后端算（是否协作者只有后端知道）；前端仅做显隐，真正拦截仍在后端。 */
    private java.util.List<String> myPermissions;

    public java.util.List<String> getMyPermissions() { return myPermissions; }
    public void setMyPermissions(java.util.List<String> myPermissions) { this.myPermissions = myPermissions; }

    /** 共享给几个协作者，只在 GET /api/survey/my 填充。作者需要知道哪几份共享出去了。 */
    private Integer collaboratorCount;

    public Integer getCollaboratorCount() { return collaboratorCount; }
    public void setCollaboratorCount(Integer collaboratorCount) { this.collaboratorCount = collaboratorCount; }

    /** 共享列表里的作者信息（JOIN 别名，非 t_survey 列），只在 GET /api/collaborator/shared 填充；前端按作者分组。 */
    private String ownerUsername;
    private String ownerNickname;

    /** 我在这份问卷上的协作者角色：viewer / editor / analyst（只在共享列表填充）。 */
    private String myCollaboratorRole;

    /** 这份问卷是什么时候共享给我的（t_survey_collaborator.created_time，只在共享列表填充）。 */
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private java.time.LocalDateTime sharedTime;

    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }

    public String getOwnerNickname() { return ownerNickname; }
    public void setOwnerNickname(String ownerNickname) { this.ownerNickname = ownerNickname; }

    public String getMyCollaboratorRole() { return myCollaboratorRole; }
    public void setMyCollaboratorRole(String myCollaboratorRole) { this.myCollaboratorRole = myCollaboratorRole; }

    public java.time.LocalDateTime getSharedTime() { return sharedTime; }
    public void setSharedTime(java.time.LocalDateTime sharedTime) { this.sharedTime = sharedTime; }

    /** 业务生命周期（DRAFT/PUBLISHED/SUSPENDED/ENDED/ARCHIVED），与 status 并存。
     * 读取方不要自己拼，用 common/SurveyLifecycle。 */
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

    public Integer getCheckinEnabled() { return checkinEnabled; }
    public void setCheckinEnabled(Integer checkinEnabled) { this.checkinEnabled = checkinEnabled; }

    public Integer getShowParticipants() { return showParticipants; }
    public void setShowParticipants(Integer showParticipants) { this.showParticipants = showParticipants; }

    public Integer getShuffleQuestions() { return shuffleQuestions; }
    public void setShuffleQuestions(Integer shuffleQuestions) { this.shuffleQuestions = shuffleQuestions; }

    public Integer getShuffleOptions() { return shuffleOptions; }
    public void setShuffleOptions(Integer shuffleOptions) { this.shuffleOptions = shuffleOptions; }

    public Integer getQuestionsPerAttempt() { return questionsPerAttempt; }
    public void setQuestionsPerAttempt(Integer questionsPerAttempt) { this.questionsPerAttempt = questionsPerAttempt; }

    /** IP 限制：0=关闭，1=同一 IP 只能提交一次；NULL 按 0 处理（不意外开启限制）。 */
    private Integer ipLimit;

    public Integer getIpLimit() { return ipLimit; }
    public void setIpLimit(Integer ipLimit) { this.ipLimit = ipLimit; }

    private Integer isTemplate;       // 0=普通问卷，1=模板
    private String templateCategory;  // 模板分类

    /** 模板被「从模板创建」使用的次数：仅 createFromTemplate 自增，模板广场按它降序；普通问卷恒为 0。 */
    private Integer templateUseCount;

    /** 答卷数，非数据库列，只在 /api/survey/my 与 /my-statistics 回填（批量 count，避免 N+1）。 */
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