package com.survey.service;

import com.survey.common.Permission;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.ErrorLogMapper;
import com.survey.mapper.OperationLogMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyReviewMapper;
import com.survey.mapper.SystemResourceMapper;
import com.survey.mapper.TemplateCategoryMapper;
import com.survey.mapper.UserMapper;
import com.survey.mapper.WalletMapper;
import com.survey.mapper.WalletTransactionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 系统统计的模块裁剪与聚合口径（Mockito，不连库）。
 *
 * ★ 这里钉的是「数据边界」：system:view 拿全站模块；只有某个模块权限时，
 *   别的模块既不返回、也不查库；资源维度只给全站视图。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemStatisticsServiceTest {

    @Mock
    private SurveyService surveyService;
    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private AnswerMapper answerMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private SurveyReviewMapper surveyReviewMapper;
    @Mock
    private WalletMapper walletMapper;
    @Mock
    private WalletTransactionMapper walletTransactionMapper;
    @Mock
    private OperationLogMapper operationLogMapper;
    @Mock
    private ErrorLogMapper errorLogMapper;
    @Mock
    private TemplateCategoryMapper templateCategoryMapper;
    @Mock
    private SystemResourceMapper systemResourceMapper;

    @InjectMocks
    private SystemStatisticsService service;

    private static Survey survey(int id, Integer status) {
        Survey s = new Survey();
        s.setId(id);
        s.setTitle("问卷" + id);
        s.setStatus(status);
        s.setIsTemplate(0);
        return s;
    }

    private void stubSurvey() {
        when(surveyService.listNormal()).thenReturn(List.of(survey(1, 1), survey(2, 2)));
        when(surveyService.countByStatus(anyList()))
                .thenReturn(new SurveyService.StatusCounts(2L, 1L, 0L, 1L));
        when(surveyService.countResponsesBySurveyIds(anyList())).thenReturn(Map.of(1, 9, 2, 3));
        when(surveyMapper.sumViewCount()).thenReturn(100);
        when(surveyMapper.countRecycled()).thenReturn(2);
        when(surveyMapper.countCreatedToday()).thenReturn(1);
        when(surveyMapper.countTemplates()).thenReturn(31);
        when(answerMapper.countDailyAll()).thenReturn(List.of());
        when(answerMapper.countToday()).thenReturn(4);
    }

    private void stubOtherModules() {
        when(userMapper.countNormalUsers()).thenReturn(10);
        when(userMapper.countAdmins()).thenReturn(2);
        when(userMapper.countActiveAdmins()).thenReturn(2);
        when(userMapper.countDisabled()).thenReturn(1);
        when(userMapper.countCreatedToday()).thenReturn(1);
        when(userMapper.countDailyCreated()).thenReturn(List.of());

        when(templateCategoryMapper.countAll()).thenReturn(5);
        when(surveyMapper.findTopTemplates(5))
                .thenReturn(List.of(Map.of("title", "满意度模板", "useCount", 3)));

        when(surveyReviewMapper.countByStatus()).thenReturn(List.of(
                Map.of("status", "PENDING", "cnt", 2L),
                Map.of("status", "APPROVED", "cnt", 3L),
                Map.of("status", "REJECTED", "cnt", 1L)));

        when(walletMapper.sumBalance()).thenReturn(88L);
        when(walletMapper.countWallets()).thenReturn(4);
        when(walletMapper.countMismatches()).thenReturn(0);
        when(walletTransactionMapper.sumToday())
                .thenReturn(Map.of("income", 10L, "expense", 5L, "cnt", 3L));
        when(walletTransactionMapper.sumByType()).thenReturn(List.of());

        when(operationLogMapper.count()).thenReturn(100);
        when(operationLogMapper.countToday()).thenReturn(5);
        when(operationLogMapper.countDaily()).thenReturn(List.of());
        when(errorLogMapper.count()).thenReturn(7);
        when(errorLogMapper.countToday()).thenReturn(0);

        when(systemResourceMapper.sumDatabaseBytes()).thenReturn(1024L);
        when(systemResourceMapper.countTables()).thenReturn(23);
        when(systemResourceMapper.sumSnapshotBytes()).thenReturn(512L);
        when(systemResourceMapper.countTableRows())
                .thenReturn(List.of(Map.of("tableName", "t_user", "rowCount", 10L)));
    }

    @Test
    @DisplayName("★ 全部权限（超管）：七个模块一次返回，聚合口径正确")
    void statistics_allPermissions_returnsAllModules() {
        stubSurvey();
        stubOtherModules();

        Map<String, Object> result = service.getStatistics(Permission.ALL);

        assertEquals(Set.of("survey", "user", "template", "review", "wallet", "log", "resource"),
                result.keySet());

        @SuppressWarnings("unchecked")
        Map<String, Object> survey = (Map<String, Object>) result.get("survey");
        assertEquals(2L, survey.get("total"));
        assertEquals(9 + 3, survey.get("responses"));
        assertEquals(31, survey.get("templates"));
        assertEquals(7, ((List<?>) survey.get("trendDates")).size());

        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) result.get("user");
        assertEquals(12, user.get("total"));

        @SuppressWarnings("unchecked")
        Map<String, Object> review = (Map<String, Object>) result.get("review");
        assertEquals(2, review.get("pending"));
        assertEquals(6, review.get("total"));
        assertEquals(67L, review.get("handledRate"));

        @SuppressWarnings("unchecked")
        Map<String, Object> wallet = (Map<String, Object>) result.get("wallet");
        assertEquals(10L, wallet.get("todayIncome"));

        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) result.get("resource");
        assertEquals(10L, resource.get("totalRows"));
        assertEquals(23, resource.get("tableCount"));
    }

    @Test
    @DisplayName("★ 只有 survey:manage：只返回问卷模块，别的模块连查询都不做")
    void statistics_surveyManageOnly_hidesOtherModules() {
        stubSurvey();

        Map<String, Object> result = service.getStatistics(Set.of(Permission.SURVEY_MANAGE));

        assertEquals(Set.of("survey"), result.keySet());
        verifyNoInteractions(userMapper, surveyReviewMapper, walletMapper,
                walletTransactionMapper, operationLogMapper, errorLogMapper,
                systemResourceMapper, templateCategoryMapper);
    }

    @Test
    @DisplayName("★ 没有任何模块权限：返回空对象，零查询")
    void statistics_noPermission_returnsEmpty() {
        Map<String, Object> result = service.getStatistics(Set.of());

        assertTrue(result.isEmpty());
        verifyNoInteractions(surveyService, surveyMapper, answerMapper, userMapper,
                surveyReviewMapper, walletMapper, walletTransactionMapper,
                operationLogMapper, errorLogMapper, systemResourceMapper,
                templateCategoryMapper);
    }

    @Test
    @DisplayName("★ 只有 system:view：只返回资源模块，不返回任何业务模块")
    void statistics_systemViewOnly_returnsResourceOnly() {
        when(systemResourceMapper.sumDatabaseBytes()).thenReturn(1024L);
        when(systemResourceMapper.countTables()).thenReturn(23);
        when(systemResourceMapper.sumSnapshotBytes()).thenReturn(512L);
        when(systemResourceMapper.countTableRows())
                .thenReturn(List.of(Map.of("tableName", "t_user", "rowCount", 10L)));

        Map<String, Object> result = service.getStatistics(Set.of(Permission.SYSTEM_VIEW));

        assertEquals(Set.of("resource"), result.keySet());
        verifyNoInteractions(surveyService, answerMapper, userMapper, surveyReviewMapper,
                walletMapper, walletTransactionMapper, operationLogMapper, errorLogMapper,
                templateCategoryMapper);
    }

    @Test
    @DisplayName("★ 资源维度只给 system:view：有 user:manage 也拿不到")
    void statistics_resourceRequiresSystemView() {
        when(userMapper.countNormalUsers()).thenReturn(10);
        when(userMapper.countAdmins()).thenReturn(2);
        when(userMapper.countActiveAdmins()).thenReturn(2);
        when(userMapper.countDisabled()).thenReturn(1);
        when(userMapper.countCreatedToday()).thenReturn(1);
        when(userMapper.countDailyCreated()).thenReturn(List.of());

        Map<String, Object> result = service.getStatistics(Set.of(Permission.USER_MANAGE));

        assertEquals(Set.of("user"), result.keySet());
        verify(systemResourceMapper, never()).sumDatabaseBytes();
        verify(systemResourceMapper, never()).countTableRows();
    }
}
