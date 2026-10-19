package com.survey.service;

import com.survey.common.Permission;
import com.survey.entity.OperationLog;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.OperationLogMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyReviewMapper;
import com.survey.mapper.WalletMapper;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理端系统总览的聚合口径与权限裁剪（Mockito，不连库）。
 *
 * ★ 总览对 staff 开放：subadmin 只拿到自己模块权限对应的字段，
 *   没权限的模块连查询都不做；system:view 视为全站视图（与超管同口径）。
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
    @Mock
    private OperationLogMapper operationLogMapper;

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

    private static OperationLog log(String username, String operation) {
        OperationLog l = new OperationLog();
        l.setId(1);
        l.setUsername(username);
        l.setOperation(operation);
        l.setCreateTime(LocalDateTime.of(2026, 10, 19, 12, 0));
        return l;
    }

    private static Map<String, Object> row(String key, int id, int count) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, id);
        m.put("cnt", (long) count);
        return m;
    }

    /** 全站视图：问卷 / 模板 / 待审 / 钱包 / 最近动态 全部参与查询。 */
    private void stubFullExtras() {
        when(surveyMapper.sumViewCount()).thenReturn(100);
        when(surveyMapper.countTemplates()).thenReturn(31);
        when(surveyMapper.countRecycled()).thenReturn(2);
        when(surveyMapper.countCreatedToday()).thenReturn(1);
        when(answerMapper.countToday()).thenReturn(4);
        when(surveyReviewMapper.countPendingAll()).thenReturn(3);
        when(walletMapper.sumBalance()).thenReturn(88L);
        when(walletMapper.countMismatches()).thenReturn(0);
        when(operationLogMapper.findRecent(5)).thenReturn(List.of(log("admin1", "登录")));
    }

    @Test
    @DisplayName("★ 全部权限（超管）：问卷 / 模板 / 待审 / 钱包 / 最近动态 一次返回")
    void overview_allPermissions_shouldReturnAllModules() {
        Survey a = survey(1, "问卷A", 1);
        Survey b = survey(2, "问卷B", 2);
        when(surveyMapper.findNormal()).thenReturn(List.of(a, b));
        when(answerMapper.countBySurveyIds(List.of(1, 2)))
                .thenReturn(List.of(row("sid", 1, 9), row("sid", 2, 3)));
        stubFullExtras();

        Map<String, Object> result = surveyService.getSystemOverview(Permission.ALL);

        assertEquals(2L, result.get("total"), "total 是 long（StatusCounts.total）");
        assertEquals(9 + 3, result.get("responses"));
        assertEquals(31, result.get("templates"));
        assertEquals(2, result.get("recycled"));
        assertEquals(3, result.get("pendingReviews"));
        assertEquals(88L, result.get("walletBalance"));
        assertEquals(0, result.get("walletMismatches"));
        assertEquals(1, result.get("todaySurveys"));
        assertEquals(4, result.get("todayAnswers"));
        assertFalse(result.containsKey("trendDates"), "趋势归系统统计页，总览不再返回");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> activities = (List<Map<String, Object>>) result.get("recentActivities");
        assertEquals(1, activities.size());
        assertEquals("登录", activities.get(0).get("operation"));
        assertEquals("2026-10-19 12:00:00", activities.get(0).get("createTime"));
    }

    @Test
    @DisplayName("★ 只有 survey:manage：只返回问卷字段，不查模板/待审/钱包/日志")
    void overview_surveyManageOnly_shouldHideOtherModules() {
        when(surveyMapper.findNormal()).thenReturn(List.of(survey(1, "问卷A", 1)));
        when(answerMapper.countBySurveyIds(List.of(1))).thenReturn(List.of());
        when(surveyMapper.sumViewCount()).thenReturn(5);
        when(surveyMapper.countRecycled()).thenReturn(0);
        when(surveyMapper.countCreatedToday()).thenReturn(0);
        when(answerMapper.countToday()).thenReturn(0);

        Map<String, Object> result = surveyService.getSystemOverview(Set.of(Permission.SURVEY_MANAGE));

        assertEquals(1L, result.get("total"));
        assertEquals(0, result.get("responses"));
        assertFalse(result.containsKey("templates"));
        assertFalse(result.containsKey("pendingReviews"));
        assertFalse(result.containsKey("walletBalance"));
        assertFalse(result.containsKey("recentActivities"));
        verify(surveyMapper, never()).countTemplates();
        verify(surveyReviewMapper, never()).countPendingAll();
        verify(walletMapper, never()).sumBalance();
        verify(operationLogMapper, never()).findRecent(anyInt());
    }

    @Test
    @DisplayName("★ 没有任何模块权限：返回空对象，且一条统计 SQL 都不发")
    void overview_noPermission_shouldReturnEmptyWithoutQueries() {
        Map<String, Object> result = surveyService.getSystemOverview(Set.of());

        assertTrue(result.isEmpty());
        verifyNoInteractions(surveyMapper, answerMapper, surveyReviewMapper, walletMapper, operationLogMapper);
    }

    @Test
    @DisplayName("★ 只有 system:view：总览不返回任何业务字段（该权限只解锁统计页资源模块）")
    void overview_systemViewOnly_shouldReturnEmpty() {
        Map<String, Object> result = surveyService.getSystemOverview(Set.of(Permission.SYSTEM_VIEW));

        assertTrue(result.isEmpty());
        verifyNoInteractions(surveyMapper, answerMapper, surveyReviewMapper, walletMapper, operationLogMapper);
    }

    @Test
    @DisplayName("没有任何问卷时不查答卷分组（避免 IN () 语法错误）")
    void overview_emptySurveys_shouldNotGroupAnswers() {
        when(surveyMapper.findNormal()).thenReturn(List.of());
        stubFullExtras();

        Map<String, Object> result = surveyService.getSystemOverview(Permission.ALL);

        assertEquals(0L, result.get("total"));
        assertEquals(0, result.get("responses"));
        verify(answerMapper, never()).countBySurveyIds(anyList());
    }
}
