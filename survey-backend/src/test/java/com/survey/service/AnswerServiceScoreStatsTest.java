package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.PageResult;
import com.survey.common.SurveyStatus;
import com.survey.dto.MyAnswerDetailDTO;
import com.survey.dto.MyAnswerItem;
import com.survey.dto.OwnerParticipantItem;
import com.survey.dto.SurveyStatisticsDTO;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判分的**统计与查询侧**单元测试。
 *
 * ── 钉住什么 ──────────────────────────────────────────
 *   ① 平均分/及格率只统计"已判分"的答卷（earned_score IS NOT NULL）——
 *      把普通问卷或判分上线前的历史答卷算成 0 分，会让平均分莫名其妙地低；
 *   ② gradedCount=0 时整个 scoreSummary 为 null（前端据此不渲染指标卡），
 *      而不是返回 0/0.0 这种"看起来像考了 0 分"的数据；
 *   ③ 普通问卷**根本不查**这条聚合（多一条 SQL 也不该有）；
 *   ④ "我的成绩"必须按 (surveyId, 登录用户) 定位，匿名用户直接返回 null。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnswerServiceScoreStatsTest {

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

    private static Survey survey(Integer gradeEnabled, Integer passScore) {
        Survey s = new Survey();
        s.setId(SURVEY_ID);
        s.setTitle("期中测验");
        s.setStatus(SurveyStatus.PUBLISHED.code());
        s.setDeleted(0);
        s.setGradeEnabled(gradeEnabled);
        s.setPassScore(passScore);
        return s;
    }

    private static Question choiceQuestion() {
        Question q = new Question();
        q.setId(11);
        q.setSurveyId(SURVEY_ID);
        q.setType(1);
        q.setTitle("第 1 题");
        return q;
    }

    /** 让统计路径不真的去查库：题目只有一道，且单题统计命中缓存。 */
    private void givenOneCachedQuestion() {
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(choiceQuestion()));
        when(statsCacheService.getQuestionStats(11))
                .thenReturn(List.of(Map.of("content", "A", "count", 3)));
        when(statsCacheService.getAnswerCount(SURVEY_ID)).thenReturn(null);
        when(answerMapper.countBySurveyId(SURVEY_ID)).thenReturn(10);
    }

    private static Map<String, Object> scoreRow(long cnt, Double avg, Long passCnt) {
        Map<String, Object> row = new HashMap<>();
        row.put("cnt", cnt);
        row.put("avg_score", avg);
        row.put("pass_cnt", passCnt);
        return row;
    }

    @Test
    @DisplayName("考试问卷：平均分保留一位小数，及格率 = 及格份数/已判分份数")
    void scoreSummary_computed() {
        givenOneCachedQuestion();
        when(answerMapper.selectScoreSummary(SURVEY_ID, false))
                .thenReturn(scoreRow(4, 76.25, 3L));

        SurveyStatisticsDTO dto = answerService.getSurveyStatistics(survey(1, 60), false);

        assertNotNull(dto.scoreSummary());
        assertEquals(4, dto.scoreSummary().gradedCount());
        assertEquals(76.3, dto.scoreSummary().avgScore(), "76.25 → 76.3（保留一位小数）");
        assertEquals(75.0, dto.scoreSummary().passRate());
    }

    @Test
    @DisplayName("没有已判分答卷：scoreSummary 为 null（不能返回 0 分）")
    void scoreSummary_nullWhenNoGradedAnswer() {
        givenOneCachedQuestion();
        when(answerMapper.selectScoreSummary(SURVEY_ID, false)).thenReturn(scoreRow(0, null, null));

        SurveyStatisticsDTO dto = answerService.getSurveyStatistics(survey(1, 60), false);

        assertNull(dto.scoreSummary(), "gradedCount=0 时前端不该显示平均分 0");
    }

    @Test
    @DisplayName("普通问卷：不查判分聚合（多一条 SQL 也不该有）")
    void plainSurvey_neverQueriesScoreSummary() {
        givenOneCachedQuestion();

        SurveyStatisticsDTO dto = answerService.getSurveyStatistics(survey(0, null), false);

        assertNull(dto.scoreSummary());
        verify(answerMapper, never()).selectScoreSummary(any(), anyBoolean());
    }

    @Test
    @DisplayName("剔除可疑样本时：聚合查询要带上 excludeInvalid=true（与图表同口径）")
    void scoreSummary_respectsExcludeInvalid() {
        givenOneCachedQuestion();
        when(answerMapper.selectScoreSummary(SURVEY_ID, true))
                .thenReturn(scoreRow(2, 90.0, 2L));

        SurveyStatisticsDTO dto = answerService.getSurveyStatistics(survey(1, 60), true);

        assertEquals(2, dto.scoreSummary().gradedCount());
        verify(answerMapper).selectScoreSummary(SURVEY_ID, true);
    }

    @Test
    @DisplayName("我的成绩：按 (surveyId, userId) 查最近一条；匿名为 null")
    void latestAnswer_requiresUserId() {
        assertNull(answerService.getLatestAnswer(SURVEY_ID, null));

        Answer answer = new Answer();
        answer.setSurveyId(SURVEY_ID);
        answer.setUserId(9);
        answer.setEarnedScore(80);
        answer.setTotalScore(100);
        answer.setSubmitTime(LocalDateTime.now());
        when(answerMapper.findLatestBySurveyAndUser(SURVEY_ID, 9)).thenReturn(answer);

        assertSame(answer, answerService.getLatestAnswer(SURVEY_ID, 9));
        verify(answerMapper, never()).findById(anyInt());
    }

    @Test
    @DisplayName("我的答题记录：按当前用户分页（offset=(页-1)×条数）；无记录不查列表；匿名为 400")
    void myAnswers_paginationAndIdentity() {
        when(answerMapper.countByUser(9, null, null, null)).thenReturn(0);
        PageResult<MyAnswerItem> empty = answerService.myAnswers(9, 1, 10, null, null, null);
        assertEquals(0, empty.getTotal());
        verify(answerMapper, never()).pageByUser(any(), any(), any(), any(), anyLong(), anyInt());

        when(answerMapper.countByUser(9, null, null, null)).thenReturn(25);
        MyAnswerItem item = new MyAnswerItem();
        item.setSurveyTitle("期中测验");
        when(answerMapper.pageByUser(eq(9), any(), any(), any(), eq(20L), eq(10))).thenReturn(List.of(item));

        PageResult<MyAnswerItem> page = answerService.myAnswers(9, 3, 10, null, null, null);
        assertEquals(25, page.getTotal());
        assertEquals(3, page.getPageNum());
        assertEquals(1, page.getList().size(), "第 3 页的 offset 必须是 20");

        assertThrows(BizException.class, () -> answerService.myAnswers(null, 1, 10, null, null, null));
    }

    @Test
    @DisplayName("我的答题记录：关键词去空白、日期按闭区间解析；日期格式非法 400")
    void myAnswers_appliesFilters() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 10, 31, 23, 59, 59);
        when(answerMapper.countByUser(9, "期中", start, end)).thenReturn(0);

        PageResult<MyAnswerItem> empty = answerService.myAnswers(9, 1, 10, "  期中  ", "2026-10-01", "2026-10-31");

        assertEquals(0, empty.getTotal());
        verify(answerMapper).countByUser(9, "期中", start, end);
        assertThrows(BizException.class,
                () -> answerService.myAnswers(9, 1, 10, null, "2026/10/01", null));
    }

    @Test
    @DisplayName("作者端名单：报名/签到数走聚合，名单按接龙顺序带签到状态")
    @SuppressWarnings("unchecked")
    void ownerParticipants_summaryAndCheckin() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(1, 0));
        Map<String, Object> summary = new HashMap<>();
        summary.put("total", 25);
        summary.put("checkedIn", 7);
        when(answerMapper.countParticipants(SURVEY_ID)).thenReturn(summary);

        Answer first = new Answer();
        first.setId(1);
        first.setSurveyId(SURVEY_ID);
        first.setSubmitTime(LocalDateTime.of(2026, 10, 19, 9, 0));
        first.setCheckedIn(1);
        first.setCheckedInAt(LocalDateTime.of(2026, 10, 19, 9, 5));
        Answer second = new Answer();
        second.setId(2);
        second.setSurveyId(SURVEY_ID);
        second.setSubmitTime(LocalDateTime.of(2026, 10, 19, 9, 1));
        second.setCheckedIn(0);
        when(answerMapper.findFirstBySurveyId(SURVEY_ID, 500)).thenReturn(List.of(first, second));
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of());

        Map<String, Object> result = answerService.listParticipantsForOwner(SURVEY_ID);

        assertEquals(25, result.get("total"));
        assertEquals(7, result.get("checkedInCount"));
        List<OwnerParticipantItem> list = (List<OwnerParticipantItem>) result.get("list");
        assertEquals(2, list.size());
        assertEquals("匿名", list.get(0).name(), "没有文本题时称呼兜底为匿名");
        assertEquals(1, list.get(0).checkedIn());
        assertEquals(0, list.get(1).checkedIn());
    }

    @Test
    @DisplayName("我的答卷详情：按答卷归属校验；未开启「显示答案」时不给正确答案")
    void myAnswerDetail_ownershipAndPrivacy() {
        Answer a = new Answer();
        a.setId(55);
        a.setSurveyId(SURVEY_ID);
        a.setUserId(9);
        when(answerMapper.findById(55)).thenReturn(a);
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey(1, 60));
        Question q = choiceQuestion();
        q.setScore(10);
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(q));
        AnswerDetail d = new AnswerDetail();
        d.setAnswerId(55);
        d.setQuestionId(11);
        d.setContent("A");
        d.setIsCorrect(1);
        when(answerDetailMapper.findByAnswerId(55)).thenReturn(List.of(d));

        MyAnswerDetailDTO dto = answerService.getMyAnswerDetail(9, 55);
        assertEquals(1, dto.questions().size());
        assertEquals("A", dto.questions().get(0).answer());
        assertTrue(dto.questions().get(0).correct());
        assertNull(dto.questions().get(0).correctAnswers(), "没开显示答案就不能带正确答案");

        assertThrows(BizException.class, () -> answerService.getMyAnswerDetail(10, 55), "别人的答卷不能看");
        assertThrows(BizException.class, () -> answerService.getMyAnswerDetail(null, 55));
    }

    @Test
    @DisplayName("我的答卷详情：开启「显示答案」时带正确答案")
    void myAnswerDetail_includesCorrectAnswersWhenEnabled() {
        Survey s = survey(1, 60);
        s.setShowAnswerAfterSubmit(1);
        Answer a = new Answer();
        a.setId(56);
        a.setSurveyId(SURVEY_ID);
        a.setUserId(9);
        when(answerMapper.findById(56)).thenReturn(a);
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(s);
        Question q = choiceQuestion();
        q.setScore(10);
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(q));
        AnswerDetail d = new AnswerDetail();
        d.setAnswerId(56);
        d.setQuestionId(11);
        d.setContent("A");
        d.setIsCorrect(1);
        when(answerDetailMapper.findByAnswerId(56)).thenReturn(List.of(d));
        Option correct = new Option();
        correct.setQuestionId(11);
        correct.setContent("A");
        correct.setIsCorrect(1);
        when(optionMapper.findByQuestionIds(anyList())).thenReturn(List.of(correct));

        MyAnswerDetailDTO dto = answerService.getMyAnswerDetail(9, 56);
        assertEquals(List.of("A"), dto.questions().get(0).correctAnswers());
    }

    @Test
    @DisplayName("签到：未启用 → 400；启用 → 写库并返回状态")
    void checkin_requiresEnabledAndUpdates() {
        Answer a = new Answer();
        a.setId(60);
        a.setSurveyId(SURVEY_ID);
        when(answerMapper.findById(60)).thenReturn(a);

        Survey disabled = survey(0, null);
        disabled.setCheckinEnabled(0);
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(disabled);
        assertThrows(BizException.class, () -> answerService.setCheckedIn(60, true));

        Survey enabled = survey(0, null);
        enabled.setCheckinEnabled(1);
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(enabled);
        assertTrue(answerService.setCheckedIn(60, true));
        verify(answerMapper).updateCheckedIn(60, 1);
        assertFalse(answerService.setCheckedIn(60, false));
        verify(answerMapper).updateCheckedIn(60, 0);
    }

    @Test
    @DisplayName("报名名单：未公开 → 400；公开 → 按顺序给称呼与时间")
    void participants_requiresPublicAndMapsName() {
        Survey hidden = survey(0, null);
        hidden.setShowParticipants(0);
        assertThrows(BizException.class, () -> answerService.listParticipants(hidden));

        Survey open = survey(0, null);
        open.setShowParticipants(1);
        Question nameQ = new Question();
        nameQ.setId(11);
        nameQ.setSurveyId(SURVEY_ID);
        nameQ.setType(3);
        nameQ.setTitle("姓名");
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(nameQ));
        Answer a1 = new Answer();
        a1.setId(71);
        a1.setSurveyId(SURVEY_ID);
        a1.setSubmitTime(java.time.LocalDateTime.now());
        Answer a2 = new Answer();
        a2.setId(72);
        a2.setSurveyId(SURVEY_ID);
        a2.setSubmitTime(java.time.LocalDateTime.now());
        when(answerMapper.findFirstBySurveyId(SURVEY_ID, 50)).thenReturn(List.of(a1, a2));
        java.util.Map<String, Object> row1 = new java.util.HashMap<>();
        row1.put("aid", 71);
        row1.put("content", "张三");
        java.util.Map<String, Object> row2 = new java.util.HashMap<>();
        row2.put("aid", 72);
        row2.put("content", "李四");
        when(answerMapper.findContentsByAnswerIds(11, List.of(71, 72))).thenReturn(List.of(row1, row2));

        List<com.survey.dto.ParticipantItem> list = answerService.listParticipants(open);
        assertEquals(2, list.size());
        assertEquals("张三", list.get(0).name());
        assertEquals(1, list.get(0).seq());
        assertEquals("李四", list.get(1).name());
    }
}
