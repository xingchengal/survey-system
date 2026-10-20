package com.survey.service;

import com.survey.common.AnswerDisplay;
import com.survey.common.AttemptShuffle;
import com.survey.common.BizException;
import com.survey.common.DateRanges;
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
import com.survey.dto.MyAnswerDetailDTO;
import com.survey.dto.MyAnswerItem;
import com.survey.dto.OwnerParticipantItem;
import com.survey.dto.ParticipantItem;
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
 * 答卷业务：提交（含通知/缓存/质控）、按问卷或题目查询答卷与统计。
 * 提交的唯一落库入口是 doSubmit：收答卷的守卫全在这里，新增入口也绕不过。
 * ★ 登录态按 survey_id + user_id 查重；匿名有意不查（按 IP 会误伤 NAT 后的同网用户）。
 * ★ 缓存失效与通知必须在 afterCommit：事务回滚后发通知/改缓存会留下不一致。
 * 质控只打标不删除；判分是提交时快照，事后改分值不影响旧成绩。
 */
@Service
public class AnswerService {

    /** 单题回答长度上限 = t_answer_detail.content 列宽 varchar(1000)；放宽前先 ALTER 列宽。 */
    private static final int MAX_CONTENT_LENGTH = TextLimits.ANSWER_CONTENT;

    /** 一次提交最多 200 条明细：构造请求可以塞几十万条，长度校验管不住条数。 */
    private static final int MAX_DETAILS_PER_SUBMIT = 200;

    /** 多选题一选项一行，单题最多 50 行（与选项上限对齐），挡住一次 POST 给某选项灌 200 票。 */
    private static final int MAX_ROWS_PER_QUESTION = 50;

    /** 每道填空题最多返回 2000 条，与单题接口的 LIMIT 2000 对齐。 */
    private static final int MAX_TEXT_ANSWERS_PER_QUESTION = 2000;

    /** 评分题合法取值：1~5 分。 */
    private static final Set<String> RATING_VALUES = Set.of("1", "2", "3", "4", "5");

    /** NPS 分数区间：0~10。 */
    private static final int NPS_MIN = 0;
    private static final int NPS_MAX = 10;

    /** 限时宽限 10 秒：倒计时到 0 后还有一两次网络往返。 */
    private static final long TIME_LIMIT_GRACE_MS = 10_000L;

    /** 公开报名名单最多展示多少位（接龙通常几十人，再多页面也没人看）。 */
    private static final int MAX_PARTICIPANTS = 50;

    /** 作者端名单一次最多返回多少条：作者要看全量，但接口必须有上限。 */
    private static final int MAX_OWNER_PARTICIPANTS = 500;

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

    /** IP 限制：记录哪个 IP 提交过某问卷，唯一键兜底并发。 */
    @Autowired
    private SurveyIpMapper surveyIpMapper;

    /**
     * 提交答卷：登录用户传 userId；匿名传 null（凭分享码答题）。
     * 收答卷的守卫全在 doSubmit（唯一落库入口）；密码已由 Controller 入口校验。
     *
     * @param surveyId 问卷 id；userId 匿名为 null；details 每题一行
     */
    @Transactional
    public SubmitResult submit(Integer surveyId, Integer userId, List<AnswerDetail> details) {
        return doSubmit(surveyId, userId, details, null, null, null);
    }

    /**
     * 提交答卷（带答题耗时）：重载各自标 @Transactional 且都调 doSubmit，互不调用。
     *
     * @param durationMs 客户端上报的毫秒数，可为 null；可伪造，只用于质控，不作安全边界
     */
    @Transactional
    public SubmitResult submit(Integer surveyId, Integer userId, List<AnswerDetail> details, Integer durationMs) {
        return doSubmit(surveyId, userId, details, durationMs, null, null);
    }

    /** 兼容旧调用（不带作答令牌）：问卷未开随机抽题时与原行为一致。 */
    @Transactional
    public SubmitResult submit(Integer surveyId, Integer userId, List<AnswerDetail> details,
                       Integer durationMs, String clientIp) {
        return doSubmit(surveyId, userId, details, durationMs, clientIp, null);
    }

