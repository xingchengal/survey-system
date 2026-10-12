package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.QuestionType;
import com.survey.common.SurveyStatus;
import com.survey.common.TextLimits;
import com.survey.entity.Answer;
import com.survey.entity.AnswerDetail;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
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

        // ★ 契约校验（见类注释）：
        //   ① questionId 必须属于本问卷；② 内容长度上限；③ 同题去重（★ 见下，多选题例外）。
        //   ★ 合法性用"一次批量查询"得到，不逐题查库 —— 答卷题量有限，也不会构成 N+1。
        List<Question> surveyQuestions = questionMapper.findBySurveyId(surveyId);
        Set<Integer> validQuestionIds = surveyQuestions.stream()
                .map(Question::getId)
                .collect(Collectors.toSet());
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

    /** 某问卷的**明细行数**（导出前的内存预检）。★ 见 AnswerController 里的说明。 */
    public int countDetails(Integer surveyId) {
        return answerDetailMapper.countBySurveyId(surveyId);
    }

    /** 某问卷的答卷份数（/api/answer/count/{surveyId}）。★ 只查单份问卷时才用它。 */
    public int countResponses(Integer surveyId) {
        return answerMapper.countBySurveyId(surveyId);
    }

    /** 单题的选项分布：{content, count} 列表 —— 单选/多选题的饼图/柱图靠它。 */
    public List<Map<String, Object>> countQuestion(Integer questionId) {
        return answerDetailMapper.countByQuestionId(questionId);
    }

    /** 单题的所有填空题文本（前端做词云或列表展示）。 */
    public List<String> listTextAnswers(Integer questionId) {
        return answerDetailMapper.findTextAnswers(questionId);
    }

    /** 某问卷的**全部**答卷明细（统计页做整体分析用，注意数据量）。 */
    public List<AnswerDetail> listDetailsBySurveyId(Integer surveyId) {
        return answerDetailMapper.findBySurveyId(surveyId);
    }

    /** 某问卷的答卷列表（答卷时间倒序，统计页的"答卷记录"表格）。 */
    public List<Answer> listAnswersBySurveyId(Integer surveyId) {
        return answerMapper.findBySurveyId(surveyId);
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