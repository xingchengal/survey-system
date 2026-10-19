package com.survey.controller;

import com.survey.annotation.OperationLog;
import com.survey.annotation.RequirePermission;
import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.entity.Survey;
import com.survey.service.SurveyReviewService;
import com.survey.service.SurveyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 举报：提交举报、我的举报，以及管理员审核列表与处理。
 * 举报入口只接受已发布的问卷、不能举报自己（校验在 Service），且必须登录以留下 reporter_id。
 * 审核只认 review:handle 权限点。
 */
@Tag(name = "问卷举报", description = "提交举报与我的举报")
@RestController
public class SurveyReviewController {

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private SurveyReviewService reviewService;

    @Operation(summary = "举报问卷（登录用户）")
    @PostMapping("/api/survey/{surveyId}/report")
    public String report(@PathVariable Integer surveyId,
                         @RequestBody Map<String, Object> body,
                         @RequestHeader("X-User-Id") Integer userId) {
        // 无法举报不存在/未发布/自己的问卷，理由长度也在 Service 校验；这里只传身份。
        Survey survey = surveyService.getById(surveyId);
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        reviewService.report(survey, userId, reason);
        return "举报已提交，管理员会尽快处理";
    }

    @RequirePermission(Permission.REVIEW_HANDLE)
    @Operation(summary = "举报列表（管理员）")
    @GetMapping("/api/review/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return reviewService.listForAdmin(status);
    }

    @OperationLog("处理举报")
    @RequirePermission(Permission.REVIEW_HANDLE)
    @Operation(summary = "处理举报（管理员）")
    @PostMapping("/api/review/{id}/handle")
    public String handle(@PathVariable Integer id,
                         @RequestBody Map<String, Object> body,
                         @RequestHeader("X-User-Id") Integer adminId) {
        String action = body.get("action") == null ? null : String.valueOf(body.get("action")).trim();
        reviewService.handle(id, adminId, action);
        return "SUSPEND".equals(action) ? "已通过并下架" : "已驳回";
    }

    @Operation(summary = "我的举报（登录用户）")
    @GetMapping("/api/review/my")
    public List<Map<String, Object>> myReports(@RequestHeader("X-User-Id") Integer userId) {
        return reviewService.myReports(userId);
    }
}
