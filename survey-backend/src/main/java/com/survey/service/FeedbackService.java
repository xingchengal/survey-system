package com.survey.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.BizException;
import com.survey.common.PageParams;
import com.survey.common.PageResult;
import com.survey.dto.FeedbackDetail;
import com.survey.dto.FeedbackView;
import com.survey.entity.Feedback;
import com.survey.entity.FeedbackMessage;
import com.survey.mapper.FeedbackMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 反馈 / 客服工单：用户侧建单、追消息、已读、关闭；管理侧列表、详情、回复、关闭。
 *
 * ★ 权限边界：用户侧每条读写都按 `feedback.userId == 当前用户` 校验（不只看登录）；
 *   管理侧由 Controller 的 `@RequirePermission(feedback:handle)` 把关，不复用 review:handle。
 * ★ 同一用户只保留一个 OPEN SERVICE 工单：数据库生成列唯一索引兜底，Service 发现已有则续开同一条。
 * ★ 消息发送用 client_msg_id 幂等（唯一键冲突时取回已有那条），前端重试不会产生重复气泡。
 * ★ 通知只发给相关人：用户发消息且有 handled_by → 通知该管理员；管理员回复 → 通知用户；无处理人只加红点。
 */
@Service
public class FeedbackService {

    public static final String TYPE_FEEDBACK = "FEEDBACK";
    public static final String TYPE_SERVICE = "SERVICE";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    private static final int MAX_SUBJECT = 200;
    private static final int MAX_CONTENT = 2000;
    private static final int MAX_CLIENT_MSG_ID = 64;
    /** 单次最多返回 50 条；多取一条判断 hasMore。 */
    private static final int PAGE_LIMIT = 50;
    private static final int PREVIEW_LEN = 120;

    @Autowired
    private FeedbackMapper feedbackMapper;

    @Autowired
    private NotificationService notificationService;

    /** 建单：SERVICE 已有未关闭工单时续开同一条（把这次输入作为追消息）。 */
    @Transactional
    public FeedbackDetail create(Integer userId, String type, String subject, String content, String clientMsgId) {
        String normalizedType = normalizeType(type);
        String text = requireContent(content);
        if (TYPE_SERVICE.equals(normalizedType)) {
            Feedback existing = feedbackMapper.findOpenService(userId);
            if (existing != null) {
                appendMessage(existing, userId, "user", text, clientMsgId);
                return userDetail(existing.getId(), userId, 0L);
            }
        }
        Feedback feedback = new Feedback();
        feedback.setUserId(userId);
        feedback.setType(normalizedType);
        feedback.setSubject(normalizeSubject(subject));
        feedbackMapper.insertFeedback(feedback);
        appendMessage(feedback, userId, "user", text, clientMsgId);
        return userDetail(feedback.getId(), userId, 0L);
    }

    /** 我的工单列表（最多 100 条）。 */
    public List<FeedbackView> myList(Integer userId) {
        return feedbackMapper.findMyList(userId);
    }

    /** 用户视角详情：afterId 增量；进入详情即把用户已读推到最新。 */
    public FeedbackDetail detail(Integer feedbackId, Integer userId, Long afterId) {
        return userDetail(feedbackId, userId, afterId == null ? 0L : Math.max(0L, afterId));
    }

    /** 用户追消息。 */
    @Transactional
    public FeedbackDetail send(Integer feedbackId, Integer userId, String content, String clientMsgId) {
        Feedback feedback = requireOwn(feedbackId, userId);
        requireOpen(feedback);
        appendMessage(feedback, userId, "user", requireContent(content), clientMsgId);
        return userDetail(feedbackId, userId, 0L);
    }

    /** 标记已读：只推进水位，不删消息。 */
    public void markUserRead(Integer feedbackId, Integer userId) {
        Feedback feedback = requireOwn(feedbackId, userId);
        if (feedback.getLastMessageId() != null) {
            feedbackMapper.updateUserRead(feedbackId, userId, feedback.getLastMessageId());
        }
    }

    /** 用户关闭自己的工单（只关闭、不删除）。 */
    @Transactional
    public void closeByUser(Integer feedbackId, Integer userId) {
        Feedback feedback = requireOwn(feedbackId, userId);
        requireOpen(feedback);
        if (feedbackMapper.close(feedbackId) <= 0) {
            throw new BizException("工单已关闭");
        }
        if (feedback.getHandledBy() != null && !feedback.getHandledBy().equals(userId)) {
            notificationService.send(feedback.getHandledBy(), "工单已关闭",
                    "用户关闭了工单《" + feedback.getSubject() + "》", "feedback",
                    adminTicketLink(feedback));
        }
    }

