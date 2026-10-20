package com.survey.controller;

import com.alibaba.excel.EasyExcel;
import com.survey.common.MatrixConfig;
import com.survey.common.PageParams;
import com.survey.common.PageResult;
import com.survey.common.QuestionType;
import com.survey.common.SurveyStatus;
import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.entity.Answer;
import com.survey.entity.AnswerDetail;
import com.survey.entity.Question;
import com.survey.dto.SurveyStatisticsDTO;
import com.survey.dto.SubmitResult;
import com.survey.dto.MyScoreDTO;
import com.survey.dto.MyAnswerItem;
import com.survey.dto.MyAnswerDetailDTO;
import com.survey.dto.ParticipantItem;
import com.survey.entity.Survey;
import com.survey.service.AnswerQualityService;
import com.survey.service.AnswerService;
import com.survey.service.WalletService;
import com.survey.service.SurveyAccessService;
import com.survey.service.QuestionService;
import com.survey.service.SurveyService;
import com.survey.util.WebUtils;
import com.survey.common.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;

/**
 * 答卷管理：提交答卷、按问卷/题目查询答卷与统计、导出 Excel。
 * 权限：公开 = POST /submit-share/{shareCode}；需登录 = POST /submit/{surveyId}、GET /score/{surveyId}；
 * 需登录+归属 = /count|/statistics|/texts|/statistics/survey|/list|/detail|/export|/quality。
 * ★ 查看数据的端点一律反查 surveyId 调 requireSurveyOwnership；只验登录会被改 URL 横向越权。
 * ★ /submit 是写入答题人身份（t_answer.user_id）的唯一路径，别因前端零调用删掉。
 */
@Tag(name = "答卷管理", description = "答卷提交、查询、统计、导出 Excel")
@RestController
@RequestMapping("/api/answer")
public class AnswerController {

    /** 单次导出的答卷份数上限：全部明细进内存再拼表，5000 份约 3~5 万行，超限明确拒绝而不是 OOM。 */
    private static final int MAX_EXPORT_RESPONSES = 5000;

    /** 单次导出的明细行数上限：内存取决于份数 × 每题行数，只卡份数挡不住"题多 × 答卷多"。 */
    private static final int MAX_EXPORT_DETAILS = 50000;

    /** 答题耗时上限 24 小时：前端上报值可能为负或天文数字，直接写 int 列会 500，这里夹到 [0, 24h]。 */
    private static final long MAX_DURATION_MS = 24L * 60 * 60 * 1000;

    /** 把前端上报的耗时夹到合理区间；null 原样返回（= 未上报）。 */
    private static Integer clampDuration(Long durationMs) {
        if (durationMs == null) {
            return null;
        }
        if (durationMs < 0) {
            return 0;
        }
        return (int) Math.min(durationMs, MAX_DURATION_MS);
    }

    @Autowired
    private AnswerService answerService;

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private SurveyAccessService surveyAccessService;

    @Autowired
    private QuestionService questionService;

    /** 质控摘要（可疑样本数 / 原因分布 / 最近样本）。 */
    @Autowired
    private AnswerQualityService answerQualityService;

    /**
     * 导出是进阶功能，每次扣 1 点（管理员免费）。
     * 扣费放在归属校验 + 上限预检之后、读数据之前：越权/超限的请求不该扣钱。
     */
    @Autowired
    private WalletService walletService;

