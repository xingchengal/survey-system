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
 * 举报（§H-H1-2）。
 *
 * ── 权限 ──────────────────────────────────────────────
 *   两个端点都**只需登录**：举报人不需要对问卷有任何归属关系（这正是举报的意义），
 *   但必须留下身份（reporter_id），否则无法防刷屏、也无法"我的举报"。
 *   ★ 管理员审核（列表/处理）在 H1-3，不在本类。
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
