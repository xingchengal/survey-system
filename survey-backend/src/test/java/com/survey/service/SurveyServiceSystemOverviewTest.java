package com.survey.service;

import com.survey.entity.Survey;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyReviewMapper;
import com.survey.mapper.WalletMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端系统总览的聚合口径（Mockito，不连库）。
 *
 * ★ 这个接口是本轮新做的「运营驾驶舱」数据源：除原有总数外，
 *   新增模板数/回收站/待处理举报/钱包余额/今日新增/近 7 天趋势/TOP 5。
 *   全部是单条聚合 SQL，**不允许**按问卷 for 循环计数（N+1）。
 */
@ExtendWith(MockitoExtension.class)
class SurveyServiceSystemOverviewTest {

    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private AnswerMapper answerMapper;
    @Mock
    private SurveyReviewMapper surveyReviewMapper;
    @Mock
    private WalletMapper walletMapper;

    @InjectMocks
    private SurveyService surveyService;

    private static Survey survey(int id, String title, Integer status) {
        Survey s = new Survey();
        s.setId(id);
        s.setTitle(title);
        s.setStatus(status);
        s.setIsTemplate(0);
        return s;
    }

    private static Map<String, Object> row(String key, int id, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, id);
        m.put("cnt", (long) count);
        return m;
    }

    private void stubEmptyExtras() {
        when(surveyMapper.sumViewCount()).thenReturn(100);
        when(surveyMapper.countTemplates()).thenReturn(31);
        when(surveyMapper.countRecycled()).thenReturn(2);
        when(surveyMapper.countCreatedToday()).thenReturn(1);
        when(surveyReviewMapper.countPendingAll()).thenReturn(3);
        when(walletMapper.sumBalance()).thenReturn(88L);
        when(walletMapper.countMismatches()).thenReturn(0);
        when(answerMapper.countToday()).thenReturn(4);
        when(surveyMapper.countDailyCreated()).thenReturn(List.of());
        when(answerMapper.countDailyAll()).thenReturn(List.of());
    }

    @Test
    @DisplayName("★ 总览包含新能力计数、7 天趋势补齐、TOP 按答卷数降序")
    void overview_shouldExposeNewMetricsAndTopSurveys() {
        Survey a = survey(1, "问卷A", 1);
        Survey b = survey(2, "问卷B", 2);
        when(surveyMapper.findAll()).thenReturn(List.of(a, b));
        when(answerMapper.countBySurveyIds(List.of(1, 2)))
                .thenReturn(List.of(row("sid", 1, 9), row("sid", 2, 3)));
        stubEmptyExtras();

        Map<String, Object> result = surveyService.getSystemOverview();

        assertEquals(2L, result.get("total"), "total 是 long（StatusCounts.total）");
        assertEquals(9 + 3, result.get("responses"));
        assertEquals(31, result.get("templates"));
        assertEquals(2, result.get("recycled"));
        assertEquals(3, result.get("pendingReviews"));
        assertEquals(88L, result.get("walletBalance"));
        assertEquals(0, result.get("walletMismatches"));
        assertEquals(1, result.get("todaySurveys"));
        assertEquals(4, result.get("todayAnswers"));
        assertEquals(7, ((List<?>) result.get("trendDates")).size(),
                "近 7 天趋势必须补齐 7 个点（没有数据的天补 0）");
        assertEquals(0, ((List<?>) result.get("trendAnswers")).get(0));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) result.get("topSurveys");
        assertEquals(2, top.size());
        assertEquals(1, top.get(0).get("id"), "答卷数多的排在前面");
        assertEquals(9, top.get(0).get("responses"));
        assertEquals(2, top.get(1).get("id"));
    }

    @Test
    @DisplayName("没有任何问卷时不查答卷分组（避免 IN () 语法错误）")
    void overview_emptySurveys_shouldNotGroupAnswers() {
        when(surveyMapper.findAll()).thenReturn(List.of());
        stubEmptyExtras();

        Map<String, Object> result = surveyService.getSystemOverview();

        assertEquals(0L, result.get("total"));
        assertEquals(0, result.get("responses"));
        assertTrue(((List<?>) result.get("topSurveys")).isEmpty());
        verify(answerMapper, never()).countBySurveyIds(org.mockito.ArgumentMatchers.anyList());
    }
}
