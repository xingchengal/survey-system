package com.survey.controller;

import com.survey.annotation.RequireAdmin;
import com.survey.common.BizException;
import com.survey.common.CollaboratorRole;
import com.survey.common.OwnershipGuard;
import com.survey.dto.CollaboratorView;
import com.survey.dto.InvitationView;
import com.survey.entity.Survey;
import com.survey.service.SurveyAccessService;
import com.survey.service.SurveyCollaboratorService;
import com.survey.service.SurveyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 问卷协作者：邀请 / 改角色 / 移除 / 列表，以及"共享给我的问卷"。
 * 管理协作者 = 作者本人或管理员（所有权问题，走 requireOwnerOrStaff；不能下放给 editor 协作者，否则权限扩散）。
 * 看"共享给我" = 任何登录用户，只返回自己参与的问卷。
 * "共享给我"挂在 /api/collaborator/shared：它不属于某份问卷，放进 /api/survey/ 会与 GET /{id} 抢路由。
 */
@Tag(name = "问卷协作者", description = "协作者管理与「共享给我」")
@RestController
public class SurveyCollaboratorController {

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private SurveyCollaboratorService collaboratorService;

    @Autowired
    private SurveyAccessService surveyAccessService;

    @Operation(summary = "某问卷的协作者列表（作者/管理员）")
    @GetMapping("/api/survey/{surveyId}/collaborators")
    public List<CollaboratorView> list(@PathVariable Integer surveyId,
                                       @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                                       @RequestHeader(value = "X-User-Role", required = false) String role) {
        Survey survey = requireManageable(surveyId, userId, role);
        return collaboratorService.list(survey);
    }

    @Operation(summary = "邀请协作者（按用户名；作者/管理员）")
    @PostMapping("/api/survey/{surveyId}/collaborators")
    public String invite(@PathVariable Integer surveyId,
                         @RequestBody Map<String, Object> body,
                         @RequestHeader("X-User-Id") Integer userId,
                         @RequestHeader("X-User-Role") String role) {
        Survey survey = requireManageable(surveyId, userId, role);
        // 已下架的问卷不能再扩员：下架期间内容冻结，不该把更多人拉进编辑链。
        surveyAccessService.requireNotSuspended(survey, role, "管理协作者");
        String username = body.get("username") == null ? null : String.valueOf(body.get("username")).trim();
        String collaboratorRole = body.get("role") == null ? null : String.valueOf(body.get("role")).trim();
        if (username == null || username.isEmpty()) {
            throw new BizException("请输入要邀请的用户名");
        }
        collaboratorService.invite(survey, username, collaboratorRole, userId);
        return "已发出邀请，等待对方接受";
    }

    @Operation(summary = "修改协作者角色（作者/管理员）")
    @PutMapping("/api/survey/{surveyId}/collaborators/{targetUserId}")
    public String changeRole(@PathVariable Integer surveyId,
                             @PathVariable Integer targetUserId,
                             @RequestBody Map<String, Object> body,
                             @RequestHeader("X-User-Id") Integer userId,
                             @RequestHeader("X-User-Role") String role) {
        Survey survey = requireManageable(surveyId, userId, role);
        // 同上：下架期间不能改协作者角色。
        surveyAccessService.requireNotSuspended(survey, role, "管理协作者");
        String collaboratorRole = body.get("role") == null ? null : String.valueOf(body.get("role")).trim();
        collaboratorService.changeRole(survey, targetUserId, collaboratorRole);
        return "已改为 " + collaboratorRole;
    }

    @Operation(summary = "移除协作者（作者/管理员）")
    @DeleteMapping("/api/survey/{surveyId}/collaborators/{targetUserId}")
    public String remove(@PathVariable Integer surveyId,
                         @PathVariable Integer targetUserId,
                         @RequestHeader("X-User-Id") Integer userId,
                         @RequestHeader("X-User-Role") String role) {
        Survey survey = requireManageable(surveyId, userId, role);
        collaboratorService.remove(survey, targetUserId);
        return "已移除该协作者";
    }

