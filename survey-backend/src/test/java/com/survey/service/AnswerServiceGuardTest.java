package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.QuestionType;
import com.survey.common.SurveyStatus;
import com.survey.entity.AnswerDetail;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 答卷提交守卫的单元测试（Mockito，不连库、不起 Spring 容器）。
 *
 * ── 为什么专门测这一组 ────────────────────────────────
 *   `AnswerService.submit` 是**唯一**的答卷落库入口，它前面挂着三道"能不能收答卷"的检查。
 *   这三道检查只要有一条漏判 deleted，**回收站里的已发布问卷就能继续收答卷**，
 *   所以它们必须收在 Service 这一层、由这组测试钉住：
 *   以后谁调整了守卫顺序、或新增了提交入口，这里会立刻变红。
 *
 * ── 关于 mock ─────────────────────────────────────────
 *   只 mock 掉 Mapper 与通知服务，**不 mock 被测对象本身**，
 *   所以断言的是真实的守卫逻辑（而不是"mock 返回了我想让它返回的东西"）。
 *   ★ 项目在 src/test/resources/mockito-extensions 里指定了 mock-maker-subclass
 *     （绕开沙箱的 Attach 限制），因此**不能 mock final 类** —— 本测试用到的类型都不是 final。
 */
@ExtendWith(MockitoExtension.class)
class AnswerServiceGuardTest {

    @Mock
    private AnswerMapper answerMapper;
    @Mock
    private AnswerDetailMapper answerDetailMapper;
    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private NotificationService notificationService;
    /**
     * ★ 提交成功后要刷新统计缓存（答卷数自增 + 该问卷各题分布失效），
     *   所以 AnswerService 多了一个依赖。这里给它一个 mock：
     *   本类断言的是**守卫逻辑**，缓存本身的行为由 StatsCacheServiceTest 覆盖；
     *   不注入的话该字段是 null，任何走到"提交成功"的用例都会 NPE。
     */
    @Mock
    private StatsCacheService statsCacheService;

    /**
     * ★ 提交成功后还要做"答卷质控判定"（基于刚插入的明细 + 选项），
     *   所以 AnswerService 又多了两个依赖。这里都给 mock：
     *   守卫逻辑本身不需要质控数据，判定规则由 AnswerQualityServiceTest 覆盖。
     *   ★ 不注入就是 null，任何走到"提交成功"的用例都会 NPE。
     */
    @Mock
    private OptionMapper optionMapper;
    @Mock
    private AnswerQualityService answerQualityService;

    @InjectMocks
    private AnswerService answerService;

    @org.junit.jupiter.api.BeforeEach
    void givenQualityRulesPass() {
        // ★ lenient：只有"提交成功"的用例才会走到质控判定，其余用例用不到这个 stub
        //   —— 严格模式下会报 UnnecessaryStubbing。
        org.mockito.Mockito.lenient()
                .when(answerQualityService.evaluate(any(), any(), any(), any()))
                .thenReturn(new AnswerQualityService.QualityVerdict(false, java.util.List.of()));
    }

    private static final Integer SURVEY_ID = 100;
    private static final Integer USER_ID = 7;

    private static Survey survey(Integer status, Integer deleted) {
        Survey s = new Survey();
        s.setId(SURVEY_ID);
        s.setUserId(1);
        s.setTitle("测试问卷");
        s.setStatus(status);
        s.setDeleted(deleted);
        return s;
    }

