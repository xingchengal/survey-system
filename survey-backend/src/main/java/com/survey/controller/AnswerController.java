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
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   SurveyFillByCode.vue    → 匿名答题页提交（/submit-share/{code}）
 *   SurveyStatistics.vue    → 统计页：答卷数、单题统计、填空题文本、答卷列表、
 *                             单份答卷详情、导出 Excel
 *   SystemStatistics.vue    → 管理员系统统计页取"每份问卷的答卷数"走的是
 *                             GET /api/survey/list-with-response-count（不再逐个调本类的
 *                             /count/{surveyId} —— 否则问卷一多就是几百次串行往返）
 *
 * ── 权限矩阵（详见 docs/问卷系统-端点权限审计表.md，随仓库入库）──
 *   公开(白名单) : POST /submit-share/{shareCode}  —— 匿名访客凭分享码提交
 *   需登录       : POST /submit/{surveyId} —— 登录态提交。★ **前端当前没有页面调用它，但它有意保留**：
 *                  它是"答题人身份落库（t_answer.user_id）/ 答卷防重 / 我的答题历史"的实现路径，
 *                  删掉会把这条路径一起堵死。完整理由（含答卷防重的现状说明）写在下面
 *                  submit() 的 javadoc 里 —— **改它之前先读那段**。
 *                  另有 GET /score/{surveyId}（查自己的成绩），同样只需登录、不需归属。
 *   需登录+归属  : GET /count|/statistics|/texts|/statistics/survey|/list|/detail|/export|/quality
 *
 * ── 两条重要约定（改这几个端点前务必看）──────────────────
 *   1. 归属校验集中在私有方法 requireSurveyOwnership()：admin 直接放行，
 *      否则要求问卷的 userId 等于当前登录用户，不通过就抛异常。
 *      ★ 为什么要放行 admin：下面 6 个端点给的都是"某一份问卷的答卷数据"，
 *        管理员要能看全站任意一份问卷的统计与导出；若这里只认作者，
 *        管理员一打开别人的问卷统计就会 400。
 *   2. 提交答卷前必须校验问卷「已发布(status==1)」：两个提交方法各查一次，
 *      而「问卷不存在 / 不在回收站 / 未发布 / **同一登录用户未交过** / 空答卷 / 超条数 /
 *      题目归属 / 同题去重 / 单题长度」这些更硬的不变量
 *      已全部收进 AnswerService.submit()（唯一落库入口，见那边的四道校验）。
 *      statistics()/texts() 拿到的是 questionId，需要用 questionService.getById() 反查 surveyId；
 *      detail() 拿到的是 answerId，需要用 answerService.getAnswerById() 反查。
 */
@Tag(name = "答卷管理", description = "答卷提交、查询、统计、导出 Excel")
@RestController
@RequestMapping("/api/answer")
public class AnswerController {

    /**
     * 单次 Excel 导出的答卷份数上限。
     * ★ 为什么是 5000：导出会把全部明细读进内存再拼二维表，按每份 5~10 条明细估算，
     *   5000 份约 3~5 万行，是"用户能接受、内存也扛得住"的平衡点。
     *   超过就明确拒绝，而不是硬撑到 OOM。
     */
    private static final int MAX_EXPORT_RESPONSES = 5000;

    /**
     * 单次导出允许的**明细行数**上限（内存预检的真正口径）。
     * ★ 内存占用取决于 答卷份数 × 每题行数，只卡份数挡不住"题多 × 答卷多"的组合：
     *   5000 × 300 = 150 万行，足以把堆打满。
     *   这里按行数取一个与"5000 份中等问卷（10 题）"同量级的值。
     */
    private static final int MAX_EXPORT_DETAILS = 50000;

    /**
     * 答题耗时的上限（24 小时）。
     * ★ 前端上报的是**客户端值**：可能是负数，也可能是天文数字。
     *   直接写库会把 int 列写爆（超过 2^31-1 会抛异常 → 500），
     *   这里夹到 [0, 24h]：既不会 500，也不让异常值污染"是否答得太快"的判定。
     */
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
     * H5 额度钱包：导出是进阶功能，每次扣 1 点（管理员免费）。
     * ★ 扣费放在"归属校验 + 上限预检"之后、真正读数据之前：
     *   越权/超限的请求不该扣钱，扣了钱却没导出更不该发生。
     */
    @Autowired
    private WalletService walletService;

