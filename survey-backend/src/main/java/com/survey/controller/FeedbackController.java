package com.survey.controller;

import com.survey.annotation.OperationLog;
import com.survey.annotation.RequirePermission;
import com.survey.common.Permission;
import com.survey.common.PageResult;
import com.survey.dto.FeedbackDetail;
import com.survey.dto.FeedbackView;
import com.survey.service.FeedbackService;
import com.survey.service.SurveyAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 反馈中心 / 客服工单。
 *
 * ★ 用户侧：所有登录用户可用，但每条按 `feedback.userId == 当前用户` 校验归属（Service 层）。
 * ★ 管理侧：只认 `feedback:handle`，不复用 review:handle / system:view；超管由切面兜底放行。
 * ★ 消息是纯文本，前端渲染禁止 v-html；发送限流由 RateLimitInterceptor 的 feedback-post 规则负责。
 */
@Tag(name = "反馈与客服", description = "用户反馈、客服工单、管理端回复")
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    @Autowired
    private FeedbackService feedbackService;

    /** 只为判断"是否显示管理端未读"；不参与用户侧归属校验。 */
    @Autowired
    private SurveyAccessService surveyAccessService;

    // ==================== 用户侧 ====================

    @Operation(summary = "提交反馈 / 发起客服工单")
    @PostMapping
    public FeedbackDetail create(@RequestBody Map<String, Object> body,
                                 @RequestHeader("X-User-Id") Integer userId) {
        return feedbackService.create(userId, text(body, "type"), text(body, "subject"),
                text(body, "content"), text(body, "clientMsgId"));
    }

    @Operation(summary = "我的反馈 / 工单列表")
    @GetMapping("/my")
    public List<FeedbackView> myList(@RequestHeader("X-User-Id") Integer userId) {
        return feedbackService.myList(userId);
    }

    @Operation(summary = "我的未读数（有 feedback:handle 时附带管理端未读数）")
    @GetMapping("/unread-count")
    public Map<String, Object> unread(@RequestHeader("X-User-Id") Integer userId,
                                      @RequestHeader("X-User-Role") String role) {
        boolean includeAdmin = surveyAccessService.hasModulePermission(userId, role, Permission.FEEDBACK_HANDLE);
        return feedbackService.unread(userId, includeAdmin);
    }

    @Operation(summary = "工单详情（afterId 增量）")
    @GetMapping("/{id}")
    public FeedbackDetail detail(@PathVariable Integer id,
                                 @RequestParam(required = false) Long afterId,
                                 @RequestHeader("X-User-Id") Integer userId) {
        return feedbackService.detail(id, userId, afterId);
    }

    @Operation(summary = "追加工单消息")
    @PostMapping("/{id}/messages")
    public FeedbackDetail send(@PathVariable Integer id,
                               @RequestBody Map<String, Object> body,
                               @RequestHeader("X-User-Id") Integer userId) {
        return feedbackService.send(id, userId, text(body, "content"), text(body, "clientMsgId"));
    }

    @Operation(summary = "标记工单已读")
    @PostMapping("/{id}/read")
    public String markRead(@PathVariable Integer id, @RequestHeader("X-User-Id") Integer userId) {
        feedbackService.markUserRead(id, userId);
        return "已读";
    }

    @Operation(summary = "关闭自己的工单")
    @PostMapping("/{id}/close")
    public String close(@PathVariable Integer id, @RequestHeader("X-User-Id") Integer userId) {
        feedbackService.closeByUser(id, userId);
        return "已关闭";
    }

    // ==================== 管理端 ====================

    @Operation(summary = "反馈 / 工单列表（管理端）")
    @GetMapping("/admin/page")
    @RequirePermission(Permission.FEEDBACK_HANDLE)
    public PageResult<FeedbackView> adminPage(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return feedbackService.adminPage(type, status, pageNum, pageSize);
    }

    @Operation(summary = "工单详情（管理端，afterId 增量）")
    @GetMapping("/admin/{id}")
    @RequirePermission(Permission.FEEDBACK_HANDLE)
    public FeedbackDetail adminDetail(@PathVariable Integer id,
                                      @RequestParam(required = false) Long afterId) {
        return feedbackService.adminDetail(id, afterId);
    }

    @OperationLog("回复反馈")
    @Operation(summary = "回复工单（管理端）")
    @PostMapping("/admin/{id}/reply")
    @RequirePermission(Permission.FEEDBACK_HANDLE)
    public FeedbackDetail adminReply(@PathVariable Integer id,
                                     @RequestBody Map<String, Object> body,
                                     @RequestHeader("X-User-Id") Integer adminId) {
        return feedbackService.adminReply(adminId, id, text(body, "content"), text(body, "clientMsgId"));
    }

    @OperationLog("关闭工单")
    @Operation(summary = "关闭工单（管理端）")
    @PostMapping("/admin/{id}/close")
    @RequirePermission(Permission.FEEDBACK_HANDLE)
    public String adminClose(@PathVariable Integer id, @RequestHeader("X-User-Id") Integer adminId) {
        feedbackService.closeByAdmin(adminId, id);
        return "已关闭";
    }

    /** 取字符串参数；缺失返回 null（不把字面量 "null" 传下去）。 */
    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }
}
