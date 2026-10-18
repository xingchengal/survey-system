package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.Grader;
import com.survey.common.MatrixConfig;
import com.survey.common.PageParams;
import com.survey.common.PageResult;
import com.survey.common.QuestionType;
import com.survey.common.SurveyLifecycle;
import com.survey.common.SurveyStatus;
import com.survey.common.TextLimits;
import com.survey.entity.Answer;
import com.survey.entity.AnswerDetail;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.dto.MyAnswerItem;
import com.survey.dto.SurveyStatisticsDTO;
import com.survey.dto.SubmitResult;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyIpMapper;
import com.survey.mapper.SurveyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 答卷业务：提交答卷（含给问卷创建者发通知）、按问卷/题目查询答卷与统计。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   AnswerController    → 全部答卷接口
 *   （系统统计的答卷数**不经过本类**：它走 SurveyService.countResponsesBySurveyIds。）
 *
 * ── 涉及的表 ──────────────────────────────────────────
 *   t_answer（一次提交一条）、t_answer_detail（一题一行）
 *
 * ── 提交为什么必须是事务 ──────────────────────────────
 *   submit() 标了 @Transactional：先插 t_answer 拿到自增 id，
 *   再用这个 id 插每一条明细。中间任何一步失败都会整体回滚 ——
 *   不会留下"有答卷记录、却没有明细"的脏数据。
 *
 * ── 通知什么时候发（容易误解，写在这里）────────────────
 *   给问卷作者发的站内通知**不在事务内直接发**，而是注册成 afterCommit 回调：
 *   提交成功之后才调用 notificationService.send()（它自己又是 @Async）。
 *   原因：send 是异步的，如果在事务里直接调，异步线程可能在**主事务提交之前**就把通知插进去；
 *   一旦主事务随后回滚，用户就会收到"有新答卷"却点进去看不到任何数据。
 *   实现见方法内的 notifyAfterCommit()。
 *
 * ── 提交的契约校验 ────────────────────────────────────
 *   ① **明细里的 questionId 必须真的属于本次的 surveyId** ——
 *      否则直接调 API 就能把别的问卷的题目 id 塞进来污染那题的统计。
 *      提交前用一次 `findBySurveyId` 取出合法题目 id 集合做批量过滤（一次查询，不构成 N+1）。
 *   ② 单条回答内容有长度上限（★ 1000 字，与 t_answer_detail.content 的列宽 varchar(1000) 一致；
 *      上限若大于列宽，超长会以 500 收场，见 common/TextLimits）。
 *   ③ 空答卷直接拒绝。
 *   ④ **同题行数按题型区别对待**：
 *        单选 / 填空 → 一题一行，重复出现即视为灌票（400）；
 *        多选       → **一选项一行**，允许多行，但每题最多 {@link #MAX_ROWS_PER_QUESTION} 行。
 *      ★ 为什么多选是多行：历史答卷、演示数据、统计页的 `GROUP BY content` 三处口径
 *        都是"一选项一行"；若要求"同题只能一行"，**多选题勾 ≥2 项会提交必然 400**，
 *        而且统计页按内容分组的饼图会失真。
 *        详见 submit() 里的长注释与 entity/AnswerDetail 的字段说明。
 *   ⑤ **答卷防重**：同一登录用户对同一问卷只能交一次，重复提交 400；
 *      **匿名侧有意不做**（会误伤 NAT 后的同网用户），局限与并发绕过口径写在 submit() 里。
 */
@Service
public class AnswerService {

    /**
     * 单题回答内容的长度上限（= t_answer_detail.content 的列宽 varchar(1000)）。
     * ★ 若这里的值大于列宽：1001~5000 字的回答会通过 submit() 的校验、再被 MySQL 拒绝，
     *   用户拿到 500 而不是 400「单题回答不能超过 1000 个字符」。
     *   口径统一在 common/TextLimits；要放宽请先 ALTER 列宽。
     */
    private static final int MAX_CONTENT_LENGTH = TextLimits.ANSWER_CONTENT;

    /**
     * 一次提交最多允许的作答条目数。
     * ★ 必须有条数上限：{@code submit} 的请求体是 {@code List<AnswerDetail>}，
     *   一份正常问卷最多几十题，但一个构造出来的请求可以塞进几十万条明细，
     *   每条都要过一次归属校验 + 一次 INSERT（还是逐个插入、在同一个事务里）。
     *   这与"单题 1000 字"的长度上限是两回事：长度校验管不住"条数"。
     */
    private static final int MAX_DETAILS_PER_SUBMIT = 200;

    /**
     * 同一道**多选题**在一次提交里最多允许几行（= 一个选项一行）。
     * ★ 与"单题最多多少个选项"对齐（{@code QuestionService.MAX_OPTIONS_PER_QUESTION} = 50）：
     *   选项是答案的合法取值集合，行数超过选项数本身就不正常了。
     * ★ 上限仍然存在是有意的：不加限制的话，一道多选可以塞 200 行
     *   （正好用满 {@link #MAX_DETAILS_PER_SUBMIT}），而统计按 content 分组计数
     *   → 那就又回到"一次 POST 给某个选项灌 200 票"的老问题。
     */
    private static final int MAX_ROWS_PER_QUESTION = 50;

    /**
     * 整份问卷统计接口里，**每道填空题最多返回多少条文本**。
     * ★ 与单题接口 `AnswerDetailMapper.findTextAnswers` 的 `LIMIT 2000` 对齐 ——
     *   两个入口对同一份数据必须给出同样的条数，否则统计页与单题页会显示不一样的列表。
     */
    private static final int MAX_TEXT_ANSWERS_PER_QUESTION = 2000;

    /** 评分题的合法取值（前端渲染 5 颗星，1~5 分） */
    private static final Set<String> RATING_VALUES = Set.of("1", "2", "3", "4", "5");

    /** NPS 的分数区间（0~10，整数） */
    private static final int NPS_MIN = 0;
    private static final int NPS_MAX = 10;

    /**
     * 限时答卷的宽限（毫秒）：前端倒计时到 0 后还会有一两次网络往返，
     * 卡得太死会把"刚好最后一秒点提交"的人误判成超时。
     */
    private static final long TIME_LIMIT_GRACE_MS = 10_000L;

    /** 日期题的格式（value-format 统一为 yyyy-MM-dd，避免客户端时区把日期差一天） */
    private static final java.util.regex.Pattern DATE_PATTERN =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    @Autowired
    private AnswerMapper answerMapper;
    @Autowired
    private AnswerDetailMapper answerDetailMapper;
    @Autowired
    private SurveyMapper surveyMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private StatsCacheService statsCacheService;
    @Autowired
    private OptionMapper optionMapper;
    @Autowired
    private AnswerQualityService answerQualityService;

    /** IP 限制：记录"哪个 IP 已经提交过某份问卷"（唯一键兜底并发）。 */
    @Autowired
    private SurveyIpMapper surveyIpMapper;

    /**
     * 提交一份答卷。
     *
     * ★ 两种入口共用它：
     *     登录用户  → userId 是当前登录用户
     *     匿名访客  → userId 传 null（凭分享码答题，不需要账号）
     *
     * ★★ "能不能收答卷"这条不变量**收在这里**（唯一落库入口）：
     *   ① 问卷存在、② 不在回收站、③ 已发布、④ **未过截止时间**、
     *   ⑤ **同一登录用户未交过**（防重，匿名侧不查）。
     *   ★ 为什么不写在 Controller 里：两个提交入口各写一遍必然漏一处 ——
     *     若 {@code /submit/{surveyId}} 只查 {@code status}、**不查 deleted**，而
     *     moveToRecycle 只改 deleted 不改 status ⇒ **回收站里的已发布问卷照样能继续收答卷**。
     *     收进 Service 之后，以后新增提交入口也绕不过。
     * ★ 插入完成后给问卷创建者发一条站内通知；创建者是模板（userId=0）时不发。
     *
     * @param surveyId 问卷 id
     * @param userId   答题人 id，匿名为 null
     * @param details  每题一行 {questionId, content}
     */
    @Transactional
    public SubmitResult submit(Integer surveyId, Integer userId, List<AnswerDetail> details) {
        return doSubmit(surveyId, userId, details, null, null);
    }

    /**
     * 提交答卷（带"答题耗时"）—— 供质控判定"是否答得太快"。
     *
     * ★ 两个重载**各自**标 `@Transactional`、且都调 private 的 `doSubmit`，互不调用：
     *   同类内部调用会让事务注解失效（《交接文档》约定 9）。
     *
     * @param durationMs 答题页上报的耗时（毫秒），可为 null（老客户端 / 直接调接口）。
     *                   ★ 客户端值、可伪造 —— 只用于质控提示，不作安全边界。
     */
    @Transactional
    public SubmitResult submit(Integer surveyId, Integer userId, List<AnswerDetail> details, Integer durationMs) {
        return doSubmit(surveyId, userId, details, durationMs, null);
    }

    /**
     * 提交答卷（带耗时 + 客户端 IP）—— 两个 HTTP 入口用这个重载。
     *
     * @param clientIp 连接 IP（{@code WebUtils.getConnectionIp}，**不看 X-Forwarded-For**）。
     *                 只有问卷开启「IP 限制」时才会用到；为 null 时若问卷要求 IP 限制会 400。
     */
    @Transactional
    public SubmitResult submit(Integer surveyId, Integer userId, List<AnswerDetail> details,
                       Integer durationMs, String clientIp) {
        return doSubmit(surveyId, userId, details, durationMs, clientIp);
    }

    private SubmitResult doSubmit(Integer surveyId, Integer userId, List<AnswerDetail> details,
                          Integer durationMs, String clientIp) {
        if (details == null || details.isEmpty()) {
            throw new BizException("答卷内容不能为空");
        }
        if (details.size() > MAX_DETAILS_PER_SUBMIT) {
            throw new BizException("一次提交的作答条目过多（最多 " + MAX_DETAILS_PER_SUBMIT + " 条）");
        }

        // ★★ "能否收答卷"的五道校验（**唯一落库入口**，详见方法 javadoc）
        Survey survey = surveyMapper.findById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (survey.getDeleted() != null && survey.getDeleted() != 0) {
            throw new BizException("问卷已在回收站中，无法提交答卷");
        }
        if (!SurveyStatus.isPublished(survey.getStatus())) {
            throw new BizException("问卷未发布");
        }

        // ★★ 第三道半：**截止时间已过就拒收**（不能指望"等定时任务把 status 改成 2"）。
        //
        //   为什么必须在这里判：
        //     · `SurveyService.autoPublishAndClose` 每分钟才把过期问卷置为 status=2，
        //       而**提交守卫若只看 status**，从 end_time 到"下一次整分钟"之间就存在窗口，
        //       窗口内提交一律被接受（end_time 已过 10 分钟、status 仍为 1 时，
        //       匿名与登录态提交都会返回 200「提交成功」）；
        //     · 窗口上限不是 60 秒而是**不确定的**：应用在该时刻没运行（停机/重启/调度未跑），
        //       过期问卷会一直停在被接受的状态，直到下一次成功扫描为止；
        //     · 而且提交是**服务端接口**，任何人都能绕过前端页面直接请求 ——
        //       前端的 `isExpired` 只是体验层（禁用按钮 + 倒计时），不能当作约束。
        //   ⇒ 口径与 publish() 里的"是否延长到未来"保持一致：**null = 不限制**，
        //     否则必须严格晚于当前时间。定时任务仍负责把 status 改成 2（供看板与列表展示），
        //     但"能不能收答卷"不依赖它。
        if (survey.getEndTime() != null
                && !survey.getEndTime().isAfter(java.time.LocalDateTime.now())) {
            throw new BizException("问卷已截止，无法提交答卷");
        }

        // ★★ 仅限登录用户填写：**服务端必须自己判**，不能只靠前端那个登录门。
        //   · submit-share 传的 userId 恒为 null → 这条会拒掉所有匿名提交；
        //   · 登录用户走 POST /answer/submit/{surveyId}（带 token）才会 userId 非空；
        //   · 与"防重"的关系：实名答卷因此受 uk_survey_user（一人一份）约束。
        //   ★ 位置放在"能不能收"的判断之后、"答卷内容校验"之前：早失败、不做无用功。
        if (survey.getRequireLogin() != null && survey.getRequireLogin() == 1 && userId == null) {
            throw new BizException("该问卷仅限登录用户填写，请先登录后再提交");
        }

        // ★★ 限时：按前端上报的答题耗时校验（+10 秒宽限）。
        //   ★ 这不是防作弊：duration_ms 是客户端值、可伪造（见 entity/Answer 与《待解决问题》C8）；
        //     它挡的是"页面倒计时结束还继续答题"的正常超时。
        //   ★ durationMs 为 null（老客户端/直接调接口）时不拦 —— 与改造前行为一致；
        //     这个缺口是已知的，要堵得靠"开始答卷发服务端时间凭证"，不在本轮范围。
        if (survey.getTimeLimitSeconds() != null && durationMs != null
                && durationMs > survey.getTimeLimitSeconds() * 1000L + TIME_LIMIT_GRACE_MS) {
            int limit = survey.getTimeLimitSeconds();
            String limitText = limit % 60 == 0 ? (limit / 60) + " 分钟" : limit + " 秒";
            throw new BizException("答题超时（限时 " + limitText + "），本次答卷未提交");
        }

        // ★★ 第五道：**答卷防重**（登录态）。
        //   · 登录用户对同一问卷只能交一次，重复 400「你已经提交过这份问卷」；
        //   · **匿名侧有意不查重**：凭分享码答题不需要账号，而"同一 IP 只能答一次"会误伤
        //     同一宿舍/教室/公司出口 IP 下的人（NAT 很常见）。匿名侧的防线是限流 30 次/分/IP
        //     —— 挡得住个人灌水，挡不住换 IP 反复填，这是**已知局限**。
        //   · 顺序：先判"这份问卷还能不能收"（上面三道），再判"你是不是已经交过" ——
        //     反过来会在问卷已删时先告诉用户"你交过了"，文案会误导。
        //   · ★ 已知局限：这是 check-then-act，并发双击理论上能灌两份。彻底堵住要加
        //     `UNIQUE KEY (survey_id, user_id)` —— MySQL 唯一索引**不约束 NULL**，
        //     所以匿名答卷（user_id = NULL）天然不受它限制，正好与"匿名不防重"一致；
        //     副作用是同一登录用户对同一问卷永久只能有一行（未来若做"删后重填"会被挡）。
        if (userId != null && answerMapper.countBySurveyAndUser(surveyId, userId) > 0) {
            throw new BizException("你已经提交过这份问卷，不能重复提交");
        }

        // ★★ 第六道：**限量收集**（问卷设置里的 max_responses）。
        //   达到上限后直接拒收，不等定时任务去改 status（改 status 会连带影响"重新发布"的语义）。
        //   ★ 已知局限：这是 check-then-act，同一瞬间的并发提交可能多收 1~2 份。
        //     对"限量"这种业务要求足够；彻底堵住要加计数行 + 行锁，收益不划算。
        if (survey.getMaxResponses() != null && survey.getMaxResponses() > 0
                && answerMapper.countBySurveyId(surveyId) >= survey.getMaxResponses()) {
            throw new BizException("这份问卷已收满 " + survey.getMaxResponses() + " 份答卷，无法继续提交");
        }

        // ★★ 被举报下架（lifecycle=SUSPENDED）一律拒收。
        //   注意：**不能只看 status** —— 下架时 status 仍是 1（已发布），
        //   这正是为什么要新增 lifecycle 而不是硬塞 status=3（会让全站 status==1 判断失效）。
        if (SurveyLifecycle.SUSPENDED.code().equals(survey.getLifecycle())) {
            throw new BizException("该问卷已被下架，无法提交答卷");
        }

        // ★ 契约校验（见类注释）：
        //   ① questionId 必须属于本问卷；② 内容长度上限；③ 同题去重（★ 见下，多选题例外）。
        //   ★ 合法性用"一次批量查询"得到，不逐题查库 —— 答卷题量有限，也不会构成 N+1。
        List<Question> surveyQuestions = questionMapper.findBySurveyId(surveyId);
        Set<Integer> validQuestionIds = surveyQuestions.stream()
                .map(Question::getId)
                .collect(Collectors.toSet());
        // 题型取值校验要用到题型：id → Question（一次批量查询顺手建好，不额外查库）
        Map<Integer, Question> questionById = surveyQuestions.stream()
                .filter(q -> q.getId() != null)
                .collect(Collectors.toMap(Question::getId, q -> q, (a, b) -> a));
        // ★★ "一题多行"的题型：多选（一选项一行）、矩阵（一行一条）、排序（一名一条）。
        //   为什么必须放行（务必看完再改）：
        //     · 前端答题页对多选的每个勾选项各生成一条明细（SurveyFillByCode.vue），
        //       演示数据也是这个形态（DemoDataService），而库里**历史答卷本来就是多行**
        //       （如 answer_id=1 + question_id=8 有 3 行）—— 三处口径一致，都是"一选项一行"；
        //     · 矩阵一道题天然有 N 行（每行选一个列），排序一道题天然有 N 条（每个名次一条），
        //       它们和"同题重复提交刷票"完全是两回事；
        //     · 统计页按 `GROUP BY content`（矩阵再按 row_key 分组）数分布，
        //       这个口径**只认一行一条**：若把矩阵拼成一行字符串，图表会按"整串组合"分组而失真。
        //   ★ 其它题型（单选/下拉/填空/评分/日期/NPS）仍严格"一题一行"，重复出现即视为灌票。
        Set<Integer> multiRowQuestionIds = surveyQuestions.stream()
                .filter(q -> QuestionType.isMultiRow(q.getType()))
                .map(Question::getId)
                .collect(Collectors.toSet());
        // 同题出现的行数：用来在不误杀多行题型的前提下，仍然挡住"一次 POST 给某个选项灌 200 票"
        Map<Integer, Integer> rowsPerQuestion = new HashMap<>();
        Map<Integer, List<AnswerDetail>> detailsByQuestion = new HashMap<>();
        for (AnswerDetail d : details) {
            if (d.getQuestionId() == null || !validQuestionIds.contains(d.getQuestionId())) {
                throw new BizException("答卷包含不属于该问卷的题目");
            }
            int rows = rowsPerQuestion.merge(d.getQuestionId(), 1, Integer::sum);
            boolean multiRow = multiRowQuestionIds.contains(d.getQuestionId());
            if (!multiRow && rows > 1) {
                // 单选/填空/评分/日期/NPS：一句话只能有一个答案，重复出现即视为灌票
                throw new BizException("同一道题在一次提交里出现了多次");
            }
            if (multiRow && rows > MAX_ROWS_PER_QUESTION) {
                // 多行题型：行数上限与"单题最多多少个选项/行"对齐
                if (QuestionType.MULTI.code() == questionById.get(d.getQuestionId()).getType()) {
                    throw new BizException("同一道多选题的作答选项过多（最多 "
                            + MAX_ROWS_PER_QUESTION + " 个）");
                }
                throw new BizException("同一道题的作答条目过多（最多 " + MAX_ROWS_PER_QUESTION + " 条）");
            }
            if (d.getContent() != null && d.getContent().length() > MAX_CONTENT_LENGTH) {
                throw new BizException("单题回答不能超过 " + MAX_CONTENT_LENGTH + " 个字符");
            }
            detailsByQuestion.computeIfAbsent(d.getQuestionId(), k -> new ArrayList<>()).add(d);
            // ★ 新题型的取值校验：评分 / 日期 / NPS（矩阵/排序另有结构化校验，见下）。
            //   不校验的话，客户端可以往评分题里塞任意字符串，
            //   统计图（按 content 分组）就会出现 "abc"、"6" 这种分数。
            validateContentByType(questionById.get(d.getQuestionId()), d);
        }

        // ★ 矩阵 / 排序的**结构化校验**：这两类题光看单条 content 不够，
        //   还要校验"行是否属于本题""名次是否 1..N 连续且不重复"。不校验的话，
        //   客户端可以塞进任意 row_key / rank_no，统计页会凭空多出几行几列。
        Map<Integer, List<Option>> rankingOptions = new HashMap<>();
        Set<Integer> rankingQuestionIds = surveyQuestions.stream()
                .filter(q -> QuestionType.isRanking(q.getType()))
                .map(Question::getId)
                .collect(Collectors.toSet());
        if (!rankingQuestionIds.isEmpty()) {
            List<Option> options = optionMapper.findByQuestionIds(new ArrayList<>(rankingQuestionIds));
            for (Option opt : options) {
                rankingOptions.computeIfAbsent(opt.getQuestionId(), k -> new ArrayList<>()).add(opt);
            }
        }
        for (Question q : surveyQuestions) {
            List<AnswerDetail> questionDetails = detailsByQuestion.get(q.getId());
            if (questionDetails == null) {
                continue;
            }
            if (QuestionType.isMatrix(q.getType())) {
                validateMatrixAnswer(q, questionDetails);
            } else if (QuestionType.isRanking(q.getType())) {
                validateRankingAnswer(q, questionDetails,
                        rankingOptions.getOrDefault(q.getId(), List.of()));
            }
        }

        // ★ IP 限制（高级设置包）：开启后同一 IP 只能提交一次。
        //   · 记录与答卷在**同一事务**里写：后面任何一步失败都会一起回滚，不会白占 IP；
        //   · 唯一键 (survey_id, ip) 是并发防线：两个请求同时提交也只有一个能过；
        //   · 用连接 IP（不看 X-Forwarded-For），避免换个请求头就绕过限制。
        if (survey.getIpLimit() != null && survey.getIpLimit() == 1) {
            if (clientIp == null || clientIp.isBlank()) {
                throw new BizException("无法识别提交来源 IP，暂时无法提交（该问卷开启了 IP 限制）");
            }
            if (surveyIpMapper.insertIgnore(surveyId, clientIp) == 0) {
                throw new BizException("该问卷开启了 IP 限制：同一网络只能提交一次");
            }
        }

        // ★★ 判分（考试模式）：在**写库之前**用已经查出来的题目 + 一次批量查出的选项算分。
        //   为什么放在这里：
        //     · 分数要与 t_answer 的 INSERT 一起落库（同一条语句），任何后续回滚都不会留下
        //       "答卷没了、分数还在"的错位；也避免"先插 0 分再 UPDATE"的两步写入；
        //     · 明细已经全部校验过（题型/结构/长度），这里不再做二次校验；
        //     · 只查"设了分值的题"的选项（通常几道到几十道），不构成 N+1。
        //   ★ 普通问卷（gradeEnabled!=1）完全跳过：不多查一条 SQL，也不改任何字段。
        Grader.Result grading = null;
        if (survey.getGradeEnabled() != null && survey.getGradeEnabled() == 1) {
            List<Integer> gradableQuestionIds = surveyQuestions.stream()
                    .filter(q -> q.getId() != null && q.getScore() != null && q.getScore() > 0)
                    .filter(q -> Grader.isGradable(q.getType()))
                    .map(Question::getId)
                    .collect(Collectors.toList());
            Map<Integer, List<Option>> gradableOptions = new HashMap<>();
            if (!gradableQuestionIds.isEmpty()) {
                for (Option opt : optionMapper.findByQuestionIds(gradableQuestionIds)) {
                    gradableOptions.computeIfAbsent(opt.getQuestionId(), k -> new ArrayList<>()).add(opt);
                }
            }
            grading = Grader.grade(survey, surveyQuestions, gradableOptions, details);
        }

        Answer answer = new Answer();
        answer.setSurveyId(surveyId);
        answer.setUserId(userId);
        if (grading != null) {
            answer.setTotalScore(grading.totalScore());
            answer.setEarnedScore(grading.earnedScore());
            answer.setIsPassed(grading.passed());
        }
        try {
            answerMapper.insert(answer);
        } catch (DuplicateKeyException e) {
            // ★ uk_survey_user（survey_id, user_id）是防重的**最后一道防线**：
            //   上面那次 countBySurveyAndUser 是 check-then-act，两个并发请求可能都通过检查；
            //   这时唯一索引会拒掉后到的那条 —— 翻译成与上面一致的 400，而不是让它变成 500。
            //   （匿名答卷 user_id 为 NULL，唯一索引不约束 NULL，所以不受这道影响。）
            throw new BizException("你已经提交过这份问卷，不能重复提交");
        }

        // ★ 判分题的逐题对错随明细一起落库（t_answer_detail.is_correct）：
        //   · 多选一选项一行，同一道题的所有行拿到**同一个**值（口径是按题，不是按行）；
        //   · 非计分题 / 普通问卷不写（保持 NULL），成绩卡据此只标"有判分"的题。
        Map<Integer, Integer> correctnessByQuestion = new HashMap<>();
        if (grading != null) {
            for (Grader.QuestionResult qr : grading.questions()) {
                correctnessByQuestion.put(qr.questionId(), qr.correct() ? 1 : 0);
            }
        }
        for (AnswerDetail d : details) {
            d.setAnswerId(answer.getId());
            Integer correct = correctnessByQuestion.get(d.getQuestionId());
            if (correct != null) {
                d.setIsCorrect(correct);
            }
            answerDetailMapper.insert(d);
        }

        // ★★ 质控判定：基于"刚插入的明细 + 题目 + 选项"，在**同一事务**里回写标记。
        //   放在这里（明细插完之后、缓存刷新之前）：
        //     · 明细没插完就没法判定（一道多选题就是多行）；
        //     · 必须同事务 —— 否则"答卷存了、标记没写"会让质控统计悄悄少一份，且没有任何报错。
        evaluateQuality(answer.getId(), surveyId, details, surveyQuestions, durationMs);

        // ★★ 缓存维护：提交成功后要让统计缓存反映这份新答卷。
        //   · 答卷数：**只有缓存已存在时**才自增（见 StatsCacheService.bumpAnswerCount）；
        //   · 单题分布/文本：直接失效，下次访问回源重算（多选题一选项一行，
        //     分布会以难以穷举的方式变化，增量维护的风险高于重算成本）。
        //   ★ 与通知同一个理由放进 afterCommit：事务回滚时缓存不能先动，
        //     否则会出现"库里没有这份答卷、答卷数却多 1"的脏缓存，且一直错到 TTL 到期。
        afterCommitCacheRefresh(surveyId, surveyQuestions);

        // ★ 给问卷创建者发通知
        //   ★ 为什么不能在事务内直接调 @Async 的 send()：
        //     异步线程可能在**主事务提交之前**就把通知插进去了。一旦主事务随后回滚，
        //     就会出现"答卷没落库、通知却已经发出"的不一致（作者点进去看不到任何数据）；
        //     反过来，异步线程也读不到本事务尚未提交的数据，存在可见性竞态。
        //     ⇒ 注册成 afterCommit 回调：**提交成功之后**才发通知。
        if (survey.getUserId() != null && survey.getUserId() != 0) {
            String title = "收到新答卷";
            String content = "你的问卷《" + survey.getTitle() + "》收到了一份新的答卷";
            String link = "/my/surveys/" + surveyId + "/statistics";
            notifyAfterCommit(survey.getUserId(), title, content, "answer", link);
        }

        // 返回给答题页的分数（普通问卷 graded=false，前端照旧显示感谢语）
        // ★ 逐题结果只在作者开启"提交后显示答案"时带出去（否则成绩卡只给分数，不泄题）
        boolean showAnswers = survey.getShowAnswerAfterSubmit() != null
                && survey.getShowAnswerAfterSubmit() == 1;
        return SubmitResult.of("提交成功", grading, survey.getPassScore(), showAnswers);
    }

    /**
     * 在当前事务**提交之后**再发通知（调用方没有事务时直接发）。
     *
     * ★ 为什么要先判 isSynchronizationActive()：submit() 虽然标了 @Transactional，
     *   但本类也可能在没有事务的上下文里被调用（例如单元测试直接 new 出来调），
     *   那种情况下 registerSynchronization 会抛 IllegalStateException。
     *
     * ★ 为什么不是"把通知挪到 Controller 去发"：那会把"提交答卷要通知作者"这条业务规则
     *   泄露到接口层 —— 以后任何新的提交入口（如匿名提交）都得记得再调一次，早晚漏。
     *   用 afterCommit 回调既保住了事务语义，又让规则留在 Service 里。
     */
    private void notifyAfterCommit(Integer userId, String title, String content, String type, String link) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notificationService.send(userId, title, content, type, link);
                }
            });
        } else {
            notificationService.send(userId, title, content, type, link);
        }
    }

    /**
     * 事务提交后刷新统计缓存：答卷数自增 + 该问卷各题的分布/文本失效。
     *
     * ★ 为什么必须在 afterCommit：submit() 是 @Transactional，如果在事务里动缓存，
     *   一旦事务回滚（明细插入失败、撞上 uk_survey_user 等），就会出现"缓存说多了一份答卷、
     *   库里其实没有"的不一致 —— 而缓存不会自己纠正，会一直错到 TTL 到期（默认 10 分钟）。
     *   ★ 注意方向差异：**失效（DEL）放在事务里也安全**（幂等 + 下次回源正确），
     *     但**自增（INCR）不行**（回滚无法撤销），所以两者统一放到提交之后。
     * ★ 与 notifyAfterCommit 同理：本类也可能在没有事务的上下文里被调用
     *   （单元测试直接 new 出来调），所以先判 isSynchronizationActive()。
     */
    private void afterCommitCacheRefresh(Integer surveyId, List<Question> surveyQuestions) {
        List<Integer> questionIds = surveyQuestions.stream()
                .map(Question::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        Runnable refresh = () -> {
            statsCacheService.bumpAnswerCount(surveyId);
            statsCacheService.evictQuestions(questionIds);
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    refresh.run();
                }
            });
        } else {
            refresh.run();
        }
    }

    /**
     * 新题型的取值校验（评分 / 日期 / NPS）。
     * ★ 只做"值域"校验，不校验选项归属：选项归属由 AnswerQualityService 在提交后打
     *   OPTION_MISMATCH 标记（单选/多选历史口径一致），这里不引入第二套判定。
     * ★ NPS 的 content 会被**规范化**成不带前导零的十进制（"07" → "7"）：
     *   不规范化的话，同一个 7 分会以 "7" 和 "07" 两行出现在分布图里。
     */
    private void validateContentByType(Question q, AnswerDetail detail) {
        if (q == null || detail == null || detail.getContent() == null) return;
        String content = detail.getContent();
        if (QuestionType.isRating(q.getType())) {
            if (!RATING_VALUES.contains(content)) {
                throw new BizException("评分题的答案必须是 1~5 分");
            }
        } else if (QuestionType.isDate(q.getType())) {
            if (!DATE_PATTERN.matcher(content).matches()) {
                throw new BizException("日期题的答案格式必须是 yyyy-MM-dd");
            }
            try {
                java.time.LocalDate.parse(content);
            } catch (java.time.format.DateTimeParseException e) {
                throw new BizException("日期题的答案不是合法日期");
            }
        } else if (QuestionType.isNps(q.getType())) {
            int score;
            try {
                score = Integer.parseInt(content.trim());
            } catch (NumberFormatException e) {
                throw new BizException("NPS 题的答案必须是 0~10 的整数");
            }
            if (score < NPS_MIN || score > NPS_MAX) {
                throw new BizException("NPS 题的答案必须在 0~10 之间");
            }
            detail.setContent(String.valueOf(score));
        }
    }

    /**
     * 矩阵题的结构化校验：每一行必须属于本题、每个答案必须是一个合法列、同一行不能答两次。
     *
     * ★ 用**标题原文**匹配（不是下标）：答卷里存的就是标题原文，
     *   作者改标题只会影响之后的答卷（与选项文本的既有口径一致）。
     * ★ 为什么允许"只答一部分行"：必填校验一直在前端（全项目口径），
     *   后端只保证"答了的内容是合法的"，不替前端决定 required 的语义。
     */
    private void validateMatrixAnswer(Question q, List<AnswerDetail> answers) {
        List<String> rows = MatrixConfig.parse(q.getMatrixRows(), "矩阵行标题");
        List<String> cols = MatrixConfig.parse(q.getMatrixCols(), "矩阵列标题");
        if (rows.isEmpty() || cols.isEmpty()) {
            throw new BizException("该矩阵题的行列配置不完整，暂时无法提交");
        }
        Set<String> rowSet = new HashSet<>(rows);
        Set<String> colSet = new HashSet<>(cols);
        Set<String> answeredRows = new HashSet<>();
        for (AnswerDetail d : answers) {
            String rowKey = d.getRowKey() == null ? "" : d.getRowKey().trim();
            if (!rowSet.contains(rowKey)) {
                throw new BizException("矩阵题的答案行不属于这道题");
            }
            if (d.getContent() == null || !colSet.contains(d.getContent())) {
                throw new BizException("矩阵题的答案列不属于这道题");
            }
            d.setRowKey(rowKey);
            if (!answeredRows.add(rowKey)) {
                throw new BizException("矩阵题的同一行出现了多条答案");
            }
        }
    }

    /**
     * 排序题的结构化校验：名次必须是 1..N 且不重复，答案必须都是本题选项，且**全部选项都要排**。
     *
     * ★ 为什么要求"排满全部选项"：平均名次的分母必须一致。
     *   若允许只排前 3 名，A 的"平均 1.2 名"与 B 的"平均 1.0 名"就不可比
     *   （前者可能来自"只排 3 个"的答卷），统计结论会失真。
     *   前端排序控件本来就是把全部选项拖成 1..N，不存在"少排"的正常路径。
     */
    private void validateRankingAnswer(Question q, List<AnswerDetail> answers, List<Option> options) {
        if (options.size() < 2) {
            throw new BizException("该排序题的选项不足 2 个，暂时无法提交");
        }
        Set<String> optionTexts = options.stream()
                .map(Option::getContent)
                .collect(Collectors.toSet());
        Set<Integer> ranks = new HashSet<>();
        Set<String> contents = new HashSet<>();
        for (AnswerDetail d : answers) {
            if (d.getRankNo() == null) {
                throw new BizException("排序题的答案缺少名次");
            }
            if (d.getRankNo() < 1 || d.getRankNo() > options.size()) {
                throw new BizException("排序题的名次必须在 1~" + options.size() + " 之间");
            }
            if (!ranks.add(d.getRankNo())) {
                throw new BizException("排序题的名次不能重复");
            }
            if (d.getContent() == null || !optionTexts.contains(d.getContent())) {
                throw new BizException("排序题的答案不在选项里");
            }
            if (!contents.add(d.getContent())) {
                throw new BizException("排序题的同一个选项出现了两条答案");
            }
        }
        if (ranks.size() != options.size()) {
            throw new BizException("排序题需要给全部 " + options.size() + " 个选项排名");
        }
    }

    /**
     * 质控判定 + 回写（提交事务内调用）。
     *
     * ★ 只取**本次涉及的选择题**的选项，一次批量查（`findByQuestionIds`）：
     *   提交是热路径，逐题查选项就是 N 次往返。
     * ★ 规则全部在 {@link AnswerQualityService} 里，本方法只负责"喂数据 + 写结果"。
     */
    private void evaluateQuality(Integer answerId, Integer surveyId, List<AnswerDetail> details,
                                 List<Question> surveyQuestions, Integer durationMs) {
        List<Integer> optionQuestionIds = surveyQuestions.stream()
                .filter(q -> QuestionType.hasOptions(q.getType()))
                .map(Question::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        Map<Integer, List<com.survey.entity.Option>> optionsByQuestion = new HashMap<>();
        if (!optionQuestionIds.isEmpty()) {
            for (com.survey.entity.Option option : optionMapper.findByQuestionIds(optionQuestionIds)) {
                optionsByQuestion.computeIfAbsent(option.getQuestionId(), k -> new java.util.ArrayList<>())
                        .add(option);
            }
        }
        AnswerQualityService.QualityVerdict verdict =
                answerQualityService.evaluate(details, surveyQuestions, optionsByQuestion, durationMs);
        String reasons = verdict.suspicious() ? verdict.joined() : null;
        if (reasons != null && reasons.length() > com.survey.common.AnswerQuality.MAX_REASONS_LENGTH) {
            // 防御：正常最多 4 个码（约 60 字符），不会超；超了宁可截断也不能让提交 500
            reasons = reasons.substring(0, com.survey.common.AnswerQuality.MAX_REASONS_LENGTH);
        }
        answerMapper.updateQuality(answerId, durationMs, verdict.suspicious() ? 1 : 0, reasons);
    }

    /** 某问卷的**明细行数**（导出前的内存预检）。★ 见 AnswerController 里的说明。 */
    public int countDetails(Integer surveyId) {
        return answerDetailMapper.countBySurveyId(surveyId);
    }

    /**
     * 某问卷的答卷份数（/api/answer/count/{surveyId}）。★ 只查单份问卷时才用它。
     *
     * ★ 先读缓存：这是统计页每次刷新都要打的接口，而 `COUNT(*)` 的结果只随"提交/删除答卷"变化。
     *   未命中时回源并写回缓存；Redis 不可用时 getAnswerCount 返回 null，本条自动退化为直查库。
     */
    public int countResponses(Integer surveyId) {
        Integer cached = statsCacheService.getAnswerCount(surveyId);
        if (cached != null) {
            return cached;
        }
        int count = answerMapper.countBySurveyId(surveyId);
        statsCacheService.putAnswerCount(surveyId, count);
        return count;
    }

    /**
     * 单题的选项分布：{content, count} 列表 —— 单选/多选题的饼图/柱图靠它。
     *
     * ★ 这是本项目最重的一条统计 SQL（`GROUP BY content` 扫明细表，2 万答卷下 P50 约 191ms），
     *   而结果只随答卷变化 ⇒ 缓存收益最大的一条。写入路径（submit）会主动删掉它。
     */
    public List<Map<String, Object>> countQuestion(Integer questionId) {
        return countQuestion(questionId, false);
    }

    /**
     * 单题选项分布。
     *
     * @param excludeInvalid true = **只统计有效答卷**（`quality_flag = 0`）——
     *                       统计页的"剔除可疑样本"开关走这条路。
     * ★ 两个口径在 Redis 里是**两个 key**（`qstats` / `qstats-valid`），绝不能共用：
     *   共用的话"切换开关"会读到上一个口径的缓存，图表会静默地算错。
     */
    public List<Map<String, Object>> countQuestion(Integer questionId, boolean excludeInvalid) {
        if (excludeInvalid) {
            List<Map<String, Object>> cached = statsCacheService.getValidQuestionStats(questionId);
            if (cached != null) {
                return cached;
            }
            List<Map<String, Object>> valid = answerDetailMapper.countValidByQuestionId(questionId);
            statsCacheService.putValidQuestionStats(questionId, valid);
            return valid;
        }
        List<Map<String, Object>> cached = statsCacheService.getQuestionStats(questionId);
        if (cached != null) {
            return cached;
        }
        List<Map<String, Object>> stats = answerDetailMapper.countByQuestionId(questionId);
        statsCacheService.putQuestionStats(questionId, stats);
        return stats;
    }

    /**
     * 单题统计（按题型分流）—— 控制器用这个版本，因为排序题不能走 `GROUP BY content` 的那条 SQL。
     *
     * ★ 排序题返回 `[{content, count, avgRank}]`：count = 被排进名次的次数，
     *   avgRank = 平均名次（越小越靠前）。
     * ★ 缓存 key 与其它题型共用同一对（`qstats` / `qstats-valid`）：
     *   缓存里存的是"这道题的统计结果"，形状由题型决定，不会被别的题型读到。
     */
    public List<Map<String, Object>> countQuestion(Question question, boolean excludeInvalid) {
        if (question == null || question.getId() == null) {
            throw new BizException("题目不存在");
        }
        if (!QuestionType.isRanking(question.getType())) {
            return countQuestion(question.getId(), excludeInvalid);
        }
        Integer questionId = question.getId();
        List<Map<String, Object>> cached = excludeInvalid
                ? statsCacheService.getValidQuestionStats(questionId)
                : statsCacheService.getQuestionStats(questionId);
        if (cached != null) {
            return cached;
        }
        List<Map<String, Object>> rows = excludeInvalid
                ? answerDetailMapper.countValidRankingByQuestionId(questionId)
                : answerDetailMapper.countRankingByQuestionId(questionId);
        List<Map<String, Object>> stats = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", row.get("content"));
            item.put("count", row.get("count"));
            item.put("avgRank", row.get("avg_rank"));
            stats.add(item);
        }
        if (excludeInvalid) {
            statsCacheService.putValidQuestionStats(questionId, stats);
        } else {
            statsCacheService.putQuestionStats(questionId, stats);
        }
        return stats;
    }

    /** 单题的所有填空题文本（前端做词云或列表展示）。★ 同样走缓存，失效策略与选项分布一致。 */
    public List<String> listTextAnswers(Integer questionId) {
        List<String> cached = statsCacheService.getQuestionTexts(questionId);
        if (cached != null) {
            return cached;
        }
        List<String> texts = answerDetailMapper.findTextAnswers(questionId);
        statsCacheService.putQuestionTexts(questionId, texts);
        return texts;
    }

    /**
     * 整份问卷的统计（一次取回）—— 统计页专用，替代"逐题请求"。
     *
     * ── 为什么需要它 ──────────────────────────────────────
     *   原实现是前端 `for + await` 逐题请求：N 道题 = N 次**串行**往返，
     *   而且每次都各自扫一遍明细表。10 题的问卷就是 10 个来回，延迟直接叠加成倍数。
     *   现在一次请求返回「标题 + 答卷数 + 题目 + 每题分布/文本」。
     *
     * ── 三层优化（缺一不可）────────────────────────────────
     *   ① **缓存优先**：逐题先读 Redis（与单题接口共用同一批 key），命中的直接带走；
     *   ② **批量回源**：所有未命中的题目合并成**一条 SQL**（选择题一条、填空题一条）——
     *      如果"每个未命中再单独查一次"，那没命中时又退化成 N 次 SQL，等于白改；
     *   ③ **回填缓存**：回源结果按题写回 Redis，下一次请求（包括单题接口）直接命中。
     *
     * ★ 权限校验不在这里：Controller 的 `requireSurveyOwnership` 已经校验过。
     * ★ `survey` 由调用方传入（它刚查过一次），这里不再重复查库。
     */
    public SurveyStatisticsDTO getSurveyStatistics(Survey survey) {
        return getSurveyStatistics(survey, false);
    }

    /**
     * 整份问卷的统计（可指定"只看有效答卷"）。
     *
     * @param excludeInvalid true = 剔除可疑答卷（`quality_flag = 1`），
     *                       统计页的"剔除可疑样本"开关走这条路。
     * ★ 两个口径在 Redis 里是**两套 key**（`qstats` / `qstats-valid`），与单题接口共用。
     */
    public SurveyStatisticsDTO getSurveyStatistics(Survey survey, boolean excludeInvalid) {
        Integer surveyId = survey.getId();
        // ★ 用 findBySurveyId（**不带 options**）：统计页不渲染选项文本，
        //   而 getQuestionsBySurveyId 会为每道选择题再查一次 t_option —— 那是另一种 N+1。
        List<Question> questions = questionMapper.findBySurveyId(surveyId);

        Map<Integer, List<Map<String, Object>>> chartByQuestion = new LinkedHashMap<>();
        Map<Integer, List<String>> textByQuestion = new LinkedHashMap<>();
        List<Integer> missingChart = new ArrayList<>();
        List<Integer> missingRanking = new ArrayList<>();
        List<Integer> missingText = new ArrayList<>();

        // ① 先读缓存（excludeInvalid 时走"有效样本"口径）
        for (Question q : questions) {
            // ★ 排序题单独一条回源 SQL（要 AVG(rank_no)），但缓存 key 与其它题型共用：
            //   缓存里存的是"这道题的统计结果"，形状由题型决定，不会被别的题型读到。
            if (QuestionType.isRanking(q.getType())) {
                List<Map<String, Object>> cached = excludeInvalid
                        ? statsCacheService.getValidQuestionStats(q.getId())
                        : statsCacheService.getQuestionStats(q.getId());
                if (cached != null) {
                    chartByQuestion.put(q.getId(), cached);
                } else {
                    missingRanking.add(q.getId());
                }
            // ★ 用 hasChart 而不是 hasOptions：评分题 / NPS / 矩阵没有普通选项，
            //   但它们的分布同样是"分组计数"，要跟选择题一样走 chartData。
            } else if (QuestionType.hasChart(q.getType())) {
                List<Map<String, Object>> cached = excludeInvalid
                        ? statsCacheService.getValidQuestionStats(q.getId())
                        : statsCacheService.getQuestionStats(q.getId());
                if (cached != null) {
                    chartByQuestion.put(q.getId(), cached);
                } else {
                    missingChart.add(q.getId());
                }
            } else {
                List<String> cached = statsCacheService.getQuestionTexts(q.getId());
                if (cached != null) {
                    textByQuestion.put(q.getId(), cached);
                } else {
                    missingText.add(q.getId());
                }
            }
        }

        // ② 批量回源：只有真的缺了才查，且一次查完所有缺的题
        if (!missingChart.isEmpty()) {
            Map<Integer, List<Map<String, Object>>> grouped = new HashMap<>();
            List<Map<String, Object>> rows = excludeInvalid
                    ? answerDetailMapper.countValidByQuestionIds(missingChart)
                    : answerDetailMapper.countByQuestionIds(missingChart);
            for (Map<String, Object> row : rows) {
                Integer qid = ((Number) row.get("qid")).intValue();
                Map<String, Object> item = new LinkedHashMap<>();
                // ★ 矩阵题：row_key 非空时带上是哪一行；其它题型 row_key 恒为 NULL，输出与原来一致。
                Object rowKey = row.get("row_key");
                if (rowKey != null) {
                    item.put("row", rowKey);
                }
                item.put("content", row.get("content"));
                item.put("count", row.get("cnt"));
                grouped.computeIfAbsent(qid, k -> new ArrayList<>()).add(item);
            }
            for (Integer qid : missingChart) {
                // ★ 一道题可能一份答卷都没有：GROUP BY 不会给它出行，必须补空列表，
                //   否则前端拿到 null 而不是"暂无回答数据"。
                List<Map<String, Object>> list = grouped.getOrDefault(qid, List.of());
                chartByQuestion.put(qid, list);
                if (excludeInvalid) {
                    statsCacheService.putValidQuestionStats(qid, list);
                } else {
                    statsCacheService.putQuestionStats(qid, list);
                }
            }
        }

        // ②' 排序题：一次取回多题的 {content, count, avgRank}
        if (!missingRanking.isEmpty()) {
            Map<Integer, List<Map<String, Object>>> grouped = new HashMap<>();
            List<Map<String, Object>> rows = excludeInvalid
                    ? answerDetailMapper.countValidRankingByQuestionIds(missingRanking)
                    : answerDetailMapper.countRankingByQuestionIds(missingRanking);
            for (Map<String, Object> row : rows) {
                Integer qid = ((Number) row.get("qid")).intValue();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("content", row.get("content"));
                item.put("count", row.get("cnt"));
                item.put("avgRank", row.get("avg_rank"));
                grouped.computeIfAbsent(qid, k -> new ArrayList<>()).add(item);
            }
            for (Integer qid : missingRanking) {
                List<Map<String, Object>> list = grouped.getOrDefault(qid, List.of());
                chartByQuestion.put(qid, list);
                if (excludeInvalid) {
                    statsCacheService.putValidQuestionStats(qid, list);
                } else {
                    statsCacheService.putQuestionStats(qid, list);
                }
            }
        }

        if (!missingText.isEmpty()) {
            Map<Integer, List<String>> grouped = new HashMap<>();
            for (Map<String, Object> row : answerDetailMapper.findTextAnswersByQuestionIds(
                    missingText, MAX_TEXT_ANSWERS_PER_QUESTION)) {
                Integer qid = ((Number) row.get("qid")).intValue();
                grouped.computeIfAbsent(qid, k -> new ArrayList<>()).add((String) row.get("content"));
            }
            for (Integer qid : missingText) {
                List<String> list = grouped.getOrDefault(qid, List.of());
                textByQuestion.put(qid, list);
                statsCacheService.putQuestionTexts(qid, list);
            }
        }

        // ③ 按题目原顺序组装（前端的渲染顺序完全依赖它）
        List<SurveyStatisticsDTO.QuestionStats> items = new ArrayList<>(questions.size());
        for (Question q : questions) {
            if (QuestionType.hasChart(q.getType())) {
                items.add(new SurveyStatisticsDTO.QuestionStats(q, chartByQuestion.get(q.getId()), null));
            } else {
                items.add(new SurveyStatisticsDTO.QuestionStats(q, null, textByQuestion.get(q.getId())));
            }
        }

        // ④ 判分摘要（只有考试问卷才查）：一条聚合 SQL，走 (survey_id, submit_time) 的等值前缀。
        //   ★ 与图表同口径：excludeInvalid 时只统计 quality_flag = 0 的答卷。
        //   ★ 不缓存：一条索引聚合的代价可忽略；要缓存必须换 key 版本（见《待解决问题》C6）。
        SurveyStatisticsDTO.ScoreSummary scoreSummary = null;
        if (survey.getGradeEnabled() != null && survey.getGradeEnabled() == 1) {
            Map<String, Object> row = answerMapper.selectScoreSummary(surveyId, excludeInvalid);
            long gradedCount = row == null || row.get("cnt") == null
                    ? 0L : ((Number) row.get("cnt")).longValue();
            if (gradedCount > 0) {
                Double avg = row.get("avg_score") == null
                        ? null : round1(((Number) row.get("avg_score")).doubleValue());
                Double passRate = null;
                if (survey.getPassScore() != null && row.get("pass_cnt") != null) {
                    long passCount = ((Number) row.get("pass_cnt")).longValue();
                    passRate = round1(passCount * 100.0 / gradedCount);
                }
                scoreSummary = new SurveyStatisticsDTO.ScoreSummary((int) gradedCount, avg, passRate);
            }
        }
        return new SurveyStatisticsDTO(surveyId, survey.getTitle(), countResponses(surveyId),
                items, scoreSummary);
    }

    /** 保留一位小数（平均分 / 及格率都用它，避免前端出现 76.66666666）。 */
    private static Double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * 登录用户对某问卷的**最近一次**答卷（"我的成绩"用）。
     * ★ 匿名答卷（user_id = NULL）查不到 —— 匿名考生只在提交那一刻能看到分数。
     */
    public Answer getLatestAnswer(Integer surveyId, Integer userId) {
        if (surveyId == null || userId == null) return null;
        return answerMapper.findLatestBySurveyAndUser(surveyId, userId);
    }

    /**
     * 「我答过的问卷」分页列表（GET /api/answer/my）。
     *
     * ★ 身份只取当前登录用户：匿名答卷没有 user_id，天然查不到（产品口径）。
     * ★ total=0 时**不查列表**：省一次无意义的 LIMIT/OFFSET 查询。
     * ★ 包含已删除 / 已结束的问卷：历史记录不该因为作者删了问卷就消失，
     *   能不能打开由前端按 deleted/status 判断。
     * ★ 页码/每页条数一律过 {@link PageParams}：深翻页与 int 溢出都挡在这里。
     */
    public PageResult<MyAnswerItem> myAnswers(Integer userId, int pageNum, int pageSize) {
        if (userId == null) {
            throw new BizException("请先登录后再查看答题记录");
        }
        int num = PageParams.pageNum(pageNum);
        int size = PageParams.pageSize(pageSize);
        int total = answerMapper.countByUser(userId);
        List<MyAnswerItem> list = total == 0
                ? List.of()
                : answerMapper.pageByUser(userId, PageParams.offset(num, size), size);
        return new PageResult<>(total, list, num, size);
    }

    /** 某问卷的**全部**答卷明细（统计页做整体分析用，注意数据量）。 */
    public List<AnswerDetail> listDetailsBySurveyId(Integer surveyId) {
        return answerDetailMapper.findBySurveyId(surveyId);
    }

    /**
     * 某问卷的答卷列表（**分页**，提交时间倒序；统计页的"所有答卷"对话框）。
     *
     * ── 为什么必须分页 ────────────────────────────────────
     *   这条接口原来一次返回整份问卷的全部答卷：两万份答卷就是两万个对象一次性
     *   读进堆、再序列化成 JSON（实测 P50 239ms，且随答卷数线性增长）。
     *   分页后每页最多 {@link PageParams#MAX_PAGE_SIZE} 条，内存与响应时间都可预期。
     *   ★ **导出不走这里**：Excel 导出要的是"整份问卷"，仍由
     *     {@code AnswerDetailMapper.findBySurveyId} + 两道预检（份数 / 明细行数）承担，
     *     所以这里的分页不会让导出静默截断。
     *
     * ★ total 用**直查**（{@code countBySurveyId}）而不是带缓存的口径：
     *   分页器的总数必须与当前页数据同源，否则缓存晚 60 秒会让"共 N 份"与最后一页对不上。
     */
    public PageResult<Answer> listAnswersPage(Integer surveyId, int pageNum, int pageSize) {
        int num = PageParams.pageNum(pageNum);
        int size = PageParams.pageSize(pageSize);
        int total = answerMapper.countBySurveyId(surveyId);
        // ★ 偏移量统一走 PageParams.offset：它内部用 long 算，避免 (pageNum-1)*pageSize 溢出成负数
        long offset = PageParams.offset(num, size);
        List<Answer> rows = total == 0
                ? List.of()
                : answerMapper.findPageBySurveyId(surveyId, offset, size);
        return new PageResult<>(total, rows, num, size);
    }

    /** 某份答卷的全部明细（点开单条答卷看详情）。 */
    public List<AnswerDetail> listDetailsByAnswerId(Integer answerId) {
        return answerDetailMapper.findByAnswerId(answerId);
    }

    /**
     * 按答卷 id 取答卷（归属校验用：answerId -> surveyId）
     */
    public Answer getAnswerById(Integer answerId) {
        return answerMapper.findById(answerId);
    }
}