    /** 未读数：普通用户只算自己的；有 feedback:handle 的 staff 再带管理端总数。 */
    public Map<String, Object> unread(Integer userId, boolean includeAdmin) {
        Map<String, Object> result = new HashMap<>();
        result.put("userUnread", feedbackMapper.countUserUnread(userId));
        if (includeAdmin) {
            result.put("adminUnread", feedbackMapper.countAdminUnread());
        }
        return result;
    }

    // ==================== 管理端 ====================

    /** 管理端列表（有 feedback:handle 即可看全部工单）。 */
    public PageResult<FeedbackView> adminPage(String type, String status, int pageNum, int pageSize) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        List<FeedbackView> list = feedbackMapper.findForAdmin(blankToNull(type), blankToNull(status));
        PageInfo<FeedbackView> info = new PageInfo<>(list);
        return new PageResult<>(info.getTotal(), info.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /** 管理端详情：进入即把管理员已读推到最新；afterId 增量。 */
    public FeedbackDetail adminDetail(Integer feedbackId, Long afterId) {
        FeedbackView view = feedbackMapper.findDetailForAdmin(feedbackId);
        if (view == null) {
            throw new BizException("工单不存在");
        }
        Long cursor = afterId == null ? 0L : Math.max(0L, afterId);
        List<FeedbackMessage> messages = feedbackMapper.findMessages(feedbackId, cursor, PAGE_LIMIT + 1);
        boolean hasMore = messages.size() > PAGE_LIMIT;
        if (hasMore) {
            messages = messages.subList(0, PAGE_LIMIT);
        }
        if (view.getLastMessageId() != null) {
            feedbackMapper.updateAdminRead(feedbackId, view.getLastMessageId());
        }
        view.setUnread(0);
        FeedbackDetail detail = new FeedbackDetail();
        detail.setFeedback(view);
        detail.setMessages(messages);
        detail.setHasMore(hasMore);
        return detail;
    }

    /** 管理员回复：写入 admin 消息并把自己记为处理人。 */
    @Transactional
    public FeedbackDetail adminReply(Integer adminId, Integer feedbackId, String content, String clientMsgId) {
        Feedback feedback = feedbackMapper.findById(feedbackId);
        if (feedback == null) {
            throw new BizException("工单不存在");
        }
        requireOpen(feedback);
        appendMessage(feedback, adminId, "admin", requireContent(content), clientMsgId);
        feedbackMapper.assignHandler(feedbackId, adminId);
        return adminDetail(feedbackId, 0L);
    }

    /** 管理员关闭工单。 */
    @Transactional
    public void closeByAdmin(Integer adminId, Integer feedbackId) {
        Feedback feedback = feedbackMapper.findById(feedbackId);
        if (feedback == null) {
            throw new BizException("工单不存在");
        }
        requireOpen(feedback);
        feedbackMapper.assignHandler(feedbackId, adminId);
        if (feedbackMapper.close(feedbackId) <= 0) {
            throw new BizException("工单已关闭");
        }
        notificationService.send(feedback.getUserId(), "工单已关闭",
                "管理员关闭了工单《" + feedback.getSubject() + "》", "feedback",
                userTicketLink(feedback));
    }

    // ==================== 内部 ====================

    private FeedbackDetail userDetail(Integer feedbackId, Integer userId, Long afterId) {
        Feedback feedback = requireOwn(feedbackId, userId);
        FeedbackView view = feedbackMapper.findMyViewById(feedbackId, userId);
        if (view == null) {
            throw new BizException("工单不存在或无权访问");
        }
        List<FeedbackMessage> messages = feedbackMapper.findMessages(feedbackId, afterId, PAGE_LIMIT + 1);
        boolean hasMore = messages.size() > PAGE_LIMIT;
        if (hasMore) {
            messages = messages.subList(0, PAGE_LIMIT);
        }
        if (feedback.getLastMessageId() != null) {
            feedbackMapper.updateUserRead(feedbackId, userId, feedback.getLastMessageId());
        }
        view.setUnread(0);
        FeedbackDetail detail = new FeedbackDetail();
        detail.setFeedback(view);
        detail.setMessages(messages);
        detail.setHasMore(hasMore);
        return detail;
    }

    private Feedback requireOwn(Integer feedbackId, Integer userId) {
        if (feedbackId == null) {
            throw new BizException("工单不存在或无权访问");
        }
        Feedback feedback = feedbackMapper.findById(feedbackId);
        // 不存在与不属于自己用同一句文案：不暴露别人的工单 id 是否有效
        if (feedback == null || !userId.equals(feedback.getUserId())) {
            throw new BizException("工单不存在或无权访问");
        }
        return feedback;
    }

    private void requireOpen(Feedback feedback) {
        if (!STATUS_OPEN.equals(feedback.getStatus())) {
            throw new BizException("工单已关闭，不能再发消息");
        }
    }

    private FeedbackMessage appendMessage(Feedback feedback, Integer senderId, String role,
                                          String content, String clientMsgId) {
        String cid = normalizeClientMsgId(clientMsgId);
        if (cid != null) {
            FeedbackMessage existing = feedbackMapper.findByClientMsgId(feedback.getId(), cid);
            if (existing != null) {
                return existing;
            }
        }
        FeedbackMessage msg = new FeedbackMessage();
        msg.setFeedbackId(feedback.getId());
        msg.setSenderId(senderId);
        msg.setSenderRole(role);
        msg.setContent(content);
        msg.setClientMsgId(cid);
        try {
            feedbackMapper.insertMessage(msg);
        } catch (DuplicateKeyException e) {
            // 并发重试：唯一键已存在，取回已有那条（幂等）
            if (cid != null) {
                FeedbackMessage existing = feedbackMapper.findByClientMsgId(feedback.getId(), cid);
                if (existing != null) {
                    return existing;
                }
            }
            throw e;
        }
        feedbackMapper.updateLastMessage(feedback.getId(), msg.getId(), preview(content));
        notifyPeer(feedback, senderId, role, content);
        return msg;
    }

    private void notifyPeer(Feedback feedback, Integer senderId, String role, String content) {
        if ("user".equals(role)) {
            // 有处理人就精准通知；没人接手时只靠管理端红点，不群发
            if (feedback.getHandledBy() != null && !feedback.getHandledBy().equals(senderId)) {
                notificationService.send(feedback.getHandledBy(), "新的反馈消息",
                        "工单《" + feedback.getSubject() + "》有新的用户消息：" + preview(content),
                        "feedback", adminTicketLink(feedback));
            }
        } else if (feedback.getUserId() != null && !feedback.getUserId().equals(senderId)) {
            notificationService.send(feedback.getUserId(), "客服回复",
                    "工单《" + feedback.getSubject() + "》有新的回复：" + preview(content),
                    "feedback", userTicketLink(feedback));
        }
    }

    /** 用户端落地页：反馈与客服已分页，按工单类型给不同入口。 */
    private String userTicketLink(Feedback feedback) {
        String path = TYPE_SERVICE.equals(feedback.getType()) ? "/support" : "/feedback";
        return path + "?ticket=" + feedback.getId();
    }

    /** 管理端落地页：带上 type，列表直接停在对应 Tab。 */
    private String adminTicketLink(Feedback feedback) {
        return "/admin/feedback?type=" + feedback.getType() + "&id=" + feedback.getId();
    }

    private String normalizeType(String type) {
        if (TYPE_FEEDBACK.equals(type) || TYPE_SERVICE.equals(type)) {
            return type;
        }
        throw new BizException("反馈类型只能是 FEEDBACK 或 SERVICE");
    }

    private String normalizeSubject(String subject) {
        String text = subject == null ? "" : subject.trim();
        if (text.isEmpty()) {
            throw new BizException("请填写主题");
        }
        if (text.length() > MAX_SUBJECT) {
            throw new BizException("主题不能超过 " + MAX_SUBJECT + " 个字");
        }
        return text;
    }

    private String requireContent(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BizException("请填写内容");
        }
        if (text.length() > MAX_CONTENT) {
            throw new BizException("内容不能超过 " + MAX_CONTENT + " 个字");
        }
        return text;
    }

    private String normalizeClientMsgId(String clientMsgId) {
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return null;
        }
        String id = clientMsgId.trim();
        if (id.length() > MAX_CLIENT_MSG_ID) {
            throw new BizException("clientMsgId 过长");
        }
        return id;
    }

    private String preview(String content) {
        String single = content.replaceAll("\\s+", " ").trim();
        return single.length() <= PREVIEW_LEN ? single : single.substring(0, PREVIEW_LEN);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
