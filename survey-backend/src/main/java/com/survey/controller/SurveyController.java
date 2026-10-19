package com.survey.controller;

import com.alibaba.excel.EasyExcel;
import com.survey.common.SurveyStatus;
import com.survey.annotation.RequirePermission;
import com.survey.annotation.OperationLog;
import com.survey.common.BizException;
import com.survey.common.ForbiddenException;
import com.survey.common.Permission;
import com.survey.common.PageResult;
import com.survey.dto.SurveyStatisticsDTO;
import com.survey.entity.Survey;
import com.survey.mapper.RoleMapper;
import com.survey.service.AnswerService;
import com.survey.service.SurveyAccessService;
import com.survey.service.SurveyCollaboratorService;
import com.survey.service.SurveyService;
import com.survey.service.TemplateCategoryService;
import com.survey.service.UserService;
import com.survey.service.WalletService;
import com.survey.common.OwnershipGuard;
import com.survey.common.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问卷管理：增删改查、发布、复制、回收站、模板、统计与导出。
 * 权限矩阵：公开 = /share/{code}、/view/{code}；需登录 = /add、/my、/my-statistics、/templates、
 * /from-template/{id}、/recycle/page、/{id}/request-restore；需登录+归属 = /update、/publish/{id}、/copy/{id}、
 * 回收站三个、GET /{id}；管理员 = /list-with-response-count、/page、/export、/set-template/{id}；
 * 管理端人员 = /system-overview（staff 可进，字段按模块权限裁剪）。
 * ★ 归属以登录身份为准：/add 用 X-User-Id 覆盖请求体 userId，/update 沿用库里的 userId 与 shareCode。
 * ★ 权限失败统一抛异常（GlobalExceptionHandler → 400 + 统一 JSON），不要 return null。
 */
@Tag(name = "问卷管理", description = "问卷的增删改查、发布、复制、回收站、统计与导出")
@RestController
@RequestMapping("/api/survey")
public class SurveyController {

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private SurveyCollaboratorService surveyCollaboratorService;

    @Autowired
    private SurveyAccessService surveyAccessService;

    @Autowired
    private UserService userService;

    /** 首次启用高级设置包时扣费（见 update 方法）。 */
    @Autowired
    private WalletService walletService;

    /** 公开结果页要复用统计（只读），直接调 AnswerService */
    @Autowired
    private AnswerService answerService;

    /** 模板分类清单（管理端「模板管理」与「设为模板」共用）。 */
    @Autowired
    private TemplateCategoryService templateCategoryService;

    /** 系统总览按模块权限裁剪字段：只读该用户的权限点，不做授权变更。 */
    @Autowired
    private RoleMapper roleMapper;

    // ==================== 基础 CRUD ====================

    @OperationLog("创建问卷")
    @Operation(summary = "新增问卷")
    @PostMapping("/add")
    public Survey add(@RequestBody Survey survey,
                      @RequestHeader("X-User-Id") Integer userId) {
        if (survey.getStatus() == null) survey.setStatus(SurveyStatus.DRAFT.code());
        if (survey.getViewCount() == null) survey.setViewCount(0);
        // 服务端独占字段（isTemplate/deleted/status/shareCode）归零统一在 createSurvey()，这里不重复。
        // 归属以登录身份为准：X-User-Id 覆盖请求体 userId，否则可伪造到别人名下。
        survey.setUserId(userId);
        return surveyService.createSurvey(survey);
    }