    /**
     * 提交答卷（带耗时 + 客户端 IP）：两个 HTTP 入口用这个重载。
     *
     * @param clientIp     连接 IP（不看 X-Forwarded-For）；问卷开 IP 限制时必须非空
     * @param attemptToken 填写页的作答令牌；问卷开了随机抽题时必须带，用于重算抽到的题与总分
     */
    @Transactional
    public SubmitResult submit(Integer surveyId, Integer userId, List<AnswerDetail> details,
                       Integer durationMs, String clientIp, String attemptToken) {
        return doSubmit(surveyId, userId, details, durationMs, clientIp, attemptToken);
    }

    private SubmitResult doSubmit(Integer surveyId, Integer userId, List<AnswerDetail> details,
                          Integer durationMs, String clientIp, String attemptToken) {
        if (details == null || details.isEmpty()) {
            throw new BizException("答卷内容不能为空");
        }
        if (details.size() > MAX_DETAILS_PER_SUBMIT) {
            throw new BizException("一次提交的作答条目过多（最多 " + MAX_DETAILS_PER_SUBMIT + " 条）");
        }

        // 收答卷守卫：空答卷、存在、回收站、已发布、截止、仅登录、重复、限收（max_responses）、下架。
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

        // 截止时间已过就拒收，不能等定时任务把 status 改成 2：两者之间有窗口，停机时窗口无限长。
        // 口径与 publish 一致：null = 不限，否则必须严格晚于当前时间。
        if (survey.getEndTime() != null
                && !survey.getEndTime().isAfter(java.time.LocalDateTime.now())) {
            throw new BizException("问卷已截止，无法提交答卷");
        }

        // 仅限登录必须服务端自己判：submit-share 的 userId 恒为 null，只有登录入口带 token。
        if (survey.getRequireLogin() != null && survey.getRequireLogin() == 1 && userId == null) {
            throw new BizException("该问卷仅限登录用户填写，请先登录后再提交");
        }

        // ★ 限时按前端上报的 duration_ms 校验（+10 秒宽限）：它可伪造，拦的是正常超时，不是防作弊。
        // durationMs 为 null 时不拦（老客户端/直接调接口），这是已知缺口。
        if (survey.getTimeLimitSeconds() != null && durationMs != null
                && durationMs > survey.getTimeLimitSeconds() * 1000L + TIME_LIMIT_GRACE_MS) {
            int limit = survey.getTimeLimitSeconds();
            String limitText = limit % 60 == 0 ? (limit / 60) + " 分钟" : limit + " 秒";
            throw new BizException("答题超时（限时 " + limitText + "），本次答卷未提交");
        }

        // 登录态按 survey_id + user_id 查重；匿名有意不查（NAT 后同网用户会被误伤，靠限流兜）。
        // check-then-act 不是原子的，并发双击靠 uk_survey_user 兜底（唯一索引不约束 NULL）。
        if (userId != null && answerMapper.countBySurveyAndUser(surveyId, userId) > 0) {
            throw new BizException("你已经提交过这份问卷，不能重复提交");
        }

        // 限量收集：达到 max_responses 直接拒收，不等定时任务改 status；并发可能多收 1~2 份。
        if (survey.getMaxResponses() != null && survey.getMaxResponses() > 0
                && answerMapper.countBySurveyId(surveyId) >= survey.getMaxResponses()) {
            throw new BizException("这份问卷已收满 " + survey.getMaxResponses() + " 份答卷，无法继续提交");
        }

        // 被举报下架（lifecycle=SUSPENDED）一律拒收：下架时 status 仍是 1，不能只看 status。
        if (SurveyLifecycle.SUSPENDED.code().equals(survey.getLifecycle())) {
            throw new BizException("该问卷已被下架，无法提交答卷");
        }

        // 契约校验：questionId 必须属于本问卷、内容长度、同题行数（多行题型例外）；一次批量查询。
        List<Question> surveyQuestions = questionMapper.findBySurveyId(surveyId);
        Set<Integer> validQuestionIds = surveyQuestions.stream()
                .map(Question::getId)
                .collect(Collectors.toSet());
        // 一次批量查询顺手建 id → Question，供题型校验用。
        Map<Integer, Question> questionById = surveyQuestions.stream()
                .filter(q -> q.getId() != null)
                .collect(Collectors.toMap(Question::getId, q -> q, (a, b) -> a));
        // 多选/矩阵/排序天然一题多行，统计按 GROUP BY content（矩阵再按 row_key）也只认一行一条。
        // 其它题型严格一题一行，重复出现即视为灌票。
        Set<Integer> multiRowQuestionIds = surveyQuestions.stream()
                .filter(q -> QuestionType.isMultiRow(q.getType()))
                .map(Question::getId)
                .collect(Collectors.toSet());
        // 同题行数：不误杀多行题型，同时挡住一次 POST 灌 200 票。
        Map<Integer, Integer> rowsPerQuestion = new HashMap<>();
        Map<Integer, List<AnswerDetail>> detailsByQuestion = new HashMap<>();
        for (AnswerDetail d : details) {
            if (d.getQuestionId() == null || !validQuestionIds.contains(d.getQuestionId())) {
                throw new BizException("答卷包含不属于该问卷的题目");
            }
            int rows = rowsPerQuestion.merge(d.getQuestionId(), 1, Integer::sum);
            boolean multiRow = multiRowQuestionIds.contains(d.getQuestionId());
            if (!multiRow && rows > 1) {
                // 单选/填空/评分/日期/NPS：一句话只能有一个答案。
                throw new BizException("同一道题在一次提交里出现了多次");
            }
            if (multiRow && rows > MAX_ROWS_PER_QUESTION) {
                // 多行题型也限行数，防止一次 POST 灌 200 票。
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
            // 评分/日期/NPS 的取值校验；不校验统计图会出现 "abc"、"6" 这类值。
            validateContentByType(questionById.get(d.getQuestionId()), d);
        }

        // 矩阵/排序还要结构化校验行与名次；不校验统计页会凭空多出行列。
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

        // IP 限制：记录与答卷同事务；唯一键 (survey_id, ip) 兜底并发；用连接 IP，不看 X-Forwarded-For。
        if (survey.getIpLimit() != null && survey.getIpLimit() == 1) {
            if (clientIp == null || clientIp.isBlank()) {
                throw new BizException("无法识别提交来源 IP，暂时无法提交（该问卷开启了 IP 限制）");
            }
            if (surveyIpMapper.insertIgnore(surveyId, clientIp) == 0) {
                throw new BizException("该问卷开启了 IP 限制：同一网络只能提交一次");
            }
        }

        // 抽题：填写页拿到的题集由 attemptToken 决定，提交时必须用同一 token 重算，
        // 否则判分总分按全题库算，会多算没抽到的题；同时挡住“塞一道没抽到的题刷分”。
        List<Question> effectiveQuestions = surveyQuestions;
        int perAttempt = survey.getQuestionsPerAttempt() == null ? 0 : survey.getQuestionsPerAttempt();
        boolean shuffleQuestions = survey.getShuffleQuestions() != null && survey.getShuffleQuestions() == 1;
        if (perAttempt > 0) {
            if (attemptToken == null || attemptToken.isBlank()) {
                throw new BizException("请刷新页面后再作答（缺少作答令牌）");
            }
            effectiveQuestions = AttemptShuffle.arrange(surveyId, surveyQuestions, perAttempt,
                    shuffleQuestions, attemptToken.trim());
            Set<Integer> drawnIds = effectiveQuestions.stream()
                    .map(Question::getId)
                    .collect(Collectors.toSet());
            for (AnswerDetail d : details) {
                if (!drawnIds.contains(d.getQuestionId())) {
                    throw new BizException("答卷包含本次未抽到的题目，请刷新页面后重试");
                }
            }
        }

        // 判分在写库前完成，与 t_answer 同一条 INSERT 落库；分数是提交时快照，事后改分值不影响已提交答卷。
        // 只查设了分值的题，不构成 N+1；普通问卷完全跳过。
        Grader.Result grading = null;
        if (survey.getGradeEnabled() != null && survey.getGradeEnabled() == 1) {
            List<Integer> gradableQuestionIds = effectiveQuestions.stream()
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
            // 总分只按本次抽到的题算（未抽到的不进分母）
            grading = Grader.grade(survey, effectiveQuestions, gradableOptions, details);
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
            // uk_survey_user 是防重最后防线：并发时唯一索引拒掉后到的一条，翻成同样的 400。
            throw new BizException("你已经提交过这份问卷，不能重复提交");
        }

        // 判分题的逐题对错随明细落库（多选同题所有行同值）；非计分题保持 NULL。
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

        // 质控只打标不删除；必须同事务，否则答卷存了、标记没写会悄悄少一份且不报错。
        evaluateQuality(answer.getId(), surveyId, details, surveyQuestions, durationMs);

        // 缓存：答卷数仅在缓存已存在时自增；单题分布/文本直接失效，下次回源重算。
        // 与通知同理放 afterCommit：回滚时缓存不能先动，否则会一直错到 TTL。
        afterCommitCacheRefresh(surveyId, surveyQuestions);

        // 通知注册成 afterCommit：事务内直接发，异步线程可能在提交前插入，回滚后作者点进去看不到数据。
        if (survey.getUserId() != null && survey.getUserId() != 0) {
            String title = "收到新答卷";
            String content = "你的问卷《" + survey.getTitle() + "》收到了一份新的答卷";
            String link = "/my/surveys/" + surveyId + "/statistics";
            notifyAfterCommit(survey.getUserId(), title, content, "answer", link);
        }

        // 逐题结果只在作者开启"提交后显示答案"时返回，否则成绩卡只给分数、不泄题。
        boolean showAnswers = survey.getShowAnswerAfterSubmit() != null
                && survey.getShowAnswerAfterSubmit() == 1;
        return SubmitResult.of("提交成功", grading, survey.getPassScore(), showAnswers);
    }

    /**
     * 在事务提交后再发通知；没有事务时直接发。
     * 必须现判：本类也可能在没有事务的上下文里被调用，registerSynchronization 会抛异常。
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
     * 提交后刷新统计缓存：答卷数自增 + 各题分布/文本失效。
     * 自增回滚无法撤销，所以统一放 afterCommit；失效本身幂等，但也放这里。
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
     * 评分 / 日期 / NPS 的值域校验；NPS 会规范化成不带前导零的十进制（"07" → "7"）。
     * 选项归属不在这里判，由 AnswerQualityService 提交后打标。
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
     * 矩阵校验：行必须属于本题、值必须是合法列、同一行只能答一次。
     * 用标题原文匹配（与答卷存储一致）；允许只答一部分行，必填在前端。
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

    /** 排序校验：名次 1..N 不重复、答案都在选项里，且必须排满全部选项（否则平均名次不可比）。 */
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
     * 质控判定 + 回写（同事务）：只批量取选择题选项，规则全在 AnswerQualityService。
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
            // 正常最多 4 个码，超了宁可截断也不能让提交 500。
            reasons = reasons.substring(0, com.survey.common.AnswerQuality.MAX_REASONS_LENGTH);
        }
        answerMapper.updateQuality(answerId, durationMs, verdict.suspicious() ? 1 : 0, reasons);
    }

    /** 某问卷的明细行数（导出前的内存预检）。 */
    public int countDetails(Integer surveyId) {
        return answerDetailMapper.countBySurveyId(surveyId);
    }

    /**
     * 某问卷答卷份数：先读缓存，未命中回源再写回；Redis 不可用时退化为直查库。
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
     * 单题选项分布（{content, count}）：最重的一条统计 SQL，结果只随答卷变化，提交路径会主动删缓存。
     */
    public List<Map<String, Object>> countQuestion(Integer questionId) {
        return countQuestion(questionId, false);
    }

    /**
     * 单题选项分布；excludeInvalid=true 只统计有效答卷（quality_flag=0）。
     * 两个口径在 Redis 里是两个 key，绝不能共用，否则切换开关会读到上一个口径。
     *
     * @param excludeInvalid true = 剔除可疑样本
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
     * 按题型分流的单题统计：排序题要 AVG(rank_no)，不能走 GROUP BY content。
     * 返回 count / avgRank（越小越靠前）；缓存 key 与其它题型共用同一对。
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

    /** 单题全部填空文本；同样走缓存，失效策略与选项分布一致。 */
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
     * 整份问卷统计（一次取回）：逐题读缓存 → 未命中的批量回源 → 回填缓存。
     * 批量回源是重点（选择题一条、填空题一条）：逐题回源会退化成 N 次 SQL。
     * 权限由 Controller 校验；survey 由调用方传入，这里不重复查库。
     */
    public SurveyStatisticsDTO getSurveyStatistics(Survey survey) {
        return getSurveyStatistics(survey, false);
    }

    /**
     * 整份问卷统计；excludeInvalid=true 只算有效答卷。
     * 两个口径在 Redis 里是两套 key，与单题接口共用。
     *
     * @param excludeInvalid true = 剔除可疑答卷
     */
    public SurveyStatisticsDTO getSurveyStatistics(Survey survey, boolean excludeInvalid) {
        Integer surveyId = survey.getId();
        // 用 findBySurveyId（不带 options）：统计页不渲染选项，逐题再查 t_option 是另一种 N+1。
        List<Question> questions = questionMapper.findBySurveyId(surveyId);

        Map<Integer, List<Map<String, Object>>> chartByQuestion = new LinkedHashMap<>();
        Map<Integer, List<String>> textByQuestion = new LinkedHashMap<>();
        List<Integer> missingChart = new ArrayList<>();
        List<Integer> missingRanking = new ArrayList<>();
        List<Integer> missingText = new ArrayList<>();

        // ① 先读缓存（excludeInvalid 时走"有效样本"口径）
        for (Question q : questions) {
            // 排序题单独一条回源 SQL（要 AVG(rank_no)），缓存 key 与其它题型共用。
            if (QuestionType.isRanking(q.getType())) {
                List<Map<String, Object>> cached = excludeInvalid
                        ? statsCacheService.getValidQuestionStats(q.getId())
                        : statsCacheService.getQuestionStats(q.getId());
                if (cached != null) {
                    chartByQuestion.put(q.getId(), cached);
                } else {
                    missingRanking.add(q.getId());
                }
            // 用 hasChart 而不是 hasOptions：评分/NPS/矩阵也要走 chartData。
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
                // 矩阵题 row_key 非空时带上行；其它题型为 NULL，输出不变。
                Object rowKey = row.get("row_key");
                if (rowKey != null) {
                    item.put("row", rowKey);
                }
                item.put("content", row.get("content"));
                item.put("count", row.get("cnt"));
                grouped.computeIfAbsent(qid, k -> new ArrayList<>()).add(item);
            }
            for (Integer qid : missingChart) {
                // 没有答卷的题 GROUP BY 不出行，必须补空列表，否则前端拿到 null。
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

        // ③ 按题目原顺序组装（前端渲染依赖顺序）。
        List<SurveyStatisticsDTO.QuestionStats> items = new ArrayList<>(questions.size());
        for (Question q : questions) {
            if (QuestionType.hasChart(q.getType())) {
                items.add(new SurveyStatisticsDTO.QuestionStats(q, chartByQuestion.get(q.getId()), null));
            } else {
                items.add(new SurveyStatisticsDTO.QuestionStats(q, null, textByQuestion.get(q.getId())));
            }
        }

        // ④ 判分摘要：一条聚合 SQL，与图表同口径（excludeInvalid 只算有效答卷）；不缓存。
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
                items, scoreSummary,
                survey.getCheckinEnabled() != null && survey.getCheckinEnabled() == 1);
    }

    /** 保留一位小数（平均分 / 及格率都用它，避免前端出现 76.66666666）。 */
    private static Double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 登录用户对某问卷的最近一次答卷；匿名（user_id=NULL）查不到。 */
    public Answer getLatestAnswer(Integer surveyId, Integer userId) {
        if (surveyId == null || userId == null) return null;
        return answerMapper.findLatestBySurveyAndUser(surveyId, userId);
    }

    /**
     * 我答过的问卷（分页）：身份只取当前登录用户；total=0 时不查列表。
     * 含已删除/已结束的问卷，能否打开由前端判断；页码/页大小一律过 PageParams。
     * 可选筛选：问卷标题关键词 + 提交时间范围（yyyy-MM-dd，闭区间），总数与列表同一口径。
     */
    public PageResult<MyAnswerItem> myAnswers(Integer userId, int pageNum, int pageSize,
                                              String keyword, String startDate, String endDate) {
        if (userId == null) {
            throw new BizException("请先登录后再查看答题记录");
        }
        int num = PageParams.pageNum(pageNum);
        int size = PageParams.pageSize(pageSize);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        java.time.LocalDateTime start = DateRanges.startOfDay(startDate);
        java.time.LocalDateTime end = DateRanges.endOfDay(endDate);
        int total = answerMapper.countByUser(userId, kw, start, end);
        List<MyAnswerItem> list = total == 0
                ? List.of()
                : answerMapper.pageByUser(userId, kw, start, end, PageParams.offset(num, size), size);
        return new PageResult<>(total, list, num, size);
    }

    /**
     * 我的成绩详情：权限按答卷归属校验；正确答案只在作者开启「提交后显示答案」时返回。
     * 存量答卷没有 is_correct 时返回 null，不硬判成"答错"。
     */
    public MyAnswerDetailDTO getMyAnswerDetail(Integer userId, Integer answerId) {
        if (userId == null) {
            throw new BizException("请先登录后再查看答卷详情");
        }
        Answer answer = answerMapper.findById(answerId);
        if (answer == null) {
            throw new BizException("答卷不存在");
        }
        if (answer.getUserId() == null || !answer.getUserId().equals(userId)) {
            throw new BizException("无权查看该答卷");
        }
        Survey survey = surveyMapper.findById(answer.getSurveyId());
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        List<Question> questions = questionMapper.findBySurveyId(answer.getSurveyId());
        List<AnswerDetail> details = answerDetailMapper.findByAnswerId(answerId);
        Map<Integer, List<AnswerDetail>> rowsByQuestion = new HashMap<>();
        for (AnswerDetail d : details) {
            rowsByQuestion.computeIfAbsent(d.getQuestionId(), k -> new ArrayList<>()).add(d);
        }

        boolean showAnswers = survey.getShowAnswerAfterSubmit() != null
                && survey.getShowAnswerAfterSubmit() == 1;
        Map<Integer, List<Option>> optionsByQuestion = new HashMap<>();
        if (showAnswers) {
            List<Integer> gradableIds = questions.stream()
                    .filter(q -> Grader.isGradable(q.getType()) && q.getId() != null)
                    .map(Question::getId)
                    .collect(Collectors.toList());
            if (!gradableIds.isEmpty()) {
                for (Option opt : optionMapper.findByQuestionIds(gradableIds)) {
                    optionsByQuestion.computeIfAbsent(opt.getQuestionId(), k -> new ArrayList<>()).add(opt);
                }
            }
        }

        List<MyAnswerDetailDTO.QuestionDetail> items = new ArrayList<>(questions.size());
        for (Question q : questions) {
            List<AnswerDetail> rows = rowsByQuestion.getOrDefault(q.getId(), List.of());
            String text = rows.isEmpty() ? "未作答" : AnswerDisplay.format(q, rows);
            Boolean correct = null;
            boolean scored = Grader.isGradable(q.getType())
                    && q.getScore() != null && q.getScore() > 0;
            if (scored) {
                Integer recorded = rows.stream().map(AnswerDetail::getIsCorrect)
                        .filter(java.util.Objects::nonNull).findFirst().orElse(null);
                correct = recorded == null ? null : recorded == 1;
            }
            List<String> correctAnswers = (correct != null && showAnswers)
                    ? AnswerDisplay.correctAnswers(optionsByQuestion.get(q.getId()))
                    : null;
            items.add(new MyAnswerDetailDTO.QuestionDetail(q.getId(), q.getTitle(), q.getType(),
                    text, correct, correctAnswers));
        }
        return new MyAnswerDetailDTO(answer.getId(), answer.getSurveyId(), survey.getTitle(),
                answer.getSubmitTime(), answer.getTotalScore(), answer.getEarnedScore(),
                survey.getPassScore(), answer.getIsPassed(), items);
    }

    /**
     * 签到 / 取消签到（作者或管理员）：只有启用签到核销的问卷才能改。
     *
     * @return true=已签到，false=已取消
     */
    public boolean setCheckedIn(Integer answerId, boolean checkedIn) {
        Answer answer = answerMapper.findById(answerId);
        if (answer == null) {
            throw new BizException("答卷不存在");
        }
        Survey survey = surveyMapper.findById(answer.getSurveyId());
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (survey.getCheckinEnabled() == null || survey.getCheckinEnabled() != 1) {
            throw new BizException("该问卷未启用签到核销");
        }
        answerMapper.updateCheckedIn(answerId, checkedIn ? 1 : 0);
        return checkedIn;
    }

    /**
     * 公开报名名单（前 50 位，按提交时间正序）。
     * 只有作者开启 show_participants 才返回；称呼取第一道单行文本题，一次批量查，避免 N+1。
     */
    public List<ParticipantItem> listParticipants(Survey survey) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (survey.getShowParticipants() == null || survey.getShowParticipants() != 1) {
            throw new BizException("作者未公开报名名单");
        }
        List<Answer> answers = answerMapper.findFirstBySurveyId(survey.getId(), MAX_PARTICIPANTS);
        if (answers.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> nameByAnswer = loadNameMap(survey.getId(), answers);
        List<ParticipantItem> list = new ArrayList<>(answers.size());
        int seq = 1;
        for (Answer a : answers) {
            String name = nameByAnswer.get(a.getId());
            list.add(new ParticipantItem(seq++,
                    name == null || name.isBlank() ? "匿名" : name.trim(),
                    a.getSubmitTime(),
                    a.getCheckedIn() != null && a.getCheckedIn() == 1 ? 1 : 0));
        }
        return list;
    }

    /**
     * 作者端报名 / 签到名单：不要求作者开启「公开报名名单」（那是给访客看的），
     * 但必须是问卷归属者（Controller 用 requireSurveyOwnership 校验）。最多 500 条。
     */
    public Map<String, Object> listParticipantsForOwner(Integer surveyId) {
        Survey survey = surveyMapper.findById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        Map<String, Object> summary = answerMapper.countParticipants(surveyId);
        int total = summary == null ? 0 : toInt(summary.get("total"));
        int checkedInCount = summary == null ? 0 : toInt(summary.get("checkedIn"));
        List<Answer> answers = answerMapper.findFirstBySurveyId(surveyId, MAX_OWNER_PARTICIPANTS);
        Map<Integer, String> nameByAnswer = loadNameMap(surveyId, answers);
        List<OwnerParticipantItem> list = new ArrayList<>(answers.size());
        int seq = 1;
        for (Answer a : answers) {
            String name = nameByAnswer.get(a.getId());
            list.add(new OwnerParticipantItem(seq++,
                    a.getId(),
                    name == null || name.isBlank() ? "匿名" : name.trim(),
                    a.getSubmitTime(),
                    a.getCheckedIn() != null && a.getCheckedIn() == 1 ? 1 : 0,
                    a.getCheckedInAt()));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("checkedInCount", checkedInCount);
        result.put("list", list);
        return result;
    }

    private int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    /** 报名名单的称呼映射：取第一道单行文本题（没有则多行文本），一次批量查避免 N+1。 */
    private Map<Integer, String> loadNameMap(Integer surveyId, List<Answer> answers) {
        Map<Integer, String> nameByAnswer = new HashMap<>();
        if (answers == null || answers.isEmpty()) {
            return nameByAnswer;
        }
        List<Question> questions = questionMapper.findBySurveyId(surveyId);
        Integer nameQuestionId = questions.stream()
                .filter(q -> QuestionType.isText(q.getType()) && q.getId() != null)
                .map(Question::getId).findFirst()
                .orElseGet(() -> questions.stream()
                        .filter(q -> QuestionType.isTextarea(q.getType()) && q.getId() != null)
                        .map(Question::getId).findFirst().orElse(null));
        if (nameQuestionId == null) {
            return nameByAnswer;
        }
        List<Integer> answerIds = answers.stream().map(Answer::getId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());
        if (answerIds.isEmpty()) {
            return nameByAnswer;
        }
        for (Map<String, Object> row : answerMapper.findContentsByAnswerIds(nameQuestionId, answerIds)) {
            Object aid = row.get("aid");
            Object content = row.get("content");
            if (aid instanceof Number n) {
                nameByAnswer.put(n.intValue(), content == null ? "" : content.toString());
            }
        }
        return nameByAnswer;
    }

    /** 某问卷的全部答卷明细（统计页整体分析用，注意数据量）。 */
    public List<AnswerDetail> listDetailsBySurveyId(Integer surveyId) {
        return answerDetailMapper.findBySurveyId(surveyId);
    }

    /**
     * 某问卷的答卷列表（分页，提交时间倒序）。
     * total 用直查而不是缓存口径：总数必须与当前页同源；导出不走这里，不会静默截断。
     */
    public PageResult<Answer> listAnswersPage(Integer surveyId, int pageNum, int pageSize) {
        int num = PageParams.pageNum(pageNum);
        int size = PageParams.pageSize(pageSize);
        int total = answerMapper.countBySurveyId(surveyId);
        // 偏移量走 PageParams.offset：内部用 long 算，避免乘法溢出成负数。
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

    /** 按答卷 id 取答卷（归属校验用：answerId → surveyId）。 */
    public Answer getAnswerById(Integer answerId) {
        return answerMapper.findById(answerId);
    }
}