package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.PageParams;
import com.survey.common.PageResult;
import com.survey.common.QuestionType;
import com.survey.common.SurveyLifecycle;
import com.survey.common.SurveyStatus;
import com.survey.common.TextLimits;
import com.survey.entity.Answer;
import com.survey.entity.AnswerDetail;
import com.survey.entity.Question;
import com.survey.dto.SurveyStatisticsDTO;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
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
    public void submit(Integer surveyId, Integer userId, List<AnswerDetail> details) {
        doSubmit(surveyId, userId, details, null);
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
    public void submit(Integer surveyId, Integer userId, List<AnswerDetail> details, Integer durationMs) {
        doSubmit(surveyId, userId, details, durationMs);
    }

    private void doSubmit(Integer surveyId, Integer userId, List<AnswerDetail> details, Integer durationMs) {
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
        // ★★ 多选题的"一选项一行"：这类题的 questionId **允许**在一次提交里出现多行。
        //   为什么（务必看完再改）：
        //     · 前端答题页对多选的每个勾选项各生成一条明细（SurveyFillByCode.vue），
        //       演示数据也是这个形态（DemoDataService），而库里**历史答卷本来就是多行**
        //       （如 answer_id=1 + question_id=8 有 3 行）—— 三处口径一致，都是"一选项一行"。
        //     · 统计页按 `GROUP BY content` 数选项分布（AnswerDetailMapper.countByQuestionId），
        //       这个口径**只认一选项一行**：若把多选拼成一行字符串，饼图会按"整串组合"分组而失真。
        //     · 所以下面"同题只能一行"的守卫**必须**放过多选题，否则
        //       **任何多选题勾选 ≥2 项提交必然 400**（「同一道题在一次提交里出现了多次」）。
        //   ★ 所以这里按题型区别对待：多选题允许多行（但限行数，见 MAX_ROWS_PER_QUESTION），
        //     单选/填空仍严格"一题一行"。
        Set<Integer> multiChoiceQuestionIds = surveyQuestions.stream()
                .filter(q -> QuestionType.hasOptions(q.getType()) && !QuestionType.isSingle(q.getType()))
                .map(Question::getId)
                .collect(Collectors.toSet());
        // 同题出现的行数：用来在不误杀多选的前提下，仍然挡住"一次 POST 给某个选项灌 200 票"
        Map<Integer, Integer> rowsPerQuestion = new HashMap<>();
        for (AnswerDetail d : details) {
            if (d.getQuestionId() == null || !validQuestionIds.contains(d.getQuestionId())) {
                throw new BizException("答卷包含不属于该问卷的题目");
            }
            int rows = rowsPerQuestion.merge(d.getQuestionId(), 1, Integer::sum);
            boolean multiChoice = multiChoiceQuestionIds.contains(d.getQuestionId());
            if (!multiChoice && rows > 1) {
                // 单选/填空：一句话只能有一个答案，重复出现即视为灌票
                throw new BizException("同一道题在一次提交里出现了多次");
            }
            if (multiChoice && rows > MAX_ROWS_PER_QUESTION) {
                // 多选题：行数上限与"单题最多多少个选项"对齐（选项是答案的合法取值集合）
                throw new BizException("同一道多选题的作答选项过多（最多 " + MAX_ROWS_PER_QUESTION + " 个）");
            }
            if (d.getContent() != null && d.getContent().length() > MAX_CONTENT_LENGTH) {
                throw new BizException("单题回答不能超过 " + MAX_CONTENT_LENGTH + " 个字符");
            }
            // ★ 新题型的取值校验：评分 / 日期。不校验的话，客户端可以往评分题里塞任意字符串，
            //   统计图（按 content 分组）就会出现 "abc"、"6" 这种分数。
            validateContentByType(questionById.get(d.getQuestionId()), d.getContent());
        }

        Answer answer = new Answer();
        answer.setSurveyId(surveyId);
        answer.setUserId(userId);
        try {
            answerMapper.insert(answer);
        } catch (DuplicateKeyException e) {
            // ★ uk_survey_user（survey_id, user_id）是防重的**最后一道防线**：
            //   上面那次 countBySurveyAndUser 是 check-then-act，两个并发请求可能都通过检查；
            //   这时唯一索引会拒掉后到的那条 —— 翻译成与上面一致的 400，而不是让它变成 500。
            //   （匿名答卷 user_id 为 NULL，唯一索引不约束 NULL，所以不受这道影响。）
            throw new BizException("你已经提交过这份问卷，不能重复提交");
        }

        for (AnswerDetail d : details) {
            d.setAnswerId(answer.getId());
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
     * 新题型的取值校验（评分 / 日期）。
     * ★ 只做"值域"校验，不校验选项归属：选项归属由 AnswerQualityService 在提交后打
     *   OPTION_MISMATCH 标记（单选/多选历史口径一致），这里不引入第二套判定。
     */
    private void validateContentByType(Question q, String content) {
        if (q == null || content == null) return;
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
        List<Integer> missingText = new ArrayList<>();

        // ① 先读缓存（excludeInvalid 时走"有效样本"口径）
        for (Question q : questions) {
            // ★ 用 hasChart 而不是 hasOptions：评分题没有选项，但 1~5 分的分布同样是"分组计数"，
            //   要跟选择题一样走 chartData，不能落到"填空原文列表"里。
            if (QuestionType.hasChart(q.getType())) {
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
        return new SurveyStatisticsDTO(surveyId, survey.getTitle(), countResponses(surveyId), items);
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