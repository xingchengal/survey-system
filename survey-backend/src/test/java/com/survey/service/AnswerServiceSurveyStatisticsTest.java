package com.survey.service;

import com.survey.dto.SurveyStatisticsDTO;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * {@code AnswerService.getSurveyStatistics} 的单元测试（整份问卷统计，一次取回）。
 *
 * ── 这个类钉住的三条不变量（都很容易被"优化"回去）──────────
 *   1. **缓存命中时一条明细 SQL 都不查** —— 否则统计页只是"少了几次 HTTP"，DB 压力一点没减；
 *   2. **未命中的题目合并成一条 SQL**，而不是"每个未命中再单独查一次" ——
 *      后者在缓存全失效时退化成 N 次 SQL，等于白改；
 *   3. **一份答卷都没有的题目要返回空列表而不是 null** —— 前端模板用 `chartData.length` 判断，
 *      null 会让页面直接报错（而不是显示"暂无回答数据"）。
 *
 * ★ 本类不连 Redis / MySQL：`StatsCacheService` 与 Mapper 全部 mock。
 */
@ExtendWith(MockitoExtension.class)
class AnswerServiceSurveyStatisticsTest {

    private static final Integer SURVEY_ID = 100;
    private static final Integer Q_SINGLE = 1;   // 单选
    private static final Integer Q_MULTI = 2;    // 多选
    private static final Integer Q_TEXT = 3;     // 填空

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

    @InjectMocks
    private AnswerService answerService;

    private static Survey survey() {
        Survey s = new Survey();
        s.setId(SURVEY_ID);
        s.setUserId(1);
        s.setTitle("统计测试问卷");
        return s;
    }

    private static Question question(Integer id, int type) {
        Question q = new Question();
        q.setId(id);
        q.setSurveyId(SURVEY_ID);
        q.setType(type);
        q.setTitle("题目" + id);
        return q;
    }

    private static Map<String, Object> row(Integer qid, String content, Long cnt) {
        Map<String, Object> m = new HashMap<>();
        m.put("qid", qid);
        m.put("content", content);
        m.put("cnt", cnt);
        return m;
    }

    private void givenThreeQuestions() {
        when(questionMapper.findBySurveyId(SURVEY_ID))
                .thenReturn(List.of(question(Q_SINGLE, 1), question(Q_MULTI, 2), question(Q_TEXT, 3)));
    }

    @Test
    @DisplayName("★ 全部命中缓存：一条明细 SQL 都不查（否则只是少了几次 HTTP，DB 压力没减）")
    void allHit_CacheOnly() {
        givenThreeQuestions();
        when(statsCacheService.getQuestionStats(Q_SINGLE)).thenReturn(List.of(row(Q_SINGLE, "A", 5L)));
        when(statsCacheService.getQuestionStats(Q_MULTI)).thenReturn(List.of(row(Q_MULTI, "X", 2L)));
        when(statsCacheService.getQuestionTexts(Q_TEXT)).thenReturn(List.of("已经缓存过的文本"));
        when(statsCacheService.getAnswerCount(SURVEY_ID)).thenReturn(7);

        SurveyStatisticsDTO dto = answerService.getSurveyStatistics(survey());

        assertEquals(SURVEY_ID, dto.surveyId());
        assertEquals("统计测试问卷", dto.title());
        assertEquals(7, dto.answerCount());
        assertEquals(3, dto.questions().size());
        verifyNoInteractions(answerDetailMapper);
    }

