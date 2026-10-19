package com.survey.controller;

import com.survey.annotation.OperationLog;
import com.survey.annotation.RequireAdmin;
import com.survey.dto.AdminPermissionOption;
import com.survey.dto.SubAdminGrantRequest;
import com.survey.dto.SubAdminView;
import com.survey.service.AdminManagementService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 次级管理员管理 —— **超级管理员专属**（类级 @RequireAdmin）。
 *
 * ★ 为什么不挂 @RequirePermission：这类“谁能进管理端 / 谁有什么权限”的操作
 *   不能再下放给任何一个次级管理员，否则一个人可以给自己加权限（提权闭环）。
 * ★ 五个端点都直接改安全边界，失败一律 BizException（400）或切面 403/401；
 *   角色字符串只由服务端写，请求体里的 userId/permissions 不能指定 role。
 */
@Tag(name = "次级管理员", description = "选定、授权、降级（仅超级管理员）")
@RestController
@RequestMapping("/api/admin")
@RequireAdmin
public class AdminManagementController {

    @Autowired
    private AdminManagementService adminManagementService;

    @Operation(summary = "可分配的权限目录（超级管理员）")
    @GetMapping("/permissions")
    public List<AdminPermissionOption> permissions() {
        return adminManagementService.grantablePermissions();
    }

    @Operation(summary = "次级管理员列表（超级管理员）")
    @GetMapping("/sub-admins")
    public List<SubAdminView> list() {
        return adminManagementService.listSubAdmins();
    }

    @OperationLog("设为次级管理员")
    @Operation(summary = "把普通用户设为次级管理员并授权（超级管理员）")
    @PostMapping("/sub-admins")
    public List<SubAdminView> promote(@RequestBody SubAdminGrantRequest req,
                                      @RequestHeader("X-User-Id") Integer operatorId) {
        return adminManagementService.promote(req == null ? null : req.getUserId(),
                req == null ? null : req.getPermissions(), operatorId);
    }

    @OperationLog("修改次级管理员权限")
    @Operation(summary = "修改次级管理员权限（超级管理员）")
    @PutMapping("/sub-admins/{userId}/permissions")
    public List<SubAdminView> updatePermissions(@PathVariable Integer userId,
                                                @RequestBody SubAdminGrantRequest req,
                                                @RequestHeader("X-User-Id") Integer operatorId) {
        return adminManagementService.updatePermissions(userId,
                req == null ? null : req.getPermissions(), operatorId);
    }

    @OperationLog("降级次级管理员")
    @Operation(summary = "把次级管理员降为普通用户（超级管理员）")
    @DeleteMapping("/sub-admins/{userId}")
    public List<SubAdminView> demote(@PathVariable Integer userId,
                                     @RequestHeader("X-User-Id") Integer operatorId) {
        return adminManagementService.demote(userId, operatorId);
    }
}
