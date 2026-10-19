package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.Survey;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyReviewMapper;
import com.survey.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 举报提交的业务校验：只有"已发布 + 不是自己 + 理由合法 + 没有待处理举报"才写库。
 * ★ 这几条漏一条的后果都是脏数据或刷屏，而不是编译错误。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SurveyReviewServiceTest {

    @Mock
    private SurveyReviewMapper reviewMapper;
    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private SurveyReviewService service;

    private static final Integer OWNER = 466;
    private static final Integer REPORTER = 585;

    private static Survey survey(Integer status) {
        Survey s = new Survey();
        s.setId(874);
        s.setUserId(OWNER);
        s.setStatus(status);
        return s;
    }

    @Test
    @DisplayName("正常举报：写入 survey_id / reporter_id / 去空白的理由")
    void reportInserts() {
        when(userMapper.findStaffIdsByPermission("review:handle")).thenReturn(java.util.List.of(1, 2));

        service.report(survey(1), REPORTER, "  内容违规  ");

        verify(reviewMapper).insert(874, REPORTER, "内容违规");
        // ★ 只通知有 review:handle 的 staff，不是"所有管理员"
        verify(notificationService).send(eq(1), eq("新的举报"), anyString(), eq("review"), eq("/admin/reviews"));
        verify(notificationService).send(eq(2), eq("新的举报"), anyString(), eq("review"), eq("/admin/reviews"));
    }

    @Test
    @DisplayName("★ 举报通知按权限点取人：名单为空时不发通知")
    void reportNotifiesOnlyStaffWithReviewPermission() {
        when(userMapper.findStaffIdsByPermission("review:handle")).thenReturn(java.util.List.of());

        service.report(survey(1), REPORTER, "内容违规");

        verify(notificationService, never()).send(anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★ 不能举报非已发布问卷 / 自己的问卷")
    void rejectsDraftAndOwn() {
        assertEquals("只能举报已发布的问卷", assertThrows(BizException.class,
                () -> service.report(survey(0), REPORTER, "x")).getMessage());
        assertEquals("只能举报已发布的问卷", assertThrows(BizException.class,
                () -> service.report(survey(2), REPORTER, "x")).getMessage());
        assertEquals("不能举报自己的问卷", assertThrows(BizException.class,
                () -> service.report(survey(1), OWNER, "x")).getMessage());
        verify(reviewMapper, never()).insert(anyInt(), anyInt(), anyString());
    }

    @Test
    @DisplayName("★ 理由必填、不超 500 字（与列宽一致）")
    void validatesReason() {
        assertEquals("请填写举报理由", assertThrows(BizException.class,
                () -> service.report(survey(1), REPORTER, "   ")).getMessage());
        String tooLong = "字".repeat(501);
        assertTrue(assertThrows(BizException.class,
                () -> service.report(survey(1), REPORTER, tooLong)).getMessage().contains("500"));
        verify(reviewMapper, never()).insert(anyInt(), anyInt(), anyString());
    }

    @Test
    @DisplayName("★ 同一人重复举报（仍有待处理）→ 400，不新增记录")
    void rejectsDuplicatePending() {
        when(reviewMapper.countPending(874, REPORTER)).thenReturn(1);
        assertEquals("你对该问卷的举报还在处理中，请等待结果", assertThrows(BizException.class,
                () -> service.report(survey(1), REPORTER, "x")).getMessage());
        verify(reviewMapper, never()).insert(anyInt(), anyInt(), anyString());
    }
    // ==================== 审核（H1-3）====================

    @Test
    @DisplayName("★ 通过并下架：举报改 APPROVED、问卷 lifecycle=SUSPENDED、给作者与举报人都发通知")
    void handleSuspend() {
        when(reviewMapper.findById(9)).thenReturn(new java.util.HashMap<>(java.util.Map.of(
                "id", 9, "surveyId", 874, "reporterId", REPORTER, "status", "PENDING")));
        when(reviewMapper.handle(9, "APPROVED", "SUSPEND", 1)).thenReturn(1);
        when(surveyMapper.findById(874)).thenReturn(survey(1));

        service.handle(9, 1, "SUSPEND");

        verify(surveyMapper).updateLifecycle(874, "SUSPENDED");
        verify(notificationService).send(eq(OWNER), eq("问卷被下架"), anyString(), eq("review"), anyString());
        verify(notificationService).send(eq(REPORTER), eq("举报处理结果"), anyString(), eq("review"), anyString());
    }

    @Test
    @DisplayName("★ 驳回：不动 lifecycle，只通知举报人")
    void handleIgnore() {
        when(reviewMapper.findById(9)).thenReturn(new java.util.HashMap<>(java.util.Map.of(
                "id", 9, "surveyId", 874, "reporterId", REPORTER, "status", "PENDING")));
        when(reviewMapper.handle(9, "REJECTED", "IGNORE", 1)).thenReturn(1);
        when(surveyMapper.findById(874)).thenReturn(survey(1));

        service.handle(9, 1, "IGNORE");

        verify(surveyMapper, never()).updateLifecycle(anyInt(), anyString());
        verify(notificationService).send(eq(REPORTER), eq("举报处理结果"), anyString(), eq("review"), anyString());
    }

    @Test
    @DisplayName("★ 幂等：已处理的举报再处理 → 400，且不重复下架/通知")
    void handleIsIdempotent() {
        when(reviewMapper.findById(9)).thenReturn(new java.util.HashMap<>(java.util.Map.of(
                "id", 9, "surveyId", 874, "reporterId", REPORTER, "status", "APPROVED")));
        assertEquals("该举报已处理过", assertThrows(BizException.class,
                () -> service.handle(9, 1, "SUSPEND")).getMessage());
        verify(reviewMapper, never()).handle(anyInt(), anyString(), anyString(), anyInt());
        verify(notificationService, never()).send(anyInt(), anyString(), anyString(), anyString(), anyString());

        // 并发：SQL 的 WHERE status='PENDING' 影响 0 行也算"已处理"
        when(reviewMapper.findById(10)).thenReturn(new java.util.HashMap<>(java.util.Map.of(
                "id", 10, "surveyId", 874, "reporterId", REPORTER, "status", "PENDING")));
        when(reviewMapper.handle(10, "APPROVED", "SUSPEND", 1)).thenReturn(0);
        assertThrows(BizException.class, () -> service.handle(10, 1, "SUSPEND"));
        verify(surveyMapper, never()).updateLifecycle(anyInt(), anyString());
    }

    @Test
    @DisplayName("★ 非法动作 / 举报不存在 → 400")
    void handleRejectsBadInput() {
        assertTrue(assertThrows(BizException.class,
                () -> service.handle(9, 1, "DELETE")).getMessage().contains("SUSPEND"));
        when(reviewMapper.findById(99)).thenReturn(null);
        assertEquals("举报不存在", assertThrows(BizException.class,
                () -> service.handle(99, 1, "SUSPEND")).getMessage());
    }
}
