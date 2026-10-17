package com.survey.service;

import com.survey.common.QuestionType;
import com.survey.common.SurveyStatus;
import com.survey.entity.Answer;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * {@link AnswerService} 与统计缓存的**接入层**测试（纯单测，不连 Redis / MySQL）。
 *
 * ── 为什么需要这个类（StatsCacheServiceTest 覆盖不到的地方）────────
 *   `StatsCacheServiceTest` 只证明"缓存组件自己是对的"，它证明不了：
 *     ① 三条统计查询**真的**用了缓存（把缓存行删掉，那个测试类照样全绿）；
 *     ② 提交答卷后的刷新**真的**发生在事务提交之后。
 *   ★ ② 是最要命的一条：`submit()` 是 `@Transactional`，若把 `bumpAnswerCount` 挪到事务内，
 *   "明细插入失败 / 撞唯一索引 → 事务回滚"时缓存已经自增了 —— 库里没有这份答卷，
 *   缓存却比库多 1，而且会一直错到 TTL 到期。下面的用例用 `TransactionSynchronizationManager`
 *   手动模拟"注册回调 → 提交"，把这条不变量钉住。
 *
 * ── 关于 mock ─────────────────────────────────────────
 *   只 mock Mapper / 通知服务 / **缓存服务**，被测对象 `AnswerService` 是真实的，
 *   所以断言的是它真实的调用顺序与时机（而不是"mock 返回了我想要的东西"）。
 */
@ExtendWith(MockitoExtension.class)
class AnswerServiceCacheTest {

    private static final Integer SURVEY_ID = 100;
    private static final Integer Q1 = 10;
    private static final Integer Q2 = 11;

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
    /** ★ 提交成功后要跑质控判定：这两个依赖不注入 = null，走到"提交成功"就 NPE。 */
    @Mock
    private OptionMapper optionMapper;
    @Mock
    private AnswerQualityService answerQualityService;

    @InjectMocks
    private AnswerService answerService;

    // ==================== 读路径 ====================

    @Test
    @DisplayName("答卷数：命中缓存时不查库（缓存没被真正用上就等于白加）")
    void countResponsesUsesCache() {
        when(statsCacheService.getAnswerCount(SURVEY_ID)).thenReturn(20000);

        assertEquals(20000, answerService.countResponses(SURVEY_ID));

        verifyNoInteractions(answerMapper);
    }

    @Test
    @DisplayName("答卷数：未命中才回源，并把结果写回缓存")
    void countResponsesFillsCacheOnMiss() {
        when(statsCacheService.getAnswerCount(SURVEY_ID)).thenReturn(null);
        when(answerMapper.countBySurveyId(SURVEY_ID)).thenReturn(7);

        assertEquals(7, answerService.countResponses(SURVEY_ID));

        verify(statsCacheService).putAnswerCount(SURVEY_ID, 7);
    }

    @Test
    @DisplayName("单题分布：命中缓存时不扫明细表")
    void countQuestionUsesCache() {
        List<Map<String, Object>> cached = List.of(Map.of("content", "手机", "count", 3));
        when(statsCacheService.getQuestionStats(Q1)).thenReturn(cached);

        assertSame(cached, answerService.countQuestion(Q1));

        verifyNoInteractions(answerDetailMapper);
    }

    @Test
    @DisplayName("单题分布：未命中才回源，并把结果写回缓存")
    void countQuestionFillsCacheOnMiss() {
        List<Map<String, Object>> stats = List.of(Map.of("content", "手机", "count", 1));
        when(statsCacheService.getQuestionStats(Q1)).thenReturn(null);
        when(answerDetailMapper.countByQuestionId(Q1)).thenReturn(stats);

        assertSame(stats, answerService.countQuestion(Q1));

        verify(statsCacheService).putQuestionStats(Q1, stats);
    }