    /**
     * 归属校验：确认当前登录用户有权看这份问卷的数据（管理员放行）。
     * 这些查看数据的接口若只验登录，改一下 URL 里的 id 就能看别人的答卷、统计甚至整份导出。
     */
    private Survey requireSurveyOwnership(Integer surveyId, Integer userId, String role) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        // 能力 × 对象：作者 / 管理员 / 能看数据的协作者（analyst、editor）。
        if (!surveyAccessService.hasPermission(survey, userId, role, Permission.SURVEY_DATA)) {
            throw new BizException("无权查看他人问卷的数据");
        }
        return survey;
    }

    /**
     * 登录用户提交答卷（前端零调用也保留）：这是把答题人身份写进 t_answer.user_id 的唯一路径。
     * 只剩匿名入口的话 user_id 永远是 NULL，"我的答题历史"、实名问卷、内部评议都没有实现基础。
     * 防重由 AnswerService.submit 的登录用户校验完成；匿名侧按 IP 去重会误伤 NAT 用户，所以不拦。
     * 守卫（未发布/回收站/题目归属等）全在 Service；限流由 RateLimitInterceptor 按登录用户 60 次/分。
     */
    @Operation(summary = "提交答卷（登录用户）")
    @PostMapping("/submit/{surveyId}")
    public SubmitResult submit(@PathVariable Integer surveyId,
                         @RequestBody List<AnswerDetail> details,
                         @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                         @RequestHeader(value = "X-Survey-Password", required = false) String password,
                         @RequestParam(value = "durationMs", required = false) Long durationMs,
                         @RequestParam(value = "attemptToken", required = false) String attemptToken,
                         HttpServletRequest request) {
        // 与匿名入口一致：只有已发布才收答卷；回收站/题目归属等硬守卫在 AnswerService.submit。
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) throw new BizException("问卷不存在");
        if (!SurveyStatus.isPublished(survey.getStatus())) throw new BizException("问卷未发布");
        // 提交同样要校验答题密码，否则直接 POST 就能塞答卷（不能只拦"加载题目"）。
        surveyService.requireAccessPassword(survey, password);
        // IP 限制用连接 IP，不看 X-Forwarded-For，防止换个请求头绕过。
        return answerService.submit(surveyId, userId, details, clampDuration(durationMs),
                WebUtils.getConnectionIp(request), attemptToken);
    }

    @Operation(summary = "提交答卷（匿名访客，通过分享码）")
    @PostMapping("/submit-share/{shareCode}")
    public SubmitResult submitByShareCode(@PathVariable String shareCode,
                                    @RequestBody List<AnswerDetail> details,
                                    @RequestHeader(value = "X-Survey-Password", required = false) String password,
                                    @RequestParam(value = "durationMs", required = false) Long durationMs,
                                    @RequestParam(value = "attemptToken", required = false) String attemptToken,
                                    HttpServletRequest request) {
        Survey survey = surveyService.getByShareCode(shareCode);
        if (survey == null) throw new BizException("问卷不存在");
        if (!SurveyStatus.isPublished(survey.getStatus())) throw new BizException("问卷未发布");
        surveyService.requireAccessPassword(survey, password);
        // 匿名提交同样判分，成绩随返回体返回（匿名没有 user_id，之后无法再查）。
        return answerService.submit(survey.getId(), null, details, clampDuration(durationMs),
                WebUtils.getConnectionIp(request), attemptToken);
    }

    @Operation(summary = "查询某问卷的答卷数量")
    @GetMapping("/count/{surveyId}")
    public int count(@PathVariable Integer surveyId,
                     @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                     @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireSurveyOwnership(surveyId, userId, role);
        return answerService.countResponses(surveyId);
    }

    @Operation(summary = "选择题选项分布统计")
    @GetMapping("/statistics/{questionId}")
    public List<Map<String, Object>> statistics(@PathVariable Integer questionId,
                                                @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                                                @RequestHeader(value = "X-User-Role", required = false) String role,
                                                @RequestParam(value = "excludeInvalid", required = false,
                                                        defaultValue = "false") boolean excludeInvalid) {
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw new BizException("题目不存在");
        }
        requireSurveyOwnership(question.getSurveyId(), userId, role);
        // 传题目本体（不是 id）：排序题要走 AVG(rank_no) 那条 SQL，只看 id 无法分流题型。
        return answerService.countQuestion(question, excludeInvalid);
    }

    /**
     * 整份问卷的统计（一次取回）：替代"1 + N 次串行请求"，统计页只发这一次。
     * 路径三段，与 /statistics/{questionId} 的两段不冲突，Spring 按段数匹配。
     */
    @Operation(summary = "整份问卷的统计（一次取回：题目 + 选项分布 + 填空文本 + 答卷数）")
    @GetMapping("/statistics/survey/{surveyId}")
    public SurveyStatisticsDTO statisticsBySurvey(@PathVariable Integer surveyId,
                                                  @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                                                  @RequestHeader(value = "X-User-Role", required = false) String role,
                                                  @RequestParam(value = "excludeInvalid", required = false,
                                                          defaultValue = "false") boolean excludeInvalid) {
        Survey survey = requireSurveyOwnership(surveyId, userId, role);
        return answerService.getSurveyStatistics(survey, excludeInvalid);
    }

    /**
     * 答卷质控摘要：只打标不删除，可疑答卷仍计入总数，是否剔除由 excludeInvalid 决定。
     * 自动删除会让作者无法复核；返回 {total, suspicious, suspiciousRate, reasons, samples}。
     */
    @Operation(summary = "答卷质控摘要（可疑样本数 / 原因分布 / 最近样本）")
    @GetMapping("/quality/{surveyId}")
    public Map<String, Object> quality(@PathVariable Integer surveyId,
                                       @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                                       @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireSurveyOwnership(surveyId, userId, role);
        return answerQualityService.summarize(surveyId, answerService.countResponses(surveyId));
    }

    @Operation(summary = "填空题的文字回答列表")
    @GetMapping("/texts/{questionId}")
    public List<String> texts(@PathVariable Integer questionId,
                              @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                              @RequestHeader(value = "X-User-Role", required = false) String role) {
        Question question = questionService.getById(questionId);
        if (question == null) {
            throw new BizException("题目不存在");
        }
        requireSurveyOwnership(question.getSurveyId(), userId, role);
        return answerService.listTextAnswers(questionId);
    }

    /**
     * 某问卷的答卷列表（分页）：每页条数由 PageParams.pageSize 夹在 [1, 100]，页码 ≥ 1。
     * 先 requireSurveyOwnership 再查，避免"先查后判"。
     */
    @Operation(summary = "查询某问卷的答卷列表（分页）")
    @GetMapping("/list/{surveyId}")
    public PageResult<Answer> listAnswers(
            @PathVariable Integer surveyId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestHeader(value = "X-User-Id", required = false) Integer userId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireSurveyOwnership(surveyId, userId, role);
        return answerService.listAnswersPage(surveyId, pageNum, pageSize);
    }

    @Operation(summary = "查询某份答卷的详细内容")
    @GetMapping("/detail/{answerId}")
    public List<AnswerDetail> detail(@PathVariable Integer answerId,
                                     @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                                     @RequestHeader(value = "X-User-Role", required = false) String role) {
        Answer answer = answerService.getAnswerById(answerId);
        if (answer == null) {
            throw new BizException("答卷不存在");
        }
        requireSurveyOwnership(answer.getSurveyId(), userId, role);
        return answerService.listDetailsByAnswerId(answerId);
    }

    /**
     * 我答过的问卷（分页）：只查自己的，不接受 userId 参数。
     * 匿名答卷查不到（没有身份就没有"我的记录"）；标题/分数由一条 JOIN 取回，前端不再 N+1。
     * 可选筛选：问卷标题关键词 + 提交日期范围（yyyy-MM-dd，闭区间）。
     */
    @Operation(summary = "我答过的问卷（分页）")
    @GetMapping("/my")
    public PageResult<MyAnswerItem> myAnswers(
            @RequestHeader(value = "X-User-Id", required = false) Integer userId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return answerService.myAnswers(userId, pageNum, pageSize, keyword, startDate, endDate);
    }

    /**
     * 签到 / 取消签到（作者或管理员）：归属复用 requireSurveyOwnership（admin 放行）。
     * 问卷没启用签到核销时 400，避免在没有签到的问卷上误点。
     */
    @Operation(summary = "签到 / 取消签到（作者）")
    @PutMapping("/checkin/{answerId}")
    public String checkin(@PathVariable Integer answerId,
                          @RequestParam(defaultValue = "true") boolean checkedIn,
                          @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                          @RequestHeader(value = "X-User-Role", required = false) String role) {
        Answer answer = answerService.getAnswerById(answerId);
        if (answer == null) {
            throw new BizException("答卷不存在");
        }
        requireSurveyOwnership(answer.getSurveyId(), userId, role);
        return answerService.setCheckedIn(answerId, checkedIn) ? "已签到" : "已取消签到";
    }

    /**
     * 公开报名名单（前 50 位）：公开档，不要求登录。
     * 作者没开启"公开报名名单"时 400；只返回称呼与时间，不返回手机号等其它答案。
     */
    @Operation(summary = "公开报名名单（前 50 位）")
    @GetMapping("/participants/{shareCode}")
    public List<ParticipantItem> participants(@PathVariable String shareCode) {
        Survey survey = surveyService.getByShareCode(shareCode);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        return answerService.listParticipants(survey);
    }

    /**
     * 作者端报名 / 签到名单（最多 500 条）：归属复用 requireSurveyOwnership（admin 放行）。
     * 与公开名单分开：这里带签到状态与签到时间，且不要求作者开启「公开报名名单」。
     */
    @Operation(summary = "报名 / 签到名单（作者）")
    @GetMapping("/participants/manage/{surveyId}")
    public Map<String, Object> manageParticipants(
            @PathVariable Integer surveyId,
            @RequestHeader(value = "X-User-Id", required = false) Integer userId,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        requireSurveyOwnership(surveyId, userId, role);
        return answerService.listParticipantsForOwner(surveyId);
    }

    /**
     * 我某份答卷的详情：权限按答卷归属（answer.userId）校验，不是问卷归属。
     * 正确答案只在作者开启「提交后显示答案」时返回。
     */
    @Operation(summary = "我某份答卷的详情（逐题对错）")
    @GetMapping("/my/{answerId}")
    public MyAnswerDetailDTO myAnswerDetail(
            @PathVariable Integer answerId,
            @RequestHeader(value = "X-User-Id", required = false) Integer userId) {
        return answerService.getMyAnswerDetail(userId, answerId);
    }

    /**
     * 我在某份问卷的成绩：只查自己，接受 surveyId 而不是 answerId（后者要额外做归属校验）。
     * 匿名答卷查不到；没开判分（earned_score 为 NULL）时明确 400。
     */
    @Operation(summary = "查询我在某份问卷的成绩")
    @GetMapping("/score/{surveyId}")
    public MyScoreDTO myScore(@PathVariable Integer surveyId,
                              @RequestHeader(value = "X-User-Id", required = false) Integer userId) {
        if (userId == null) {
            throw new BizException("请先登录后再查看成绩");
        }
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        Answer answer = answerService.getLatestAnswer(surveyId, userId);
        if (answer == null) {
            throw new BizException("你还没有提交过这份问卷");
        }
        if (answer.getEarnedScore() == null) {
            throw new BizException("这份问卷没有开启考试判分");
        }
        return new MyScoreDTO(surveyId, survey.getTitle(), answer.getTotalScore(),
                answer.getEarnedScore(), survey.getPassScore(), answer.getIsPassed(),
                answer.getSubmitTime());
    }

    @Operation(summary = "导出某问卷的答卷为 Excel")
    @GetMapping("/export/{surveyId}")
    public void export(@PathVariable Integer surveyId,
                       @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                       @RequestHeader(value = "X-User-Role", required = false) String role,
                       HttpServletResponse response) throws IOException {
        Survey survey = requireSurveyOwnership(surveyId, userId, role);

        // 全部答卷明细会读进内存再拼表，十万级明细足以撑爆堆，而导出只是"顺手点一下"。
        // 先做两条廉价 COUNT 预检（份数 + 明细行数），超过上限直接拒绝，不让服务 OOM。
        // 真要支持更大数据量，应按 answerId 分批查询 + EasyExcel 分批写，而不是调大上限。
        int responseCount = answerService.countResponses(surveyId);
        if (responseCount > MAX_EXPORT_RESPONSES) {
            throw new BizException("该问卷已有 " + responseCount + " 份答卷，单次最多导出 "
                    + MAX_EXPORT_RESPONSES + " 份。建议分批导出（或直接在数据库侧导出）");
        }
        int detailCount = answerService.countDetails(surveyId);
        if (detailCount > MAX_EXPORT_DETAILS) {
            throw new BizException("该问卷的答卷明细共 " + detailCount + " 行，单次最多导出 "
                    + MAX_EXPORT_DETAILS + " 行。建议分批导出（或直接在数据库侧导出）");
        }

        // 扣费放在所有拒绝条件之后：被拒的请求不扣钱；管理端人员免费。
        if (!Role.isStaff(role)) {
            walletService.chargeForExport(surveyId, userId);
        }

        List<Question> questions = questionService.getQuestionsBySurveyId(surveyId);
        List<AnswerDetail> allDetails = answerService.listDetailsBySurveyId(surveyId);

        // 一道题可能有多行答案（多选/矩阵/排序），必须收成 List：单值 Map 会让后一行覆盖前一行。
        Map<Integer, Map<Integer, List<AnswerDetail>>> answerMap = new LinkedHashMap<>();
        for (AnswerDetail d : allDetails) {
            answerMap.computeIfAbsent(d.getAnswerId(), k -> new HashMap<>())
                    .computeIfAbsent(d.getQuestionId(), k -> new ArrayList<>())
                    .add(d);
        }

        List<List<String>> head = new ArrayList<>();
        head.add(Collections.singletonList("答卷ID"));
        for (Question q : questions) {
            head.add(Collections.singletonList(q.getTitle()));
        }

        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, List<AnswerDetail>>> entry : answerMap.entrySet()) {
            List<Object> row = new ArrayList<>();
            row.add(entry.getKey());
            for (Question q : questions) {
                List<AnswerDetail> details = entry.getValue().getOrDefault(q.getId(), List.of());
                row.add(formatExportCell(q, details));
            }
            rows.add(row);
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");

        // 文件名两层编码：filename 只能 ASCII，中文名必须放 filename*（RFC 5987），否则下载到 %E4... 乱码。
        // URLEncoder 把空格编成 "+"，而 RFC 5987 要求 "%20"，所以要替换。
        String baseName = survey.getTitle() + "_答卷数据";
        String encodedName = URLEncoder.encode(baseName, "UTF-8").replace("+", "%20");
        String asciiName = "survey_" + surveyId + "_answers.xlsx";
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + asciiName + "\"; filename*=UTF-8''" + encodedName + ".xlsx");

        EasyExcel.write(response.getOutputStream())
                .head(head)
                .sheet("答卷数据")
                .doWrite(rows);
    }

    /**
     * 按题型把一道题的全部明细拼成一个 Excel 单元格（多选/矩阵/排序一行一题）。
     * 渲染统一走 AnswerDisplay：与「我的成绩 → 查看详情」共用，两处各写一遍必然漂移。
     */
    private static String formatExportCell(Question q, List<AnswerDetail> details) {
        return com.survey.common.AnswerDisplay.format(q, details);
    }
}