    // 不要再加"不经回收站直接彻底删除"的端点：/permanent 强制要求先移入回收站，加它就是开旁路。
    @OperationLog("更新问卷")
    @Operation(summary = "更新问卷（含标题、描述、时间等）")
    @PutMapping("/update")
    public String update(@RequestBody Survey survey,
                         @RequestHeader("X-User-Id") Integer userId,
                         @RequestHeader("X-User-Role") String role) {
        Survey old = surveyService.getById(survey.getId());
        if (old == null) throw new BizException("问卷不存在");

        // 权限：非管理员且非本人则拒绝；已下架问卷冻结内容（编辑/发布都只能等恢复）。
        surveyAccessService.requirePermission(old, userId, role, Permission.SURVEY_EDIT, "修改");
        surveyAccessService.requireNotSuspended(old, role, "修改");

        // 分享码由服务端独占：一律沿用库里的旧值；只在 null 时才沿用的话，客户端传值会被采纳（抢注/撞唯一索引）。
        // 回收站里的问卷不能编辑 —— 与发布、按 id 读、提交答卷三处口径一致。
        OwnershipGuard.requireNotInRecycleBin(old, "编辑");

        // 高级设置包只在 old→new 首次启用时扣 1 点：管理员不扣，余额不足则整体失败（幂等键 advanced:{id}）。
        boolean advancedNow = hasAdvancedSettings(survey);
        boolean advancedBefore = hasAdvancedSettings(old);
        if (!Role.isStaff(role) && advancedNow && !advancedBefore) {
            walletService.chargeAdvancedSettings(old.getId(), old.getUserId());
        }

        // user_id / status / share_code 不在 update 的 SET 列表里；这是静态 UPDATE，加回它们会把未传字段写成 NULL，
        // 还能靠传 status=1 绕过 /publish，造出"已发布却没有分享码"的问卷。状态变更只走专用方法。
        int result = surveyService.updateSurvey(survey);
        if (result <= 0) throw new BizException("修改失败");
        return "修改成功";
    }

    // ==================== 查询 ====================

