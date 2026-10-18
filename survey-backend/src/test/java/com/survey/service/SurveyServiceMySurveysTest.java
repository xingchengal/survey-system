package com.survey.service;

import com.survey.entity.Survey;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.SurveyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「我的问卷」与看板最近问卷的**答卷数回填**（Mockito，不连库）。
 *
 * ★ 为什么要钉住：
 *   这个数字直接影响用户对"问卷有没有人填"的判断，必须来自真实批量查询；
 *   逐份 COUNT 是 N+1（/my 一次最多几十份），所以口径只允许有一条：
 *   `AnswerMapper.countBySurveyIds`。
 * ★ responseCount 是 Survey 上的**非数据库字段**，只有 Service 回填；
 *   新增/更新问卷的 SQL 里出现它才是 bug —— 这里只验证回填行为。
 */
@ExtendWith(MockitoExtension.class)
class SurveyServiceMySurveysTest {

    private static final Integer USER_ID = 7;

    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private AnswerMapper answerMapper;

    @InjectMocks
    private SurveyService surveyService;

    private static Survey survey(int id) {
        Survey s = new Survey();
        s.setId(id);
        s.setUserId(USER_ID);
        s.setTitle("问卷" + id);
        s.setStatus(1);
        s.setIsTemplate(0);
        return s;
    }

    private static Map<String, Object> countRow(int surveyId, int count) {
        Map<String, Object> row = new HashMap<>();
        row.put("sid", surveyId);
        row.put("cnt", (long) count);
        return row;
    }

    @Test
    @DisplayName("★ /my 列表：一次批量查询回填每份问卷的答卷数，没答卷的补 0")
    void listByUserId_shouldFillResponseCounts() {
        Survey a = survey(1);
        Survey b = survey(2);
        when(surveyMapper.findByUserId(USER_ID)).thenReturn(List.of(a, b));
        when(answerMapper.countBySurveyIds(List.of(1, 2)))
                .thenReturn(List.of(countRow(1, 5)));

        List<Survey> list = surveyService.listByUserId(USER_ID);

        assertEquals(2, list.size());
        assertEquals(5, list.get(0).getResponseCount());
        assertEquals(0, list.get(1).getResponseCount(), "没有答卷的问卷必须补 0，而不是 null");
    }

    @Test
    @DisplayName("空列表不查答卷表（避免 IN () 语法错误 / 无谓 SQL）")
    void listByUserId_empty_shouldNotQueryAnswers() {
        when(surveyMapper.findByUserId(USER_ID)).thenReturn(List.of());

        List<Survey> list = surveyService.listByUserId(USER_ID);

        assertEquals(0, list.size());
        verifyNoInteractions(answerMapper);
    }

    @Test
    @DisplayName("看板「最近问卷」也带上答卷数（复用同一次批量查询）")
    @SuppressWarnings("unchecked")
    void getUserStatistics_recentShouldCarryResponseCount() {
        Survey a = survey(1);
        Survey b = survey(2);
        // 给不同的创建时间：recent 是按 createdTime 倒序的，两条都为 null 时顺序不稳定
        a.setCreatedTime(LocalDateTime.of(2026, 10, 2, 10, 0));
        b.setCreatedTime(LocalDateTime.of(2026, 10, 1, 10, 0));
        when(surveyMapper.findByUserId(USER_ID)).thenReturn(List.of(a, b));
        when(answerMapper.countBySurveyIds(List.of(1, 2)))
                .thenReturn(List.of(countRow(1, 3)));
        when(answerMapper.countDailyByUserId(USER_ID)).thenReturn(List.of());

        Map<String, Object> result = surveyService.getUserStatistics(USER_ID);
        List<Survey> recent = (List<Survey>) result.get("recent");

        assertEquals(3, recent.get(0).getResponseCount());
        assertEquals(0, recent.get(1).getResponseCount());
        assertNull(result.get("error"), "统计接口不应有错误字段");
    }
}