    @Test
    @DisplayName("★ 全部未命中：选择题一条 SQL + 填空题一条 SQL（不是每题一条）")
    void allMiss_OneBatchQueryPerKind() {
        givenThreeQuestions();
        when(statsCacheService.getQuestionStats(anyInt())).thenReturn(null);
        when(statsCacheService.getQuestionTexts(anyInt())).thenReturn(null);
        when(statsCacheService.getAnswerCount(SURVEY_ID)).thenReturn(2);
        when(answerDetailMapper.countByQuestionIds(anyList()))
                .thenReturn(List.of(row(Q_SINGLE, "A", 3L), row(Q_SINGLE, "B", 1L), row(Q_MULTI, "X", 2L)));
        when(answerDetailMapper.findTextAnswersByQuestionIds(anyList(), anyInt()))
                .thenReturn(List.of(row(Q_TEXT, "hello", null)));

        answerService.getSurveyStatistics(survey());

        verify(answerDetailMapper, times(1)).countByQuestionIds(anyList());
        verify(answerDetailMapper, times(1)).findTextAnswersByQuestionIds(anyList(), anyInt());
        // 钉住"批量"：绝不能退化成逐题查询
        verify(answerDetailMapper, never()).countByQuestionId(anyInt());
        verify(answerDetailMapper, never()).findTextAnswers(anyInt());
    }

    @Test
    @DisplayName("混合命中：只把未命中的题目送去批量查询（命中的不重复查）")
    void partialMiss_QueriesOnlyMissingQuestions() {
        givenThreeQuestions();
        when(statsCacheService.getQuestionStats(Q_SINGLE)).thenReturn(List.of(row(Q_SINGLE, "A", 1L)));
        when(statsCacheService.getQuestionStats(Q_MULTI)).thenReturn(null);
        when(statsCacheService.getQuestionTexts(Q_TEXT)).thenReturn(List.of("缓存"));
        when(answerDetailMapper.countByQuestionIds(anyList())).thenReturn(List.of());
        when(statsCacheService.getAnswerCount(SURVEY_ID)).thenReturn(1);

        answerService.getSurveyStatistics(survey());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Integer>> ids = ArgumentCaptor.forClass(List.class);
        verify(answerDetailMapper).countByQuestionIds(ids.capture());
        assertEquals(List.of(Q_MULTI), ids.getValue(), "只有未命中的多选题该被查");
        // 填空题命中缓存 → 不该走批量文本查询
        verify(answerDetailMapper, never()).findTextAnswersByQuestionIds(anyList(), anyInt());
    }

    @Test
    @DisplayName("★ 一份答卷都没有的题目：返回空列表而不是 null（否则前端 .length 会报错）")
    void questionsWithoutAnswers_ReturnEmptyList() {
        givenThreeQuestions();
        when(statsCacheService.getQuestionStats(anyInt())).thenReturn(null);
        when(statsCacheService.getQuestionTexts(anyInt())).thenReturn(null);
        when(statsCacheService.getAnswerCount(SURVEY_ID)).thenReturn(0);
        // 只有 Q_SINGLE 有数据：Q_MULTI 与 Q_TEXT 在 SQL 结果里不出现
        when(answerDetailMapper.countByQuestionIds(anyList()))
                .thenReturn(List.of(row(Q_SINGLE, "A", 1L)));
        when(answerDetailMapper.findTextAnswersByQuestionIds(anyList(), anyInt()))
                .thenReturn(List.of());

        SurveyStatisticsDTO dto = answerService.getSurveyStatistics(survey());

        SurveyStatisticsDTO.QuestionStats multi = dto.questions().get(1);
        assertNotNull(multi.chartData(), "多选题的 chartData 不能是 null");
        assertTrue(multi.chartData().isEmpty(), "没有任何答卷时应当是空列表");
        SurveyStatisticsDTO.QuestionStats text = dto.questions().get(2);
        assertNotNull(text.textAnswers(), "填空题的 textAnswers 不能是 null");
        assertTrue(text.textAnswers().isEmpty());
    }

    @Test
    @DisplayName("题型分流：选择题只给 chartData，填空题只给 textAnswers（另一个字段恒为 null）")
    void questionTypesAreRouted() {
        givenThreeQuestions();
        when(statsCacheService.getQuestionStats(anyInt())).thenReturn(List.of());
        when(statsCacheService.getQuestionTexts(anyInt())).thenReturn(List.of());
        when(statsCacheService.getAnswerCount(SURVEY_ID)).thenReturn(0);

        SurveyStatisticsDTO dto = answerService.getSurveyStatistics(survey());

        assertNotNull(dto.questions().get(0).chartData());
        assertNull(dto.questions().get(0).textAnswers());
        assertNotNull(dto.questions().get(1).chartData());
        assertNull(dto.questions().get(1).textAnswers());
        assertNull(dto.questions().get(2).chartData());
        assertNotNull(dto.questions().get(2).textAnswers());
        // 题目顺序必须与问卷一致（前端按这个顺序渲染）
        assertEquals(Q_SINGLE, dto.questions().get(0).question().getId());
        assertEquals(Q_TEXT, dto.questions().get(2).question().getId());
    }

