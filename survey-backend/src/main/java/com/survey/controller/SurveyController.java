package com.survey.controller;

import com.alibaba.excel.EasyExcel;
import com.survey.common.SurveyStatus;
import com.survey.annotation.RequireAdmin;
import com.survey.annotation.OperationLog;
import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.common.PageResult;
import com.survey.dto.SurveyStatisticsDTO;
import com.survey.entity.Survey;
import com.survey.service.AnswerService;
import com.survey.service.SurveyAccessService;
import com.survey.service.SurveyCollaboratorService;
import com.survey.service.SurveyService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问卷管理：问卷的增删改查、发布、复制、回收站、模板、统计。共 **20** 个端点。
 * （★ 另有 4 个被其它端点完全覆盖的单词式端点（GET /list、GET /statistics、GET /recycle、
 *   DELETE /delete/{id}）已下线，它们的 URL 仍由 REMOVED_LITERAL_PATHS 显式挡着 ——
 *   不挡就会被 /{id} 吞掉，见 getOne() 上方注释。）
 *
 * ── 谁在调我（前端页面）────────────────────────────────
 *   MySurveys.vue          → 我的问卷、新建、AI生成、模板广场、复制、发布、二维码
 *   SurveyEdit.vue         → 编辑问卷（改标题/描述/时间）
 *   SurveyStatistics.vue   → 单份问卷的答题统计
 *   MyDashboard.vue        → 个人统计 /my-statistics
 *   Dashboard.vue / SystemStatistics.vue / AdminProfile.vue → 系统统计与总览
 *   AdminSurveyList.vue    → 管理员分页查全部问卷
 *   Recycle.vue            → 回收站
 *   SurveyFillByCode.vue   → 匿名访客凭分享码打开问卷、记访问量
 *
 * ── 权限矩阵（详见 docs/问卷系统-端点权限审计表.md，随仓库入库）──
 *   公开(白名单) : GET /share/{code}、POST /view/{code}
 *   需登录       : /add、/my、/my-statistics、/templates、/from-template/{id}、/recycle/page
 *   需登录+归属  : /update、/publish/{id}、/copy/{id}、
 *                  /recycle/{id}、/restore/{id}、/permanent/{id}、GET /{id}
 *   需管理员     : /list-with-response-count、/page、/export、/system-overview、/set-template/{id}
 *
 * ── 三条贯穿全类的约定（改代码时请遵守）──────────────────
 *   1. 归属以登录身份为准：/add 会用 X-User-Id 覆盖请求体里的 userId，/update 会强制
 *      沿用原 userId —— 客户端无法把问卷挂到别人名下；
 *   2. 分享码由服务端独占管理：/update 无条件沿用数据库旧值，客户端传了也会被忽略；
 *   3. 权限失败统一抛异常（走 GlobalExceptionHandler → 400 + 统一 JSON），不要 return null。
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

    /** H5：首次启用高级设置包时扣费（见 update 方法）。 */
    @Autowired
    private WalletService walletService;

    /** 公开结果页要复用统计（只读），直接调 AnswerService */
    @Autowired
    private AnswerService answerService;

    // ==================== 基础 CRUD ====================

    @OperationLog("创建问卷")
    @Operation(summary = "新增问卷")
    @PostMapping("/add")
    public Survey add(@RequestBody Survey survey,
                      @RequestHeader("X-User-Id") Integer userId) {
        if (survey.getStatus() == null) survey.setStatus(SurveyStatus.DRAFT.code());
        if (survey.getViewCount() == null) survey.setViewCount(0);
        // ★ isTemplate / deleted 必须落地成 0，而不是 NULL —— 前端新建问卷的请求体里
        //   没有这两个字段，MyBatis 会把 null 原样写进列；而库里确实存在
        //   `is_template IS NULL` 的问卷（统计侧 Java 判断 `isTemplate == null || == 0`
        //   所以还能算对，但任何写死 `is_template = 0` 的 SQL 都会漏掉它们，是埋着的坑）。
        // ★ 归属以登录身份为准，不信任请求体里的 userId（否则可以伪造到别人名下）。
        //   isTemplate / deleted / status / shareCode 的"服务端独占"归零统一下沉在
        //   SurveyService.createSurvey()（唯一的 INSERT 入口），这里不重复写 ——
        //   免得以后新增创建途径时只改一处、留下漏网的字段。
        survey.setUserId(userId);
        return surveyService.createSurvey(survey);
    }

    // ★ 这里**不要**再加"不经回收站直接彻底删除"的端点：
    //   它与 DELETE /permanent/{id} **语义重叠但约束更松** —— permanent 强制要求
    //   "问卷必须已在回收站里"（防止手滑一个请求把正常问卷彻底删掉），而那样一条**不要求**，
    //   直接绕过回收站。前端的删除流程统一是"移入回收站 → 彻底删除"两步，
    //   留着它等于留了一条绕过保护的旁路（对应的 SurveyService.deleteSurvey() 同样不保留）。
    @OperationLog("更新问卷")
    @Operation(summary = "更新问卷（含标题、描述、时间等）")
    @PutMapping("/update")
    public String update(@RequestBody Survey survey,
                         @RequestHeader("X-User-Id") Integer userId,
                         @RequestHeader("X-User-Role") String role) {
        Survey old = surveyService.getById(survey.getId());
        if (old == null) throw new BizException("问卷不存在");

        // ★ 硬校验：只要不是管理员，且不是自己的问卷，就拒绝
        surveyAccessService.requirePermission(old, userId, role, Permission.SURVEY_EDIT, "修改");
        // ★ 已被举报下架的问卷，非管理员不能再改内容（编辑/发布都冻结，只能申请恢复）
        surveyAccessService.requireNotSuspended(old, role, "修改");

        // ★ 分享码由服务端独占管理：一律沿用数据库里的旧值，不接受客户端传来的 shareCode。
        //   若只在 shareCode == null 时才沿用旧值，客户端传了就会被采纳 ——
        //   可以把分享码改成别人已有的值（撞唯一索引报错）或抢注一个自己想要的码。
        // ★ **回收站里的问卷不能被编辑** —— 只校验归属、不看 deleted 的话，
        //   移入回收站的问卷照样能改标题/时间，与发布、按 id 读、提交答卷那三处的口径不一致
        //   （那三处都挡了），所以这里必须过 requireNotInRecycleBin。
        OwnershipGuard.requireNotInRecycleBin(old, "编辑");

        // ★ H5：**首次**启用「高级设置包」（答题密码 / 限收份数 / 公开结果）扣 1 点。
        //   · 只判断 old→new 的"从无到有"，之后改来改去不再扣（幂等键 advanced:{id} 兜底）；
        //   · 管理员不扣（管理动作不该消耗用户/自己的额度）；
        //   · 余额不足时这次保存整体失败 —— 不会出现"设置开了、钱没扣"。
        boolean advancedNow = hasAdvancedSettings(survey);
        boolean advancedBefore = hasAdvancedSettings(old);
        if (!Role.isAdmin(role) && advancedNow && !advancedBefore) {
            walletService.chargeAdvancedSettings(old.getId(), old.getUserId());
        }

        // ★ 关键不变量由 SQL 承担，不靠这里的回填：SurveyMapper.update 的 SET 列表里
        //   **根本没有** user_id / status / share_code —— 即使忘了回填，这次更新也不可能碰到它们。
        //   ★ 若有人把这三列加回 SET 列表：它是静态字段 UPDATE，客户端没传的字段会被写成 NULL，
        //   "只改标题"的局部提交就会把 status 写成 NULL（那份问卷从此谁也答不了），
        //   而且能靠传 status=1 绕过 /publish 假装已发布、却没有分享码。
        //   状态变更走专用方法（publish / updateStatusAndShareCode / 回收站与定时任务），
        //   归属与分享码在各自的入口处理。
        //   ★ 前端零影响：SurveyEdit.vue 保存时本来就回带 status，发布走独立的 api.survey.publish()。
        int result = surveyService.updateSurvey(survey);
        if (result <= 0) throw new BizException("修改失败");
        return "修改成功";
    }

    // ==================== 查询 ====================

    // ★ 这里**不要**再加"不分页地列出全部问卷"的端点：
    //   它的两个用途都已有更合适的端点 —— 系统统计页要"问卷 + 每份答卷数"，已由下面的
    //   /list-with-response-count 一次 GROUP BY 提供；管理端要按题/描述/状态筛选，走 /page。
    //   ★ 否则就是"清单分叉"：同一份数据三个端点，字段口径各写一遍，改一处就有一处对不上。
    /**
     * 问卷列表 + 每份的答卷数（管理员，一次请求）。
     *
     * ★ 存在的理由：系统统计页要"每份问卷的答卷数"，若前端 for 循环逐个
     *   await api.answer.count(id)，问卷一多就是几百次**串行**往返。
     *   后端早就有批量统计 countResponsesBySurveyIds()（一次 GROUP BY），
     *   缺的只是"把问卷与答卷数一起吐出来"的端点，前端只能自己循环。
     *   现在整页 SQL 条数恒为 2（列表 1 条 + 分组统计 1 条），与问卷数量无关。
     *
     * 返回结构：id/title/status/createdTime（原样带出）+ 本端点独有的 responseCount ——
     *   所以前端把循环换成一次调用即可，其它消费方不受影响。
     * 列表含模板（数据源 SurveyService.listAll() → mapper.findAll()，与 getSystemOverview() 同源）。
     */
    @Operation(summary = "问卷列表（含每份答卷数，管理员）")
    @GetMapping("/list-with-response-count")
    @RequireAdmin
    public List<Map<String, Object>> listWithResponseCount() {
        List<Survey> surveys = surveyService.listAll();
        // 一次 GROUP BY 拿到全部答卷数（key = surveyId；没有答卷的问卷不会出现在 Map 里）
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
    @RequireAdmin
    public PageResult<Survey> pageSurveys(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        // ★ 日期格式只有一种：yyyy-MM-dd（见 common/DateRanges）。
        //   传空 = 不限制；传错 = 400 + "应为 yyyy-MM-dd"，不会变成 500。
        return surveyService.pageAllSurveys(pageNum, pageSize, keyword, status, startDate, endDate);
    }

    /**
     * 导出问卷列表为 Excel（管理员）。
     *
     * ★ 筛选条件（keyword / status / startDate / endDate）与 {@code /page} **完全一致**，
     *   前端把当前筛选原样带过来，"导出的就是屏幕上筛出来的"。
     * ★ 口径是「全部问卷」：**不含模板**（与 /page 的 SQL 一致，模板有自己的管理页）。
     * ★ 上限 10000 份（SurveyService.SURVEY_EXPORT_MAX_ROWS），超出明确拒绝，不静默截断。
     * ★ 答卷数用一次 GROUP BY 补齐（countResponsesBySurveyIds），**不要**在循环里逐份 count。
     */
    @Operation(summary = "导出问卷列表为 Excel（管理员）")
    @GetMapping("/export")
    @RequireAdmin
    public void exportSurveys(@RequestParam(required = false) String keyword,
                              @RequestParam(required = false) Integer status,
                              @RequestParam(required = false) String startDate,
                              @RequestParam(required = false) String endDate,
                              HttpServletResponse response) throws IOException {
        List<Survey> surveys = surveyService.exportSurveys(keyword, status, startDate, endDate);
        // 一次 GROUP BY 拿全部答卷数（没有答卷的问卷不会出现在 Map 里，取值必须 getOrDefault）
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
        // ★ 文件名两层编码（同 AnswerController.export）：filename 只能是 ASCII，
        //   中文名放 filename*（RFC 5987），否则浏览器下载到的是一串 %E9%97%AE... 乱码名。
        String encodedName = URLEncoder.encode("问卷列表", "UTF-8").replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"surveys.xlsx\"; filename*=UTF-8''" + encodedName + ".xlsx");

        EasyExcel.write(response.getOutputStream())
                .head(head)
                .sheet("问卷列表")
                .doWrite(rows);
    }

    /**
     * 导出里的状态文案 —— 与前端列表的三种标签一致（草稿 / 已发布 / 已结束）。
     * ★ 用 {@link SurveyStatus} 判定而不是 `status == 1`：NULL 视为草稿的口径收在那个枚举里。
     */
    private static String exportSurveyStatus(Integer status) {
        if (SurveyStatus.isPublished(status)) return "已发布";
        if (SurveyStatus.isEnded(status)) return "已结束";
        return "草稿";
    }

    @Operation(summary = "查询当前用户的问卷（我的问卷）")
    @GetMapping("/my")
    public List<Survey> mySurveys(@RequestHeader("X-User-Id") Integer userId) {
        List<Survey> mine = surveyService.listByUserId(userId);
        // ★ 让作者看见"哪几份共享出去了"（只多一次 GROUP BY，不逐条查）
        surveyCollaboratorService.fillCollaboratorCounts(mine);
        return mine;
    }

    /**
     * 已下线端点的「单段字面量」路径 —— 必须在这里显式挡掉，否则会被下面的 {@code /{id}} 吞掉。
     *
     * 集合里现有 **4** 个元素：{@code list}、{@code statistics}、{@code recycle}、{@code delete}。
     *   这些端点在 Spring 的路由里原本是"字面量路径"，优先级高于 {@code /{id}}；下线之后，
     *   同一个 URL 只能匹配到 {@code /{id}}，于是老的调用方/书签/文档里点进来
     *   会先被判"参数「id」格式不正确"（400）—— 文案指向一个根本不存在的"id 参数"，
     *   而不是如实说"这个端点已经没有了"。
     *   ★ 为什么要在这里挡而不是靠 404：Spring 的路径变量绑定发生在进入方法体之前，
     *     {@code @PathVariable Integer} 遇到非数字直接抛 MethodArgumentTypeMismatchException，
     *     方法体里的任何判断都来不及执行（那 4 个 400 就是这么来的）。
     *   ★ 若以后又删掉某个单词式端点，把它的名字加进这个集合即可。
     */
    private static final Set<String> REMOVED_LITERAL_PATHS =
            Set.of("list", "statistics", "recycle", "delete");

    @Operation(summary = "根据ID查询问卷")
    @GetMapping("/{id}")
    public Survey getOne(@PathVariable String id,
                         @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                         @RequestHeader(value = "X-User-Role", required = false) String role) {
        // ① 先挡「已下线的字面量端点」：给一句如实的 404 文案，而不是"参数 id 格式不正确"
        if (REMOVED_LITERAL_PATHS.contains(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "该端点已下线（GET /api/survey/" + id + " 不再存在），请改用对应的替代端点");
        }
        // ② 再把 id 解析成整数 —— 非数字路径（如探测脚本乱试的 /api/survey/abc）给 400
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
        // ★ 这里不做校验的话，任何登录用户遍历 id 就能读到全站问卷（含别人的草稿）。
        //   现在：管理员可读任意；本人可读自己的；已发布（status=1）的问卷对**其它登录用户**也可读
        //   （与分享链接的口径一致）。★ 但本端点**不在 JwtAuthFilter 白名单**里，匿名请求会先被 401
        //   挡住 —— 它是"需登录"的端点，别按公开接口的假设去改它。
        surveyAccessService.requireVisible(survey, userId, role, "无权限查看他人未发布的问卷");
        // ★ 顺带告诉前端"你对这份问卷能做什么"：编辑器据此隐藏保存/发布按钮。
        //   只是显隐提示 —— 每个写操作仍会在后端再判一次。
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
        // ★ 答题密码**绝不**返回给前端：只暴露 hasPassword。
        //   ★ hasPassword 必须在置空**之前**算好并存进独立字段 —— 它是派生值的话，
        //     置空之后会跟着变成 false，前端就不弹密码框了（已实测踩过）。
        //   校验密码走 X-Survey-Password 请求头，在「加载题目」「提交答卷」两个入口各判一次。
        survey.setHasPassword(survey.passwordRequired());
        survey.setAccessPassword(null);
        // ★ 告诉填写页"是不是已经收满了"：口径与 AnswerService.submit 的第六道守卫完全一致
        //   （maxResponses 为 null 或 ≤ 0 = 不限量）。
        //   不这么做的话，填写人只有点提交那一刻才知道已经满了（白填一遍）。
        survey.setResponsesFull(
                survey.getMaxResponses() != null && survey.getMaxResponses() > 0
                        && answerService.countResponses(survey.getId()) >= survey.getMaxResponses());
        return survey;
    }

    /**
     * 公开结果页（匿名）：问卷作者在设置里开启「公开结果」后，任何人都能看汇总统计。
     *
     * ★ 三条安全口径：
     *   1. 必须 public_result = 1，否则 400 —— 不能让"没开启"变成"藏起来但能猜到"；
     *   2. 必须是已发布或已结束 —— 草稿的题目不该通过结果页泄露；
     *   3. 返回前丢掉所有**填空题原文**（可能含个人信息），只留选项分布/评分分布，
     *      脱敏逻辑在 DTO.withoutTextAnswers()。
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
    @RequireAdmin
    @Operation(summary = "取消下架（管理员）")
    @PostMapping("/unsuspend/{id}")
    public String unsuspend(@PathVariable Integer id) {
        surveyService.restoreFromSuspend(id);
        return "已恢复上架";
    }

    /**
     * 作者申请恢复上架（H1 闭环）。
     *
     * ★ 与上面的 /unsuspend/{id} 是**两个不同的动作**，别再合并：
     *   /unsuspend 是管理员**直接执行**恢复；本端点只**通知**管理员，lifecycle 保持 SUSPENDED，
     *   等管理员在审核台点「恢复上架」。合并会让"作者自助恢复"绕过举报审核。
     * ★ 档位：**只需登录**（不进 admin 档）。归属判断在 Service 里（只有作者本人能申请），
     *   所以普通用户拿别人的 surveyId 调它只会得到 400「只能为自己的问卷申请恢复」。
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
        // ★ 权限不足时抛异常，而不是 return null：
        //   return null 会被序列化成空响应体，前端没法区分"问卷不存在"和"无权限"；
        //   getOne 等接口用的是抛异常（走 GlobalExceptionHandler → 400 + 统一 JSON），这里与它们对齐。
        if (survey == null) {
            throw new BizException("问卷不存在，ID：" + id);
        }
        // ★ 发布涉及"对外可见"，editor 协作者**没有**这个权限（见 CollaboratorRole）
        surveyAccessService.requirePermission(survey, userId, role, Permission.SURVEY_PUBLISH, "发布");
        // ★ 已下架的问卷不能被直接重新发布：那等于绕过举报审核。要恢复只能走申请 → 管理员恢复。
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
        // ★ 同上：统一抛异常，前端才能拿到明确的失败原因
        if (old == null) {
            throw new BizException("问卷不存在，ID：" + id);
        }
        OwnershipGuard.requireOwnerOrAdmin(old, userId, role, "复制");
        return surveyService.copySurvey(id, userId);
    }

    // ==================== 统计 ====================

    // ★ 这里**不要**再加"系统统计"的独立端点（已在 /system-overview 里）：
    //   ① 管理端看板走 /system-overview，个人统计走 /my-statistics，没有消费方；
    //   ② 它会是 /system-overview 的**真子集** —— total/published/draft/ended/responses
    //      五个字段 system-overview 全都有，只少一个 views 与 users；
    //   ③ 状态计数统一走 countByStatus()：多一个入口就多一份需要同步的口径，
    //      少一个入口就少一次口径漂移的机会。
    @Operation(summary = "系统总览（含用户数、访客次数）")
    @GetMapping("/system-overview")
    @RequireAdmin
    public Map<String, Object> systemOverview() {
        Map<String, Object> result = surveyService.getSystemOverview();
        result.put("users", userService.countNormalUsers());
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

    // ★ 这里**不要**再加"不分页地查回收站"的端点（用下面的 /recycle/page）：
    //   回收站页（Recycle.vue）用的是 /recycle/page（支持标题搜索 + 分页）；
    //   而**一次返回全量回收站**在数据量大时既慢又会把响应体撑大。
    //   ★ 注意：SurveyService 里**没有** listAllRecycle / listRecycleByUserId 这两个方法，
    //   要加回端点得连 Service/Mapper 一起补，别只加一层空壳。
    @Operation(summary = "分页查询回收站（支持搜索）")
    @GetMapping("/recycle/page")
    public PageResult<Survey> pageRecycle(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestHeader("X-User-Id") Integer userId,
            @RequestHeader("X-User-Role") String role) {
        if (Role.isAdmin(role)) {
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
    @RequireAdmin
    public String setTemplate(@PathVariable Integer id,
                              @RequestParam Integer isTemplate,
                              @RequestParam(required = false) String category) {
        // ★ is_template 只允许 0/1：若接受任意整数，
        //   调一次 ?isTemplate=2 就会产生一份"既不是模板也不是普通问卷"的记录 ——
        //   findTemplates 要求 = 1、管理员列表要求 IS NULL OR = 0 ——
        //   它会从**两个列表里同时消失**，只能在数据库里靠 id 找回来。
        if (isTemplate == null || (isTemplate != 0 && isTemplate != 1)) {
            throw new BizException("isTemplate 只能是 0（取消模板）或 1（设为模板）");
        }
        int r = surveyService.setTemplate(id, isTemplate, category);
        if (r <= 0) throw new BizException("操作失败");
        return "操作成功";
    }

    /**
     * 是否启用了任一「高级设置包」项：答题密码 / 限收份数 / 公开结果 / IP 限制。
     * ★ 判断口径必须与 WalletFeature.ADVANCED_SETTINGS 的说明一致；
     *   以后若把新的设置项纳入这个包，**两处一起改**（这里是唯一判定处，钱包文档里也写了）。
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