    /**
     * ★ 归属校验：确认当前登录用户就是这份问卷的作者（管理员放行）。
     * 这 6 个“查看答卷数据”的接口若只验“是否登录”、不验“这份问卷是不是你的”，
     * 任何登录用户只要改一下 URL 里的 id，就能看别人的答卷数量、选项分布、
     * 填空题原文甚至导出整份 Excel（横向越权）。
     */
    private Survey requireSurveyOwnership(Integer surveyId, Integer userId, String role) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        // ★ H2：改成"能力 × 对象"：作者 / 管理员 / 能看数据的协作者（analyst、editor）。
        //   ★ 文案保持 "无权查看他人问卷的数据" 不变 —— 前端与矩阵测试都按它断言。
        if (!surveyAccessService.hasPermission(survey, userId, role, Permission.SURVEY_DATA)) {
            throw new BizException("无权查看他人问卷的数据");
        }
        return survey;
    }

    /**
     * 登录用户提交答卷。
     *
     * ★★ 本端点**有意保留**（前端零调用也先别删）—— 下一个人看到"前端零调用"时很可能想删掉它：
     *
     *   ① **它是"登录身份"唯一的写入路径**。另一条入口 {@code submit-share} 走的是
     *      {@code answerService.submit(survey.getId(), null, details)} —— **userId 恒为 null**。
     *      若只剩那一条，{@code t_answer.user_id} 就退化成"永远为 NULL 的死列"，
     *      而它本该记录的正是"**谁**答的"。
     *   ② **它是「我的答题历史」、实名问卷/内部评议的前提**（答卷防重已另行实现，见下）：
     *      这些都要先有"答题人身份"落库。
     *      删掉它等于把这些功能的**实现路径**一起堵死（将来得先恢复端点、再补读写逻辑）。
     *   ③ 成本极低：守卫（未发布 / 回收站 / 归属）全在 {@code AnswerService.submit} 里，
     *      限流与审计也已就位（见下），多这一个端点不增加维护负担。
     *
     *   ★ **防重已实现**（见 {@code AnswerService.submit} 的第四道校验）：登录用户对同一问卷
     *     重复提交 → 400「你已经提交过这份问卷」；用例在 {@code AnswerServiceGuardTest}
     *     （含"匿名侧不查重"的回归断言）。
     *     ★ **匿名侧有意不拦**：按 IP 去重会误伤 NAT 后的同网用户（出口 IP 相同即被判重复），
     *       所以匿名重复提交不在这一层拦。
     *
     *   ★ 与匿名入口的分工：本端点要求**已发布(status=1)**；匿名那条凭分享码。
     *     两者最终都进 {@code AnswerService.submit}，那里还有更硬的守卫（非空 / 条数上限 /
     *     不在回收站 / 已发布 / 题目归属 / 同题去重 / 单题长度）。
     *
     *   ★ 限流：{@code RateLimitInterceptor} 对 {@code /api/answer/submit/} 按**登录用户** 60 次/分。
     *     若撤掉它，脚本可无限次提交答卷灌库，把统计口径直接冲垮。
     */
    @Operation(summary = "提交答卷（登录用户）")
    @PostMapping("/submit/{surveyId}")
    public SubmitResult submit(@PathVariable Integer surveyId,
                         @RequestBody List<AnswerDetail> details,
                         @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                         @RequestHeader(value = "X-Survey-Password", required = false) String password,
                         @RequestParam(value = "durationMs", required = false) Long durationMs,
                         HttpServletRequest request) {
        // ★ 与下面 submitByShareCode 保持一致：只有【已发布】(status=1) 的问卷才收答卷。
        //   （"回收站里的问卷不能收答卷"由 AnswerService.submit 的四道校验兜住 —— 不变量收口在 Service，
        //     这里的判断只是为了给出更早、更明确的 400 文案。）
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) throw new BizException("问卷不存在");
        if (!SurveyStatus.isPublished(survey.getStatus())) throw new BizException("问卷未发布");
        // ★ 答题密码：提交同样要校验（不能只拦"加载题目"，否则直接 POST 就能塞答卷）
        surveyService.requireAccessPassword(survey, password);
        // ★ IP 限制用**连接 IP**（不看 X-Forwarded-For，防止换个请求头绕过）
        return answerService.submit(surveyId, userId, details, clampDuration(durationMs),
                WebUtils.getConnectionIp(request));
    }

    @Operation(summary = "提交答卷（匿名访客，通过分享码）")
    @PostMapping("/submit-share/{shareCode}")
    public SubmitResult submitByShareCode(@PathVariable String shareCode,
                                    @RequestBody List<AnswerDetail> details,
                                    @RequestHeader(value = "X-Survey-Password", required = false) String password,
                                    @RequestParam(value = "durationMs", required = false) Long durationMs,
                                    HttpServletRequest request) {
        Survey survey = surveyService.getByShareCode(shareCode);
        if (survey == null) throw new BizException("问卷不存在");
        if (!SurveyStatus.isPublished(survey.getStatus())) throw new BizException("问卷未发布");
        surveyService.requireAccessPassword(survey, password);
        // ★ 匿名提交同样判分：成绩随返回体回给答题页（之后无法凭身份再查 —— 匿名没有 user_id）。
        return answerService.submit(survey.getId(), null, details, clampDuration(durationMs),
                WebUtils.getConnectionIp(request));
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
        // ★ 传题目本体（不是 id）：排序题要走 AVG(rank_no) 那条 SQL，只看 id 无法分流题型
        return answerService.countQuestion(question, excludeInvalid);
    }

    /**
     * 整份问卷的统计（一次取回）—— 统计页专用，替代"逐题请求"。
     *
     * ★ 原来统计页发 `1（问卷详情）+ 1（答卷数）+ 1（题目列表）+ N（逐题统计/文本）` 次请求，
     *   而且是 `for + await` **串行**：N 道题就要等 N 个来回。现在只发这 1 次。
     * ★ 路径 `/statistics/survey/{surveyId}` 与 `/statistics/{questionId}` **不冲突**：
     *   后者只匹配"两段"，这里是三段；Spring 的路径匹配按段数比较，
     *   不会把 "survey" 当成 questionId 去做整数转换。
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
     * 答卷质控摘要（统计页顶部显示"其中可疑 N 份"）。
     *
     * ── ★ 只打标、不删除 ────────────────────────────────
     *   可疑答卷仍然在库里、仍然计入"答卷数"；是否剔除由统计接口的 `excludeInvalid` 决定。
     *   自动删除会让作者**无法复核**被判无效的答卷（也没法申诉"我这明明是认真填的"）。
     *
     * ── 返回什么 ────────────────────────────────────────
     *   `{ total, suspicious, suspiciousRate, reasons:[{code,label,count}], samples:[最近 50 条] }`
     *   — 规则码与中文说明的唯一口径在 `common/AnswerQuality`。
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
     * 某问卷的答卷列表（**分页**）。
     *
     * ★ 原来一次性返回全部答卷：两万份答卷时响应体是两万个对象（实测 P50 239ms），
     *   既拖慢统计页，也让"点开所有答卷"变成一个能拖垮服务端的操作。
     *   ⇒ 改成 {@code PageResult<Answer>}（total / list / pageNum / pageSize），
     *   每页条数由 {@link PageParams#pageSize} 夹在 [1, 100]，页码 ≥ 1。
     * ★ 前端对应改动：`SurveyStatistics.vue` 的"所有答卷"对话框加了分页器；
     *   `api/answer.js` 的 `list()` 多了两个参数（默认 1 / 10，与后端夹紧口径一致）。
     * ★ 归属校验仍在：先 {@code requireSurveyOwnership} 再查，避免"先查后判"。
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
     * 我答过的问卷（分页）。
     *
     * ★ 权限档位：**需登录**（只查自己的，不接受 userId 参数）。
     * ★ 匿名答卷查不到 —— 这是产品口径：没有身份就没有"我的记录"。
     * ★ 返回 `PageResult<MyAnswerItem>`：问卷标题/分数/状态由一条 JOIN 取回，前端不再 N+1。
     */
    @Operation(summary = "我答过的问卷（分页）")
    @GetMapping("/my")
    public PageResult<MyAnswerItem> myAnswers(
            @RequestHeader(value = "X-User-Id", required = false) Integer userId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return answerService.myAnswers(userId, pageNum, pageSize);
    }

    /**
     * 签到 / 取消签到（作者或管理员）。
     *
     * ★ 归属校验复用作者侧同一套（`requireSurveyOwnership`：admin 放行）。
     * ★ 问卷没启用签到核销时 400 —— 避免在没有签到的问卷上误点。
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
     * 公开报名名单（前 50 位，按接龙顺序）。
     *
     * ★ **公开档**（白名单）：接龙名单就是要给报名人看的，不要求登录。
     * ★ 作者没开启"公开报名名单"时 400 —— 默认不公开，避免把答卷里的姓名默认暴露。
     * ★ 只返回称呼与时间，不返回手机号等其它答案。
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
     * 我某份答卷的详情（逐题回放）。
     *
     * ★ 权限按**答卷归属**校验（answer.userId == 当前用户），不是问卷归属 ——
     *   答题人不是问卷作者，不能复用作者的 /answer/detail/{answerId}。
     * ★ 正确答案只在作者开启「提交后显示答案」时返回（与交卷成绩卡同口径）。
     */
    @Operation(summary = "我某份答卷的详情（逐题对错）")
    @GetMapping("/my/{answerId}")
    public MyAnswerDetailDTO myAnswerDetail(
            @PathVariable Integer answerId,
            @RequestHeader(value = "X-User-Id", required = false) Integer userId) {
        return answerService.getMyAnswerDetail(userId, answerId);
    }

    /**
     * 查询「我在某份问卷的成绩」。
     *
     * ★ 权限档位：**需登录**（只查自己，不需要问卷归属）——
     *   按 (surveyId, 登录用户 id) 定位，不接受 answerId（那要额外做归属/归属人校验）。
     * ★ 匿名答卷查不到：匿名没有身份可回溯，成绩只在提交那一刻随返回体给出。
     * ★ 考卷没开判分（earned_score 为 NULL）时明确 400，而不是返回一堆 null 让前端猜。
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

        // ★ 下面会把该问卷的**全部**答卷明细读进内存（allDetails → answerMap → rows 三层结构），
        //   十万级明细（含长文本回答）足以把堆撑爆 —— 而导出是个"顺手点一下"的操作，
        //   不该有能力拖垮整个服务。
        //   这里先做一次**廉价的 COUNT 预检**（单条 SQL，不加载数据），超过上限直接拒绝：
        //   宁可让用户看到一句明确提示，也不要让服务 OOM。
        //   ★ 若要支持更大数据量，正确做法是**按 answerId 分页查询 + EasyExcel 分批
        //     writer.write(batch, sheet)**（EasyExcel 本身支持），而不是把上限调大。
        // ★ 真正占内存的是**明细行数**而不是**答卷份数**（= 份数 × 每题行数）——
        //   5000 份 × 300 题 = 150 万行明细被读进 List，再被 answerMap 与 rows 各复制一份，
        //   只卡份数挡不住这种组合放大。所以两个口径**都查**（各一条廉价 COUNT，双保险）：
        //   份数上限防止"答卷极多"，明细行数上限防止"题多 × 答卷多"。
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

        // ★ H5：导出是进阶功能，每次 1 点。放在所有"拒绝条件"之后，保证被拒的请求不扣钱。
        //   管理员免费（管理动作不该消耗自己的额度）。
        if (!Role.isAdmin(role)) {
            walletService.chargeForExport(surveyId, userId);
        }

        List<Question> questions = questionService.getQuestionsBySurveyId(surveyId);
        List<AnswerDetail> allDetails = answerService.listDetailsBySurveyId(surveyId);

        // ★ 一道题可能有多行答案（多选/矩阵/排序），所以这里必须收成 List 而不是单个 String。
        //   旧实现是 `Map<questionId, String>`，同一题后来的行会**覆盖**前面的 ——
        //   多选题导出只留下最后一个勾选项，矩阵/排序更是只剩一行。这是本轮顺手修掉的既有缺陷。
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

        // ★ 文件名编码（两层硬约束，写错就会下载到一串 %E4%BD%A0... 的乱码名）：
        //   直接把 URLEncoder.encode(中文, "UTF-8") 拼进 filename 是不行的：
        //     1) HTTP 头里的 filename 参数按 RFC 6266 只能是 ASCII/ISO-8859-1，
        //        塞进去的百分号串浏览器**不会**自动还原，于是下载下来的就是 "xxx%%%%.xlsx"；
        //     2) 想传中文名必须用 RFC 5987 的 filename*=UTF-8''<百分号编码>，
        //        并且要再给一个 ASCII 的 filename 作为老浏览器的兜底。
        //   正确写法（现代浏览器优先读 filename*，中文名能正常显示）：
        //     attachment; filename="survey_1_answers.xlsx"; filename*=UTF-8''%E4%BD%A0...
        //   ★ 注意 URLEncoder 会把空格编成 "+"，而 RFC 5987 里空格必须是 "%20"，所以要替换。
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
     * 把一道题的全部明细拼成一个 Excel 单元格。
     *
     * ★ 渲染口径已经抽到 {@link com.survey.common.AnswerDisplay}：
     *   「我的成绩 → 查看详情」用的是同一份实现 —— 两处各写一遍必然漂移
     *   （历史上"多选只剩最后一项""矩阵丢行"都是这么来的）。
     */
    private static String formatExportCell(Question q, List<AnswerDetail> details) {
        return com.survey.common.AnswerDisplay.format(q, details);
    }
}