    @Test
    @DisplayName("回源结果会写回缓存（下一次请求包括单题接口都能命中）")
    void refillCacheAfterMiss() {
        givenThreeQuestions();
        when(statsCacheService.getQuestionStats(anyInt())).thenReturn(null);
        when(statsCacheService.getQuestionTexts(anyInt())).thenReturn(null);
        when(statsCacheService.getAnswerCount(SURVEY_ID)).thenReturn(0);
        when(answerDetailMapper.countByQuestionIds(anyList()))
                .thenReturn(List.of(row(Q_SINGLE, "A", 1L)));
        when(answerDetailMapper.findTextAnswersByQuestionIds(anyList(), anyInt()))
                .thenReturn(List.of(row(Q_TEXT, "文本", null)));

        answerService.getSurveyStatistics(survey());

        verify(statsCacheService, atLeastOnce()).putQuestionStats(eq(Q_SINGLE), anyList());
        verify(statsCacheService).putQuestionStats(eq(Q_MULTI), anyList());
        verify(statsCacheService).putQuestionTexts(eq(Q_TEXT), anyList());
    }

    // ==================== 进阶题型（矩阵 / 排序） ====================

    private static Map<String, Object> matrixRow(Integer qid, String rowKey, String content, Long cnt) {
        Map<String, Object> m = row(qid, content, cnt);
        m.put("row_key", rowKey);
        return m;
    }

    private static Map<String, Object> rankingRow(Integer qid, String content, Long cnt, Double avgRank) {
        Map<String, Object> m = row(qid, content, cnt);
        m.put("avg_rank", avgRank);
        return m;
    }

    @Test
    @DisplayName("★ 矩阵题按 row_key 分组下发 row 字段；排序题走 AVG(rank_no) 的单独批量 SQL")
    void advancedTypes_matrixRowAndRankingAvg() {
        Question matrix = question(8, 8);
        matrix.setMatrixRows("[\"服务态度\",\"响应速度\"]");
        matrix.setMatrixCols("[\"满意\",\"不满意\"]");
        Question ranking = question(9, 9);
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(matrix, ranking));
        when(statsCacheService.getQuestionStats(anyInt())).thenReturn(null);
        when(statsCacheService.getAnswerCount(SURVEY_ID)).thenReturn(3);
        when(answerDetailMapper.countByQuestionIds(anyList()))
                .thenReturn(List.of(matrixRow(8, "服务态度", "满意", 2L)));
        when(answerDetailMapper.countRankingByQuestionIds(anyList()))
                .thenReturn(List.of(rankingRow(9, "甲", 3L, 1.5)));

        SurveyStatisticsDTO dto = answerService.getSurveyStatistics(survey());

        // 矩阵：chartData 里必须带 row（前端按行拼表格）
        Map<String, Object> matrixCell = dto.questions().get(0).chartData().get(0);
        assertEquals("服务态度", matrixCell.get("row"));
        assertEquals("满意", matrixCell.get("content"));
        assertEquals(2L, matrixCell.get("count"));

        // 排序：平均名次必须原样下发（前端按 avgRank 升序画条形图）
        Map<String, Object> rankingItem = dto.questions().get(1).chartData().get(0);
        assertEquals("甲", rankingItem.get("content"));
        assertEquals(1.5, rankingItem.get("avgRank"));

        // 两类各走各的批量 SQL：矩阵不能混进排序查询，反之亦然
        verify(answerDetailMapper).countByQuestionIds(List.of(8));
        verify(answerDetailMapper).countRankingByQuestionIds(List.of(9));
    }
}