    @Test
    @DisplayName("填空文本：命中缓存不查库，且不会重复写回")
    void listTextAnswersUsesCache() {
        when(statsCacheService.getQuestionTexts(Q1)).thenReturn(List.of("已缓存"));

        assertEquals(List.of("已缓存"), answerService.listTextAnswers(Q1));

        verifyNoInteractions(answerDetailMapper);
        verify(statsCacheService, never()).putQuestionTexts(anyInt(), anyList());
    }

    // ==================== 写路径（本类的重点） ====================

    @Test
    @DisplayName("提交答卷（无事务上下文）：直接刷新缓存 —— 答卷数自增 + 该问卷所有题失效")
    void submitRefreshesCacheWithoutTransaction() {
        givenSubmittableSurvey();

        answerService.submit(SURVEY_ID, null, List.of(detail(Q1, "A"), detail(Q2, "B")));

        verify(statsCacheService).bumpAnswerCount(SURVEY_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Integer>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(statsCacheService).evictQuestions(ids.capture());
        assertTrue(ids.getValue().containsAll(List.of(Q1, Q2)),
                "应当失效该问卷的所有题，实际失效的是 " + ids.getValue());
    }

    @Test
    @DisplayName("★ 提交答卷（有事务同步）：提交前**不碰**缓存，afterCommit 之后才刷新")
    void submitDefersCacheRefreshUntilAfterCommit() {
        givenSubmittableSurvey();

        TransactionSynchronizationManager.initSynchronization();
        try {
            answerService.submit(SURVEY_ID, null, List.of(detail(Q1, "A")));

            // 事务还没提交：缓存必须原封不动（否则回滚就会留下"多一份答卷"的脏值）
            verify(statsCacheService, never()).bumpAnswerCount(anyInt());
            verify(statsCacheService, never()).evictQuestions(anyList());

            List<TransactionSynchronization> registrations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertFalse(registrations.isEmpty(), "提交后要刷新缓存，就必须注册 afterCommit 回调");
            registrations.forEach(TransactionSynchronization::afterCommit);

            verify(statsCacheService).bumpAnswerCount(SURVEY_ID);
            verify(statsCacheService).evictQuestions(anyList());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================== 夹具 ====================

    /** 构造一份"能通过 submit 全部守卫"的问卷：已发布、未删除、无截止时间、有两道单选题。 */
    private void givenSubmittableSurvey() {
        Survey survey = new Survey();
        survey.setId(SURVEY_ID);
        survey.setUserId(1);                       // 有作者 → 提交后会给作者发通知（mock 掉）
        survey.setTitle("测试问卷");
        survey.setStatus(SurveyStatus.PUBLISHED.code());
        survey.setDeleted(0);
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey);

        when(questionMapper.findBySurveyId(SURVEY_ID))
                .thenReturn(List.of(question(Q1), question(Q2)));

        // 自增主键由数据库回填，这里手动设置，让明细的 answerId 不为 null
        doAnswer(invocation -> {
            ((Answer) invocation.getArgument(0)).setId(999);
            return 1;
        }).when(answerMapper).insert(any(Answer.class));

        // ★ 质控判定：本类只关心"缓存刷新的时机"，判定结果固定为"不可疑"。
        //   optionMapper 不必 stub —— Mockito 对 List 返回**空集合**（不是 null），判定会直接跳过。
        when(answerQualityService.evaluate(any(), any(), any(), any()))
                .thenReturn(new AnswerQualityService.QualityVerdict(false, java.util.List.of()));
    }

    private static Question question(Integer id) {
        Question q = new Question();
        q.setId(id);
        q.setSurveyId(SURVEY_ID);
        q.setType(QuestionType.SINGLE.code());
        q.setTitle("题目 " + id);
        return q;
    }

    private static AnswerDetail detail(Integer questionId, String content) {
        AnswerDetail d = new AnswerDetail();
        d.setQuestionId(questionId);
        d.setContent(content);
        return d;
    }
}
