package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.SurveyStatus;
import com.survey.dto.SubmitResult;
import com.survey.entity.Answer;
import com.survey.entity.AnswerDetail;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyIpMapper;
import com.survey.mapper.SurveyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判分**接入层**的单元测试：验证分数真的被算出来、写进了 t_answer、并随返回体带回去。
 *
 * ── 与 GraderTest 的分工 ──────────────────────────────
 *   GraderTest 钉"规则算得对不对"；本类钉"接得对不对"：
 *     · gradeEnabled=0 时三个分数字段必须留 NULL（普通问卷零影响）；
 *     · gradeEnabled=1 时 total/earned/passed 与返回体一致；
 *     · 分数是**随 INSERT 一起落库**的（用 ArgumentCaptor 抓 insert 的入参）。
 *   两类测试都绿，才能说"判分真的生效了"，而不是"写了个没人调用的类"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnswerServiceGradingTest {

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
    @Mock
    private StatsCacheService statsCacheService;
    @Mock
    private OptionMapper optionMapper;
    @Mock
    private AnswerQualityService answerQualityService;
    @Mock
    private SurveyIpMapper surveyIpMapper;

    @InjectMocks
    private AnswerService answerService;

    private static final Integer SURVEY_ID = 7;

    @BeforeEach
    void setUp() {
        // 质控判定不是本类的关注点，统一给"未命中任何规则"
        when(answerQualityService.evaluate(any(), any(), any(), any()))
                .thenReturn(new AnswerQualityService.QualityVerdict(false, List.of()));
        // 插入答卷时把自增 id 回填（后面插明细要用）
        when(answerMapper.insert(any())).thenAnswer(inv -> {
            Answer a = inv.getArgument(0);
            a.setId(100);
            return 1;
        });
    }

    private static Survey survey(int gradeEnabled, Integer passScore) {
        Survey s = new Survey();
        s.setId(SURVEY_ID);
        s.setTitle("期中测验");
        s.setUserId(3);
        s.setStatus(SurveyStatus.PUBLISHED.code());
        s.setDeleted(0);
        s.setGradeEnabled(gradeEnabled);
        s.setPassScore(passScore);
        return s;
    }

    private static Question single(int id, int score) {
        Question q = new Question();
        q.setId(id);
        q.setSurveyId(SURVEY_ID);
        q.setType(1);
        q.setTitle("第 " + id + " 题");
        q.setScore(score);
        q.setSortOrder(0);
        return q;
    }

    private static Option option(int questionId, String content, boolean correct) {
        Option o = new Option();
        o.setQuestionId(questionId);
        o.setContent(content);
        o.setIsCorrect(correct ? 1 : 0);
        return o;
    }

    private static AnswerDetail answer(int questionId, String content) {
        AnswerDetail d = new AnswerDetail();
        d.setQuestionId(questionId);
        d.setContent(content);
        return d;
    }

    private void givenSurvey(Question q, Option... options) {
        givenSurvey(survey(1, 8), q, options);
    }

    private void givenSurvey(Survey s, Question q, Option... options) {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(s);
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(q));
        when(optionMapper.findByQuestionIds(anyList())).thenReturn(List.of(options));
    }

    @Test
    @DisplayName("考试问卷：分数随 INSERT 落库，返回体带 graded=true")
    void graded_scoresArePersistedAndReturned() {
        Question q = single(11, 10);
        givenSurvey(q, option(11, "A", true), option(11, "B", false));

        SubmitResult result = answerService.submit(SURVEY_ID, null,
                List.of(answer(11, "A")), 3000, "127.0.0.1");

        assertTrue(result.graded());
        assertEquals(10, result.totalScore());
        assertEquals(10, result.earnedScore());
        assertEquals(8, result.passScore());
        assertEquals(1, result.passed());

        ArgumentCaptor<Answer> captor = ArgumentCaptor.forClass(Answer.class);
        verify(answerMapper).insert(captor.capture());
        Answer saved = captor.getValue();
        assertEquals(10, saved.getTotalScore());
        assertEquals(10, saved.getEarnedScore());
        assertEquals(1, saved.getIsPassed(), "分数必须与答卷一起插入，不能事后再 UPDATE");
    }

    @Test
    @DisplayName("普通问卷：三个分数字段留 NULL，返回 graded=false（零影响）")
    void notGraded_fieldsStayNull() {
        Question q = single(11, 10);
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(0, null));
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(q));
        when(optionMapper.findByQuestionIds(anyList()))
                .thenReturn(List.of(option(11, "A", true)));

        SubmitResult result = answerService.submit(SURVEY_ID, null,
                List.of(answer(11, "A")), 3000, "127.0.0.1");

        assertFalse(result.graded());
        assertNull(result.earnedScore());
        assertNull(result.passed());

        ArgumentCaptor<Answer> captor = ArgumentCaptor.forClass(Answer.class);
        verify(answerMapper).insert(captor.capture());
        assertNull(captor.getValue().getEarnedScore());
        assertNull(captor.getValue().getTotalScore());
        assertNull(captor.getValue().getIsPassed());
    }

    @Test
    @DisplayName("多选：漏选 → 0 分（接入层也要与 Grader 同口径）")
    void multiPartial_getsZero() {
        Question q = new Question();
        q.setId(13);
        q.setSurveyId(SURVEY_ID);
        q.setType(2);
        q.setTitle("多选题");
        q.setScore(10);
        givenSurvey(q, option(13, "A", true), option(13, "B", true));

        SubmitResult result = answerService.submit(SURVEY_ID, null,
                List.of(answer(13, "A")), 3000, "127.0.0.1");

        assertEquals(0, result.earnedScore());
        assertEquals(10, result.totalScore());
        assertEquals(0, result.passed(), "0 分 < 及格分 8");
    }

    @Test
    @DisplayName("及格分未设置时：passed 为 null，不能写成 0")
    void noPassScore_passedIsNull() {
        Question q = single(11, 10);
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(1, null));
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(q));
        when(optionMapper.findByQuestionIds(anyList()))
                .thenReturn(List.of(option(11, "A", true)));

        SubmitResult result = answerService.submit(SURVEY_ID, null,
                List.of(answer(11, "A")), 3000, "127.0.0.1");

        assertNull(result.passed());
        ArgumentCaptor<Answer> captor = ArgumentCaptor.forClass(Answer.class);
        verify(answerMapper).insert(captor.capture());
        assertNull(captor.getValue().getIsPassed());
    }

    // ==================== P1：限时 / 逐题对错 / 提交后显示答案 ====================

    @Test
    @DisplayName("限时：超过限时 + 10 秒宽限 → 400，且一份数据都不写")
    void timeLimitExceeded_rejected() {
        Survey s = survey(1, 8);
        s.setTimeLimitSeconds(60);
        givenSurvey(s, single(11, 10), option(11, "A", true));

        BizException e = assertThrows(BizException.class, () -> answerService.submit(
                SURVEY_ID, null, List.of(answer(11, "A")), 71_000, "127.0.0.1"));

        assertTrue(e.getMessage().contains("答题超时"), e.getMessage());
        verify(answerMapper, never()).insert(any());
        verify(answerDetailMapper, never()).insert(any());
    }

    @Test
    @DisplayName("限时：落在宽限内（69 秒 / 限时 60 秒）→ 正常收卷")
    void timeLimitWithinGrace_accepted() {
        Survey s = survey(1, 8);
        s.setTimeLimitSeconds(60);
        givenSurvey(s, single(11, 10), option(11, "A", true));

        SubmitResult result = answerService.submit(SURVEY_ID, null,
                List.of(answer(11, "A")), 69_000, "127.0.0.1");

        assertEquals(10, result.earnedScore());
    }

    @Test
    @DisplayName("逐题对错：答对写 1、答错写 0（t_answer_detail.is_correct）")
    void correctness_isPersistedOnDetails() {
        givenSurvey(single(11, 10), option(11, "A", true), option(11, "B", false));
        answerService.submit(SURVEY_ID, null, List.of(answer(11, "A")), 3000, "127.0.0.1");
        ArgumentCaptor<AnswerDetail> right = ArgumentCaptor.forClass(AnswerDetail.class);
        verify(answerDetailMapper).insert(right.capture());
        assertEquals(1, right.getValue().getIsCorrect());

        givenSurvey(single(11, 10), option(11, "A", true), option(11, "B", false));
        answerService.submit(SURVEY_ID, null, List.of(answer(11, "B")), 3000, "127.0.0.1");
        ArgumentCaptor<AnswerDetail> wrong = ArgumentCaptor.forClass(AnswerDetail.class);
        verify(answerDetailMapper, times(2)).insert(wrong.capture());
        assertEquals(0, wrong.getAllValues().get(1).getIsCorrect());
    }

    @Test
    @DisplayName("普通问卷：明细的 isCorrect 保持 null（不能被当成答错）")
    void plainSurvey_correctnessStaysNull() {
        Survey s = survey(0, null);
        givenSurvey(s, single(11, 10), option(11, "A", true));

        answerService.submit(SURVEY_ID, null, List.of(answer(11, "A")), 3000, "127.0.0.1");

        ArgumentCaptor<AnswerDetail> captor = ArgumentCaptor.forClass(AnswerDetail.class);
        verify(answerDetailMapper).insert(captor.capture());
        assertNull(captor.getValue().getIsCorrect());
    }

    @Test
    @DisplayName("仅限登录：匿名提交 → 400，且一份数据都不写")
    void requireLogin_rejectsAnonymous() {
        Survey s = survey(0, null);
        s.setRequireLogin(1);
        givenSurvey(s, single(11, 10), option(11, "A", true));

        BizException e = assertThrows(BizException.class, () -> answerService.submit(
                SURVEY_ID, null, List.of(answer(11, "A")), 3000, "127.0.0.1"));

        assertTrue(e.getMessage().contains("仅限登录"), e.getMessage());
        verify(answerMapper, never()).insert(any());
        verify(answerDetailMapper, never()).insert(any());
    }

    @Test
    @DisplayName("仅限登录：登录用户提交 → 正常收卷，user_id 落库")
    void requireLogin_acceptsLoggedIn() {
        Survey s = survey(0, null);
        s.setRequireLogin(1);
        givenSurvey(s, single(11, 10), option(11, "A", true));

        answerService.submit(SURVEY_ID, 9, List.of(answer(11, "A")), 3000, "127.0.0.1");

        ArgumentCaptor<Answer> captor = ArgumentCaptor.forClass(Answer.class);
        verify(answerMapper).insert(captor.capture());
        assertEquals(9, captor.getValue().getUserId());
    }

    @Test
    @DisplayName("登录态提交路径：即使是匿名问卷，这条路径仍会写 user_id（网页匿名问卷走 share，不走它）")
    void submitPath_alwaysWritesUserId() {
        givenSurvey(single(11, 10), option(11, "A", true));

        answerService.submit(SURVEY_ID, 9, List.of(answer(11, "A")), 3000, "127.0.0.1");

        ArgumentCaptor<Answer> captor = ArgumentCaptor.forClass(Answer.class);
        verify(answerMapper).insert(captor.capture());
        // ★ 两条入口的分工不要混淆：submit/{id} 是"已知身份的提交"，永远写 user_id；
        //   网页上的匿名问卷走 submit-share（userId=null），所以默认问卷的数据语义没变。
        assertEquals(9, captor.getValue().getUserId());
    }

    @Test
    @DisplayName("提交后显示答案：开启才返回逐题结果，关闭时为 null")
    void showAnswers_onlyWhenEnabled() {
        Survey s = survey(1, 8);
        s.setShowAnswerAfterSubmit(1);
        givenSurvey(s, single(11, 10), option(11, "A", true));

        SubmitResult withAnswers = answerService.submit(SURVEY_ID, null,
                List.of(answer(11, "A")), 3000, "127.0.0.1");
        assertNotNull(withAnswers.questions());
        assertEquals(1, withAnswers.questions().size());
        assertTrue(withAnswers.questions().get(0).correct());
        assertEquals(List.of("A"), withAnswers.questions().get(0).correctAnswers());

        // 关闭时再交一份：questions 必须为 null（不泄题）
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(1, 8));
        SubmitResult without = answerService.submit(SURVEY_ID, null,
                List.of(answer(11, "A")), 3000, "127.0.0.1");
        assertNull(without.questions());
    }
}
