package com.survey.service;

import com.survey.common.BizException;
import com.survey.dto.FeedbackDetail;
import com.survey.dto.FeedbackView;
import com.survey.entity.Feedback;
import com.survey.entity.FeedbackMessage;
import com.survey.mapper.FeedbackMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 反馈 / 客服工单的业务边界（Mockito，不连库）。
 *
 * ★ 钉住：用户侧按工单归属校验、客服工单续开同一条、client_msg_id 幂等、
 *   关闭后不能再发、通知只发给相关人。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackServiceTest {

    private static final Integer USER = 100;
    private static final Integer ADMIN = 1;

    @Mock
    private FeedbackMapper feedbackMapper;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private FeedbackService service;

    private static Feedback feedback(int id, String status, Integer handledBy) {
        Feedback f = new Feedback();
        f.setId(id);
        f.setUserId(USER);
        f.setType("FEEDBACK");
        f.setSubject("问题");
        f.setStatus(status);
        f.setHandledBy(handledBy);
        f.setLastMessageId(10L);
        return f;
    }

    private static FeedbackView view(int id) {
        FeedbackView v = new FeedbackView();
        v.setId(id);
        v.setUserId(USER);
        v.setSubject("问题");
        v.setStatus(FeedbackService.STATUS_OPEN);
        v.setLastMessageId(10L);
        return v;
    }

    private void stubDetail(Integer id) {
        when(feedbackMapper.findById(id)).thenReturn(feedback(id, FeedbackService.STATUS_OPEN, null));
        when(feedbackMapper.findMyViewById(id, USER)).thenReturn(view(id));
        when(feedbackMapper.findMessages(eq(id), anyLong(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("★ 建单：写工单 + 首条消息 + 更新最后消息")
    void createFeedback() {
        when(feedbackMapper.insertFeedback(any())).thenAnswer(inv -> {
            ((Feedback) inv.getArgument(0)).setId(7);
            return 1;
        });
        when(feedbackMapper.insertMessage(any())).thenAnswer(inv -> {
            ((FeedbackMessage) inv.getArgument(0)).setId(11L);
            return 1;
        });
        stubDetail(7);

        FeedbackDetail detail = service.create(USER, "FEEDBACK", "问题", "内容", "cid-1");

        assertEquals(7, detail.getFeedback().getId());
        verify(feedbackMapper).insertFeedback(any());
        verify(feedbackMapper).insertMessage(any());
        verify(feedbackMapper).updateLastMessage(eq(7), eq(11L), anyString());
    }

    @Test
    @DisplayName("★ 客服工单续开：已有 OPEN 工单时只追消息，不新建")
    void serviceTicketReusesOpenTicket() {
        Feedback existing = feedback(9, FeedbackService.STATUS_OPEN, null);
        when(feedbackMapper.findOpenService(USER)).thenReturn(existing);
        when(feedbackMapper.insertMessage(any())).thenAnswer(inv -> {
            ((FeedbackMessage) inv.getArgument(0)).setId(12L);
            return 1;
        });
        stubDetail(9);

        service.create(USER, "SERVICE", "客服", "再问一句", "cid-2");

        verify(feedbackMapper, never()).insertFeedback(any());
        verify(feedbackMapper).insertMessage(any());
        verify(feedbackMapper).updateLastMessage(eq(9), eq(12L), anyString());
    }

    @Test
    @DisplayName("★ client_msg_id 幂等：重复提交不新增消息")
    void duplicateClientMsgIdIsIdempotent() {
        when(feedbackMapper.findById(9)).thenReturn(feedback(9, FeedbackService.STATUS_OPEN, null));
        FeedbackMessage existing = new FeedbackMessage();
        existing.setId(33L);
        when(feedbackMapper.findByClientMsgId(9, "cid-3")).thenReturn(existing);
        stubDetail(9);

        service.send(9, USER, "重复提交", "cid-3");

        verify(feedbackMapper, never()).insertMessage(any());
    }

    @Test
    @DisplayName("★ 内容 / 类型校验：空内容、超长、未知类型 → 400")
    void validatesInput() {
        assertThrows(BizException.class, () -> service.create(USER, "BAD", "主题", "内容", null));
        assertTrue(assertThrows(BizException.class,
                () -> service.create(USER, "FEEDBACK", "主题", "  ", null)).getMessage().contains("内容"));
        assertTrue(assertThrows(BizException.class,
                () -> service.create(USER, "FEEDBACK", "主题", "字".repeat(2001), null))
                .getMessage().contains("2000"));
        assertTrue(assertThrows(BizException.class,
                () -> service.create(USER, "FEEDBACK", " ", "内容", null)).getMessage().contains("主题"));
    }

    @Test
    @DisplayName("★ 归属：别人的工单详情一律 400，且文案不区分不存在/不属于")
    void otherUsersTicketIsRejected() {
        Feedback other = feedback(9, FeedbackService.STATUS_OPEN, null);
        other.setUserId(999);
        when(feedbackMapper.findById(9)).thenReturn(other);

        assertEquals("工单不存在或无权访问",
                assertThrows(BizException.class, () -> service.detail(9, USER, 0L)).getMessage());
    }

    @Test
    @DisplayName("★ 关闭后不能再发消息")
    void closedTicketRejectsMessages() {
        when(feedbackMapper.findById(9)).thenReturn(feedback(9, FeedbackService.STATUS_CLOSED, null));

        assertTrue(assertThrows(BizException.class,
                () -> service.send(9, USER, "还能发吗", null)).getMessage().contains("已关闭"));
        verify(feedbackMapper, never()).insertMessage(any());
    }

    @Test
    @DisplayName("★ 管理员回复：记处理人并通知用户")
    void adminReplyNotifiesUser() {
        when(feedbackMapper.findById(9)).thenReturn(feedback(9, FeedbackService.STATUS_OPEN, null));
        when(feedbackMapper.insertMessage(any())).thenAnswer(inv -> {
            ((FeedbackMessage) inv.getArgument(0)).setId(44L);
            return 1;
        });
        when(feedbackMapper.findDetailForAdmin(9)).thenReturn(view(9));
        when(feedbackMapper.findMessages(eq(9), anyLong(), anyInt())).thenReturn(List.of());

        service.adminReply(ADMIN, 9, "已处理", "cid-4");

        verify(feedbackMapper).assignHandler(9, ADMIN);
        verify(notificationService).send(eq(USER), eq("客服回复"), anyString(), eq("feedback"),
                eq("/feedback?ticket=9"));
    }

    @Test
    @DisplayName("★ 用户追消息：有处理人才通知管理员，没人接手只加红点")
    void userMessageNotifiesHandlerOnly() {
        stubDetail(9);
        when(feedbackMapper.findById(9)).thenReturn(feedback(9, FeedbackService.STATUS_OPEN, ADMIN));
        when(feedbackMapper.insertMessage(any())).thenAnswer(inv -> {
            ((FeedbackMessage) inv.getArgument(0)).setId(55L);
            return 1;
        });

        service.send(9, USER, "补充信息", "cid-5");
        verify(notificationService).send(eq(ADMIN), eq("新的反馈消息"), anyString(), eq("feedback"),
                eq("/admin/feedback?id=9"));

        reset(notificationService);
        when(feedbackMapper.findById(9)).thenReturn(feedback(9, FeedbackService.STATUS_OPEN, null));
        service.send(9, USER, "还没人接", "cid-6");
        verify(notificationService, never()).send(anyInt(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("★ 用户关闭工单：通知处理人")
    void closeByUserNotifiesHandler() {
        when(feedbackMapper.findById(9)).thenReturn(feedback(9, FeedbackService.STATUS_OPEN, ADMIN));
        when(feedbackMapper.close(9)).thenReturn(1);

        service.closeByUser(9, USER);

        verify(feedbackMapper).close(9);
        verify(notificationService).send(eq(ADMIN), eq("工单已关闭"), anyString(), eq("feedback"),
                eq("/admin/feedback?id=9"));
    }
}
