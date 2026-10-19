package com.survey.dto;

import com.survey.entity.FeedbackMessage;

import java.util.List;

/**
 * 工单详情：头 + 一段消息（afterId 增量）。
 * ★ hasMore=true 表示还有更多旧/新消息没取完，前端应立即再拉一次（最多补几轮）。
 */
public class FeedbackDetail {

    private FeedbackView feedback;
    private List<FeedbackMessage> messages;
    private boolean hasMore;

    public FeedbackView getFeedback() { return feedback; }
    public void setFeedback(FeedbackView feedback) { this.feedback = feedback; }

    public List<FeedbackMessage> getMessages() { return messages; }
    public void setMessages(List<FeedbackMessage> messages) { this.messages = messages; }

    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
}