    // 不要再加"不分页列出全部问卷"的端点：系统统计走 /list-with-response-count，筛选走 /page。
    /**
     * 问卷列表 + 每份答卷数（管理员）：整页 SQL 恒为 2 条（列表 1 + GROUP BY 1），与问卷数量无关。
     * 口径不含模板，与 getSystemOverview() 的问卷总数一致；模板数量单独由总览的 templates 给出。
     * responseCount 只有本端点赋值。
     */
    @Operation(summary = "问卷列表（含每份答卷数，管理员）")
    @GetMapping("/list-with-response-count")
    @RequirePermission(Permission.SURVEY_MANAGE)
    public List<Map<String, Object>> listWithResponseCount() {
        List<Survey> surveys = surveyService.listNormal();
        // 一次 GROUP BY 拿全部答卷数；没有答卷的问卷不在 Map 里，取值必须 getOrDefault。
        Map<Integer, Integer> counts = surveyService.countResponsesBySurveyIds(surveys);

        List<Map<String, Object>> rows = new ArrayList<>(surveys.size());
        for (Survey s : surveys) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.getId());
            row.put("title", s.getTitle());
            row.put("status", s.getStatus());
            row.put("createdTime", s.getCreatedTime());
            row.put("responseCount", counts.getOrDefault(s.getId(), 0));
            rows.add(row);
        }
        return rows;
    }

    @Operation(summary = "分页查询全部问卷（管理员，支持搜索 + 状态 + 创建时间范围筛选）")
    @GetMapping("/page")
    @RequirePermission(Permission.SURVEY_MANAGE)
    public PageResult<Survey> pageSurveys(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        // 日期只认 yyyy-MM-dd：传空 = 不限，传错 = 400（由 DateRanges 负责），不会变成 500。
        return surveyService.pageAllSurveys(pageNum, pageSize, keyword, status, startDate, endDate);
    }

    /**
     * 导出问卷为 Excel（管理员）：筛选条件与 /page 完全一致，导出的就是屏幕上筛出来的。
     * 口径为全部问卷（不含模板）；上限 10000 份，超出明确拒绝，不静默截断。
     * 答卷数用一次 GROUP BY 补齐，不要在循环里逐份 count。
     */
    @Operation(summary = "导出问卷列表为 Excel（管理员）")
    @GetMapping("/export")
    @RequirePermission(Permission.SURVEY_MANAGE)
    public void exportSurveys(@RequestParam(required = false) String keyword,
                              @RequestParam(required = false) Integer status,
                              @RequestParam(required = false) String startDate,
                              @RequestParam(required = false) String endDate,
                              HttpServletResponse response) throws IOException {
        List<Survey> surveys = surveyService.exportSurveys(keyword, status, startDate, endDate);
        // 一次 GROUP BY 拿全部答卷数（没有答卷的问卷不在 Map 里）。
        Map<Integer, Integer> counts = surveyService.countResponsesBySurveyIds(surveys);

        List<List<String>> head = List.of(
                List.of("ID"), List.of("问卷标题"), List.of("描述"), List.of("状态"),
                List.of("答卷数"), List.of("创建时间"));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<List<String>> rows = new ArrayList<>(surveys.size());
        for (Survey s : surveys) {
            List<String> row = new ArrayList<>(6);
            row.add(s.getId() == null ? "" : String.valueOf(s.getId()));
            row.add(s.getTitle() == null ? "" : s.getTitle());
            row.add(s.getDescription() == null ? "" : s.getDescription());
            row.add(exportSurveyStatus(s.getStatus()));
            row.add(String.valueOf(counts.getOrDefault(s.getId(), 0)));
            row.add(s.getCreatedTime() == null ? "" : s.getCreatedTime().format(fmt));
            rows.add(row);
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        // 文件名两层编码：filename 只能 ASCII，中文名放 filename*（RFC 5987），否则浏览器下载到 %E9... 乱码名。
        String encodedName = URLEncoder.encode("问卷列表", "UTF-8").replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"surveys.xlsx\"; filename*=UTF-8''" + encodedName + ".xlsx");

        EasyExcel.write(response.getOutputStream())
                .head(head)
                .sheet("问卷列表")
                .doWrite(rows);
    }

    /** 导出用状态文案：用 SurveyStatus 判定，"NULL 视为草稿"的口径只在枚举里维护。 */
    private static String exportSurveyStatus(Integer status) {
        if (SurveyStatus.isPublished(status)) return "已发布";
        if (SurveyStatus.isEnded(status)) return "已结束";
        return "草稿";
    }

    @Operation(summary = "查询当前用户的问卷（我的问卷）")
    @GetMapping("/my")
    public List<Survey> mySurveys(@RequestHeader("X-User-Id") Integer userId) {
        List<Survey> mine = surveyService.listByUserId(userId);
        // 让作者看见"哪几份共享出去了"（一次 GROUP BY，不逐条查）
        surveyCollaboratorService.fillCollaboratorCounts(mine);
        return mine;
    }

    /**
     * 已下线端点的单段字面量路径：必须在 /{id} 之前挡掉。
     * 这些 URL 下线后只能匹配 /{id}，而 Spring 在进入方法体前就把路径变量绑定成 Integer，
     * 非数字会先抛 400（"参数 id 格式不正确"），方法里的判断来不及执行 —— 所以 getOne 的 id 用 String 接。
     * 以后删掉单词式端点时，把名字加进这个集合。
     */
    private static final Set<String> REMOVED_LITERAL_PATHS =
            Set.of("list", "statistics", "recycle", "delete");

    @Operation(summary = "根据ID查询问卷")
    @GetMapping("/{id}")
    public Survey getOne(@PathVariable String id,
                         @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                         @RequestHeader(value = "X-User-Role", required = false) String role) {
        // ① 先挡已下线的字面量端点：给如实的 404，而不是"参数 id 格式不正确"
        if (REMOVED_LITERAL_PATHS.contains(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "该端点已下线（GET /api/survey/" + id + " 不再存在），请改用对应的替代端点");
        }
        // ② 再把 id 解析成整数：非数字路径（如 /api/survey/abc）返回 400
        Integer surveyId;
        try {
            surveyId = Integer.valueOf(id);
        } catch (NumberFormatException e) {
            throw new BizException("问卷 ID 必须是数字");
        }

        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在，ID：" + surveyId);
        }
        // 不校验的话，任何登录用户遍历 id 就能读到全站问卷（含别人的草稿）：管理员可读任意，
        // 作者可读自己的，已发布问卷对其他登录用户可读。本端点不在 JwtAuthFilter 白名单，匿名会先被 401。
        surveyAccessService.requireVisible(survey, userId, role, "无权限查看他人未发布的问卷");
        // myPermissions 只用于前端显隐；每个写操作后端仍会再判一次。
        survey.setMyPermissions(surveyAccessService.permissionsFor(survey, userId, role));
        return survey;
    }

    @Operation(summary = "根据分享码查询问卷（匿名访客）")
    @GetMapping("/share/{shareCode}")
    public Survey getByShareCode(@PathVariable String shareCode) {
        Survey survey = surveyService.getByShareCode(shareCode);
        if (survey == null) {
            throw new BizException("问卷不存在或已下线");
        }
        // 答题密码绝不返回；hasPassword 必须在置空之前算好，否则派生值会跟着变 false，前端就不弹密码框。
        survey.setHasPassword(survey.passwordRequired());
        survey.setAccessPassword(null);
        // responsesFull 与 AnswerService.submit 的限收口径一致，避免填写人提交时才知道已满（白填一遍）。
        survey.setResponsesFull(
                survey.getMaxResponses() != null && survey.getMaxResponses() > 0
                        && answerService.countResponses(survey.getId()) >= survey.getMaxResponses());
        return survey;
    }

    /**
     * 公开结果（匿名）：必须开启 public_result 且已发布/已结束，否则 400。
     * 两者分别防"没开启却被猜到"与"草稿题目经结果页泄露"；返回前丢掉填空题原文。
     */
    @Operation(summary = "公开结果（匿名，需问卷开启公开结果）")
    @GetMapping("/result/{shareCode}")
    public SurveyStatisticsDTO publicResult(@PathVariable String shareCode) {
        Survey survey = surveyService.getByShareCode(shareCode);
        if (survey == null) {
            throw new BizException("问卷不存在或已下线");
        }
        if (!SurveyStatus.isPublished(survey.getStatus()) && !SurveyStatus.isEnded(survey.getStatus())) {
            throw new BizException("问卷尚未发布");
        }
        if (survey.getPublicResult() == null || survey.getPublicResult() != 1) {
            throw new BizException("该问卷未开启公开结果");
        }
        return answerService.getSurveyStatistics(survey).withoutTextAnswers();
    }

    @OperationLog("恢复上架")
    @RequirePermission(Permission.REVIEW_HANDLE)
    @Operation(summary = "取消下架（管理员）")
    @PostMapping("/unsuspend/{id}")
    public String unsuspend(@PathVariable Integer id) {
        surveyService.restoreFromSuspend(id);
        return "已恢复上架";
    }

    /**
     * 作者申请恢复上架：只通知管理员，lifecycle 保持 SUSPENDED；/unsuspend 才是管理员直接执行恢复。
     * 合并两者会让作者自助恢复绕过举报审核。归属判断在 Service（只能申请自己的问卷），所以普通用户可调。
     */
    @OperationLog("申请恢复上架")
    @Operation(summary = "作者申请恢复上架（通知全部管理员，需登录）")
    @PostMapping("/{id}/request-restore")
    public String requestRestore(@PathVariable Integer id,
                                 @RequestHeader("X-User-Id") Integer userId) {
        surveyService.requestRestore(id, userId);
        return "已提交申请，管理员会尽快处理";
    }

    // ==================== 发布 / 复制 ====================

    @OperationLog("发布问卷")
    @Operation(summary = "发布问卷（生成分享码）")
    @PostMapping("/publish/{id}")
    public Survey publish(@PathVariable Integer id,
                          @RequestHeader("X-User-Id") Integer userId,
                          @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(id);
        // 权限不足抛异常而不是 return null：null 会变成空响应体，前端分不清"不存在"和"无权限"。
        if (survey == null) {
            throw new BizException("问卷不存在，ID：" + id);
        }
        // 发布是对外可见动作，editor 协作者没有该权限；已下架问卷不能直接重发（否则绕过举报审核）。
        surveyAccessService.requirePermission(survey, userId, role, Permission.SURVEY_PUBLISH, "发布");
        surveyAccessService.requireNotSuspended(survey, role, "发布");
        return surveyService.publish(id);
    }

    @OperationLog("复制问卷")
    @Operation(summary = "复制问卷")
    @PostMapping("/copy/{id}")
    public Survey copy(@PathVariable Integer id,
                       @RequestHeader("X-User-Id") Integer userId,
                       @RequestHeader("X-User-Role") String role) {
        Survey old = surveyService.getById(id);
        // 同上：统一抛异常，前端才能拿到明确的失败原因。
        if (old == null) {
            throw new BizException("问卷不存在，ID：" + id);
        }
        // 复制属于数据外带：editor 协作者有 survey:edit 也不能复制，只给作者/超管/有模块权限的次级管理员。
        surveyAccessService.requireOwnerOrStaff(old, userId, role, "复制");
        return surveyService.copySurvey(id, userId);
    }

    // ==================== 统计 ====================

    // 不要再加"系统统计"独立端点：它是 /system-overview 的真子集（少 views/users），多一个入口多一份要同步的口径。
    /**
     * 系统总览：管理端人员（超管 + 次级管理员）均可访问，字段按模块权限返回。
     * 次级管理员没有任何模块权限时返回空对象，前端显示"联系超管授权"。
     */
    @Operation(summary = "系统总览（管理端人员，按模块权限返回字段）")
    @GetMapping("/system-overview")
    public Map<String, Object> systemOverview(@RequestHeader("X-User-Id") Integer userId,
                                              @RequestHeader("X-User-Role") String role) {
        if (!Role.isStaff(role)) {
            throw new ForbiddenException("无权限");
        }
        Set<String> permissions;
        if (Role.isAdmin(role)) {
            permissions = Permission.ALL;
        } else {
            List<String> codes = roleMapper.findPermissionCodesByUserId(userId);
            permissions = codes == null || codes.isEmpty() ? Set.of() : new HashSet<>(codes);
        }
        Map<String, Object> result = surveyService.getSystemOverview(permissions);
        // 用户数是 user:manage 模块的字段：没有该权限就不返回，不靠前端隐藏
        if (permissions.contains(Permission.USER_MANAGE)) {
            result.put("users", userService.countNormalUsers());
        }
        return result;
    }

    @Operation(summary = "当前用户个人统计")
    @GetMapping("/my-statistics")
    public Map<String, Object> myStatistics(@RequestHeader("X-User-Id") Integer userId) {
        return surveyService.getUserStatistics(userId);
    }


    // ==================== 访问记录 ====================

    @Operation(summary = "记录访问次数（匿名访客打开链接时调用）")
    @PostMapping("/view/{shareCode}")
    public String recordView(@PathVariable String shareCode) {
        surveyService.incrementViewCount(shareCode);
        return "ok";
    }

    // ==================== 回收站 ====================

    @OperationLog("移入回收站")
    @Operation(summary = "移到回收站")
    @PutMapping("/recycle/{id}")
    public String moveToRecycle(@PathVariable Integer id,
                                @RequestHeader("X-User-Id") Integer userId,
                                @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(id);
        if (survey == null) throw new BizException("问卷不存在");
        surveyAccessService.requirePermission(survey, userId, role, Permission.SURVEY_DELETE, "操作");
        int result = surveyService.moveToRecycle(id, userId);
        if (result <= 0) throw new BizException("操作失败");
        return "已移入回收站";
    }

    @OperationLog("恢复问卷")
    @Operation(summary = "从回收站恢复")
    @PutMapping("/restore/{id}")
    public String restore(@PathVariable Integer id,
                          @RequestHeader("X-User-Id") Integer userId,
                          @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(id);
        if (survey == null) throw new BizException("问卷不存在");
        surveyAccessService.requirePermission(survey, userId, role, Permission.SURVEY_DELETE, "操作");
        int result = surveyService.restoreFromRecycle(id);
        if (result <= 0) throw new BizException("操作失败");
        return "已恢复";
    }

    @OperationLog("彻底删除问卷")
    @Operation(summary = "彻底删除问卷")
    @DeleteMapping("/permanent/{id}")
    public String permanentDelete(@PathVariable Integer id,
                                  @RequestHeader("X-User-Id") Integer userId,
                                  @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(id);
        if (survey == null) throw new BizException("问卷不存在");
        surveyAccessService.requirePermission(survey, userId, role, Permission.SURVEY_DELETE, "操作");
        int result = surveyService.permanentDelete(id);
        if (result <= 0) throw new BizException("删除失败");
        return "已彻底删除";
    }

    // 不要再加"不分页查回收站"的端点：/recycle/page 已支持搜索 + 分页，全量返回又慢又撑大响应体。
    @Operation(summary = "分页查询回收站（支持搜索）")
    @GetMapping("/recycle/page")
    public PageResult<Survey> pageRecycle(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestHeader("X-User-Id") Integer userId,
            @RequestHeader("X-User-Role") String role) {
        if (surveyAccessService.hasModulePermission(userId, role, Permission.SURVEY_MANAGE)) {
            return surveyService.pageRecycleAll(pageNum, pageSize, keyword);
        }
        return surveyService.pageRecycleByUser(userId, pageNum, pageSize, keyword);
    }

    // ==================== 模板 ====================

    @Operation(summary = "查询模板广场列表")
    @GetMapping("/templates")
    public List<Survey> templates(@RequestParam(required = false) String keyword) {
        return surveyService.listTemplates(keyword);
    }

    @OperationLog("从模板创建问卷")
    @Operation(summary = "从模板创建问卷")
    @PostMapping("/from-template/{templateId}")
    public Survey createFromTemplate(@PathVariable Integer templateId,
                                     @RequestHeader("X-User-Id") Integer userId) {
        return surveyService.createFromTemplate(templateId, userId);
    }

    @OperationLog("设为模板")
    @Operation(summary = "设为模板 / 取消模板（管理员）")
    @PutMapping("/set-template/{id}")
    @RequirePermission(Permission.TEMPLATE_MANAGE)
    public String setTemplate(@PathVariable Integer id,
                              @RequestParam Integer isTemplate,
                              @RequestParam(required = false) String category) {
        // is_template 只允许 0/1：接受任意整数会造出既不是模板也不是普通问卷的记录，两个列表都查不到。
        // set-template 只改模板标记与分类，不动其它字段。
        if (isTemplate == null || (isTemplate != 0 && isTemplate != 1)) {
            throw new BizException("isTemplate 只能是 0（取消模板）或 1（设为模板）");
        }
        int r = surveyService.setTemplate(id, isTemplate, category);
        if (r <= 0) throw new BizException("操作失败");
        return "操作成功";
    }

    /**
     * 模板分类清单（管理员）：分类可独立于模板存在（模板管理页可先建空分类），所以不能从现有模板反推。
     * 清单本身不敏感，但只有管理端需要，故走管理档。
     */
    @Operation(summary = "模板分类清单（管理员）")
    @GetMapping("/template-categories")
    @RequirePermission(Permission.TEMPLATE_MANAGE)
    public List<String> templateCategories() {
        return templateCategoryService.listNames();
    }

    /**
     * 新增模板分类（管理员）：name 声明为非必填、在 Service 判空 —— 必填 @RequestParam 会在权限切面之前抛 400，
     * 普通用户拿到的就不是 403。返回新增后的完整清单，前端可直接刷新下拉。
     */
    @OperationLog("新增模板分类")
    @Operation(summary = "新增模板分类（管理员）")
    @PostMapping("/template-categories")
    @RequirePermission(Permission.TEMPLATE_MANAGE)
    public List<String> addTemplateCategory(@RequestParam(required = false) String name) {
        return templateCategoryService.add(name);
    }

    /**
     * 是否启用任一「高级设置包」项：答题密码 / 限收份数 / 公开结果 / IP 限制。
     * 口径必须与 WalletFeature.ADVANCED_SETTINGS 一致；新增设置项时两处一起改（这里是唯一判定处）。
     */
    private static boolean hasAdvancedSettings(Survey survey) {
        if (survey == null) {
            return false;
        }
        boolean hasPassword = survey.getAccessPassword() != null
                && !survey.getAccessPassword().isBlank();
        boolean hasLimit = survey.getMaxResponses() != null && survey.getMaxResponses() > 0;
        boolean publicResult = survey.getPublicResult() != null && survey.getPublicResult() == 1;
        boolean ipLimit = survey.getIpLimit() != null && survey.getIpLimit() == 1;
        return hasPassword || hasLimit || publicResult || ipLimit;
    }
}