    /** 撤销未接受的邀请：只是把 PENDING 改成 REVOKED，撤销后对方再点接受一律 400。 */
    @Operation(summary = "撤销未接受的邀请（作者/管理员）")
    @PostMapping("/api/survey/{surveyId}/collaborators/{targetUserId}/revoke")
    public String revoke(@PathVariable Integer surveyId,
                         @PathVariable Integer targetUserId,
                         @RequestHeader("X-User-Id") Integer userId,
                         @RequestHeader("X-User-Role") String role) {
        Survey survey = requireManageable(surveyId, userId, role);
        collaboratorService.revoke(survey, targetUserId);
        return "已撤销邀请";
    }

    /** 我的待接受邀请：只按当前用户，不接受任何 userId 参数。 */
    @Operation(summary = "我的待接受协作邀请")
    @GetMapping("/api/collaborator/invitations")
    public List<InvitationView> myInvitations(@RequestHeader("X-User-Id") Integer userId) {
        return collaboratorService.listMyInvitations(userId);
    }

    /** 接受邀请：归属按 invitation.user_id 校验，接口没有可传的 userId；管理员账号一律拒绝。 */
    @Operation(summary = "接受协作邀请")
    @PostMapping("/api/collaborator/invitations/{id}/accept")
    public String accept(@PathVariable Integer id,
                         @RequestHeader("X-User-Id") Integer userId,
                         @RequestHeader("X-User-Role") String role) {
        collaboratorService.accept(id, userId, role);
        return "已接受邀请";
    }

    /** 拒绝邀请：同上，只作用于自己的邀请。 */
    @Operation(summary = "拒绝协作邀请")
    @PostMapping("/api/collaborator/invitations/{id}/reject")
    public String reject(@PathVariable Integer id,
                         @RequestHeader("X-User-Id") Integer userId) {
        collaboratorService.reject(id, userId);
        return "已拒绝邀请";
    }

    /** 退出协作（本人）：只作用于"我"，URL 里没有 userId，避免"帮别人退出"的越权口子。 */
    @Operation(summary = "退出协作（被邀请人本人）")
    @DeleteMapping("/api/collaborator/self/{surveyId}")
    public String leave(@PathVariable Integer surveyId,
                        @RequestHeader("X-User-Id") Integer userId) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        collaboratorService.leave(survey, userId);
        return "已退出协作";
    }

    /** 申请变更自己的协作角色（本人）：同样只作用于"我"，避免"替别人申请"。 */
    @Operation(summary = "申请变更协作角色（被邀请人本人）")
    @PostMapping("/api/collaborator/self/{surveyId}/apply")
    public String applyRole(@PathVariable Integer surveyId,
                            @RequestBody Map<String, Object> body,
                            @RequestHeader("X-User-Id") Integer userId) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        String collaboratorRole = body.get("role") == null ? null : String.valueOf(body.get("role")).trim();
        collaboratorService.applyRole(survey, userId, collaboratorRole);
        return "已提交申请，作者会收到通知";
    }

    /** 共享给我的问卷：只返回自己参与的，SQL 按 user_id 过滤，不接受"查别人"的参数。 */
    @Operation(summary = "共享给我的问卷")
    @GetMapping("/api/collaborator/shared")
    public List<Survey> sharedWithMe(@RequestHeader("X-User-Id") Integer userId) {
        return collaboratorService.listSharedWithMe(userId);
    }

    /**
     * 管理入口统一守卫：作者本人或管理员。
     * 管理名单不能下放给 editor 协作者（权限扩散），只给作者/超管/有模块权限的次级管理员。
     */
    private Survey requireManageable(Integer surveyId, Integer userId, String role) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        // 管理名单不能下放给 editor 协作者（会权限扩散）：只给作者/超管/有模块权限的次级管理员。
        surveyAccessService.requireOwnerOrStaff(survey, userId, role, "操作协作者");
        return survey;
    }
}