    private static List<AnswerDetail> details(int count) {
        List<AnswerDetail> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AnswerDetail d = new AnswerDetail();
            d.setQuestionId(i + 1);
            d.setContent("答案" + i);
            list.add(d);
        }
        return list;
    }

    private static Question question(int id) {
        Question q = new Question();
        q.setId(id);
        q.setType(QuestionType.SINGLE.code());   // 默认单选：一题一行
        return q;
    }

    /** 指定题型的题目（多选 = type 2，允许"一选项一行"）。 */
    private static Question question(int id, int type) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        return q;
    }

    // ==================== 一、能不能收答卷（三道守卫） ====================

    @Test
    @DisplayName("★ 回收站里的问卷不能收答卷")
    void submit_shouldRejectSurveyInRecycleBin() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.PUBLISHED.code(), 1));

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
        assertEquals("问卷已在回收站中，无法提交答卷", ex.getMessage());
    }

    @Test
    @DisplayName("未发布的问卷不能收答卷")
    void submit_shouldRejectUnpublished() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.DRAFT.code(), 0));

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
        assertEquals("问卷未发布", ex.getMessage());
    }

    @Test
    @DisplayName("status 为 NULL（历史数据）按草稿处理 → 同样拒收")
    void submit_shouldRejectNullStatus() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(null, 0));

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
        assertEquals("问卷未发布", ex.getMessage());
    }

    @Test
    @DisplayName("已结束（status=2）的问卷同样收不了答卷")
    void submit_shouldRejectEnded() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.ENDED.code(), 0));

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
        assertEquals("问卷未发布", ex.getMessage());
    }

    @Test
    @DisplayName("问卷不存在 → 400「问卷不存在」")
    void submit_shouldRejectMissingSurvey() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
        assertEquals("问卷不存在", ex.getMessage());
    }

    // ==================== 二、答卷防重 ====================
    //
    //  产品规则（用户确认）：**登录态一律拦住**；**匿名侧有意不拦**（会误伤 NAT 后的同网用户）。
    //  这一组同时钉住"拦"与"不拦"两侧 —— 只钉一侧的话，很容易在以后改成"干脆都拦/都不拦"。

    @Test
    @DisplayName("★ 登录用户已经交过 → 400「你已经提交过这份问卷」（答卷防重）")
    void submit_shouldRejectDuplicateSubmissionByLoggedInUser() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.PUBLISHED.code(), 0));
        // 关键 stub：这个用户对这份问卷已有 1 份答卷
        when(answerMapper.countBySurveyAndUser(SURVEY_ID, USER_ID)).thenReturn(1);

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
        assertTrue(ex.getMessage().contains("已经提交过"), "实际文案：" + ex.getMessage());
    }

    @Test
    @DisplayName("登录用户**没交过** → 正常放行（别把第一次提交也拦掉）")
    void submit_shouldAllowFirstSubmissionByLoggedInUser() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.PUBLISHED.code(), 0));
        when(answerMapper.countBySurveyAndUser(SURVEY_ID, USER_ID)).thenReturn(0);
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(question(1)));

        assertDoesNotThrow(() -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
    }

    @Test
    @DisplayName("★ 匿名提交（userId=null）**不查重、直接放行** —— 这是有意为之，不是漏了")
    void submit_shouldNotCheckDuplicateForAnonymous() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.PUBLISHED.code(), 0));
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(question(1)));

        assertDoesNotThrow(() -> answerService.submit(SURVEY_ID, null, details(1)));
        // ★ 防回归（这条比上面那条更重要）：匿名侧**绝不能**去查重 ——
        //   一旦有人"顺手"把判定改成不判 userId 就查，NAT 后的第二个人会被直接拒绝，
        //   而那种 bug 只在"同一出口 IP 的两人都答题"时才暴露，极难复现。
        verify(answerMapper, never()).countBySurveyAndUser(any(), any());
    }

    @Test
    @DisplayName("空答卷直接拒绝")
    void submit_shouldRejectEmptyDetails() {
        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, List.of()));
        assertEquals("答卷内容不能为空", ex.getMessage());
    }

    // ==================== 三、截止时间 ====================
    //
    //  ★ 为什么必须由 Service 判、而不是等定时任务改 status：
    //    定时任务每分钟才跑一次，且应用停机/重调度期间根本不跑 ——
    //    过了截止时间、status 还是 1 的那段窗口里，提交会照常返回 200「提交成功」，
    //    而这段窗口的长度不确定。所以截止时间必须在每次提交时就地问一次。

    @Test
    @DisplayName("★ 截止时间已过 → 400「问卷已截止」（即使 status 还是 1）")
    void submit_shouldRejectExpiredSurveyEvenIfStillPublished() {
        Survey s = survey(SurveyStatus.PUBLISHED.code(), 0);
        s.setEndTime(java.time.LocalDateTime.now().minusMinutes(10));
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(s);

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
        assertEquals("问卷已截止，无法提交答卷", ex.getMessage());
    }

    @Test
    @DisplayName("截止时间恰好在“此刻” → 同样拒（边界：必须严格晚于当前时间）")
    void submit_shouldRejectSurveyEndingExactlyNow() {
        Survey s = survey(SurveyStatus.PUBLISHED.code(), 0);
        s.setEndTime(java.time.LocalDateTime.now());
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(s);

        assertThrows(BizException.class, () -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
    }

    @Test
    @DisplayName("截止时间在未来 → 正常收（不要误伤未过期的问卷）")
    void submit_shouldAcceptSurveyEndingInFuture() {
        Survey s = survey(SurveyStatus.PUBLISHED.code(), 0);
        s.setEndTime(java.time.LocalDateTime.now().plusDays(1));
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(s);
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(question(1)));

        assertDoesNotThrow(() -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
    }

    @Test
    @DisplayName("截止时间为 null → 不限制（长期问卷照常收）")
    void submit_shouldAcceptSurveyWithoutEndTime() {
        Survey s = survey(SurveyStatus.PUBLISHED.code(), 0);
        s.setEndTime(null);
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(s);
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(question(1)));

        assertDoesNotThrow(() -> answerService.submit(SURVEY_ID, USER_ID, details(1)));
    }

    // ==================== 四、入参的规模与语义 ====================

    @Test
    @DisplayName("★ 明细条数上限 200：201 条直接拒绝（防一次请求灌爆）")
    void submit_shouldRejectTooManyDetails() {
        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, details(201)));
        assertTrue(ex.getMessage().contains("作答条目过多"), "实际文案：" + ex.getMessage());
    }

    @Test
    @DisplayName("★ 单选题同一道题出现两次 → 拒绝（防一次 POST 给某个选项灌 200 票）")
    void submit_shouldRejectDuplicateSingleChoiceQuestion() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.PUBLISHED.code(), 0));
        when(questionMapper.findBySurveyId(SURVEY_ID))
                .thenReturn(List.of(question(1, QuestionType.SINGLE.code()), question(2)));

        List<AnswerDetail> duplicated = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            AnswerDetail d = new AnswerDetail();
            d.setQuestionId(1);          // 同一个 questionId 出现两次
            d.setContent("选项A");
            duplicated.add(d);
        }

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, duplicated));
        assertEquals("同一道题在一次提交里出现了多次", ex.getMessage());
    }

    // ==================== 五、多选题的"一选项一行" ====================

    @Test
    @DisplayName("★★ 多选题允许多行（一选项一行）：勾 ≥2 项不报同题重复")
    void submit_shouldAllowMultipleRowsForMultiChoiceQuestion() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.PUBLISHED.code(), 0));
        when(questionMapper.findBySurveyId(SURVEY_ID))
                .thenReturn(List.of(question(1, QuestionType.MULTI.code())));

        // 前端答题页对多选的每个勾选项各生成一条明细（与历史答卷 / 演示数据 / 统计口径一致）
        List<AnswerDetail> multi = new ArrayList<>();
        for (String content : List.of("选项A", "选项B", "选项C")) {
            AnswerDetail d = new AnswerDetail();
            d.setQuestionId(1);
            d.setContent(content);
            multi.add(d);
        }

        assertDoesNotThrow(() -> answerService.submit(SURVEY_ID, USER_ID, multi),
                "多选题勾选多个选项必须能提交成功");
    }

    @Test
    @DisplayName("★ 多选题也不是无上限：同一题超过 50 行被拒（防'一次 POST 灌 200 票'换个形态复活）")
    void submit_shouldRejectTooManyRowsForMultiChoiceQuestion() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.PUBLISHED.code(), 0));
        when(questionMapper.findBySurveyId(SURVEY_ID))
                .thenReturn(List.of(question(1, QuestionType.MULTI.code())));

        List<AnswerDetail> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {            // 51 > MAX_ROWS_PER_QUESTION(50)
            AnswerDetail d = new AnswerDetail();
            d.setQuestionId(1);
            d.setContent("选项" + i);
            tooMany.add(d);
        }

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, tooMany));
        assertTrue(ex.getMessage().contains("作答选项过多"), "实际文案：" + ex.getMessage());
    }

    @Test
    @DisplayName("填空题同样是一题一行（重复出现按单选口径拒绝）")
    void submit_shouldRejectDuplicateTextQuestion() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.PUBLISHED.code(), 0));
        when(questionMapper.findBySurveyId(SURVEY_ID))
                .thenReturn(List.of(question(1, QuestionType.TEXT.code())));

        List<AnswerDetail> duplicated = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            AnswerDetail d = new AnswerDetail();
            d.setQuestionId(1);
            d.setContent("填空答案");
            duplicated.add(d);
        }

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, duplicated));
        assertEquals("同一道题在一次提交里出现了多次", ex.getMessage());
    }

    @Test
    @DisplayName("不属于本问卷的题目 id 被拒（既有校验的回归保护）")
    void submit_shouldRejectForeignQuestion() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.PUBLISHED.code(), 0));
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(question(1)));

        List<AnswerDetail> foreign = new ArrayList<>();
        AnswerDetail d = new AnswerDetail();
        d.setQuestionId(999);
        d.setContent("x");
        foreign.add(d);

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, foreign));
        assertEquals("答卷包含不属于该问卷的题目", ex.getMessage());
    }

    @Test
    @DisplayName("单题回答超过 1000 字被拒（与 t_answer_detail.content 的列宽一致）")
    void submit_shouldRejectTooLongContent() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(SurveyStatus.PUBLISHED.code(), 0));
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(question(1)));

        List<AnswerDetail> tooLong = new ArrayList<>();
        AnswerDetail d = new AnswerDetail();
        d.setQuestionId(1);
        d.setContent("字".repeat(1001));
        tooLong.add(d);

        BizException ex = assertThrows(BizException.class,
                () -> answerService.submit(SURVEY_ID, USER_ID, tooLong));
        assertTrue(ex.getMessage().contains("不能超过 1000 个字符"), "实际文案：" + ex.getMessage());
    }
}
