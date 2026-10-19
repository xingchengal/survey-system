package com.survey.controller;

import com.survey.common.ForbiddenException;
import com.survey.common.Permission;
import com.survey.common.Role;
import com.survey.mapper.RoleMapper;
import com.survey.service.SystemStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统统计：管理端人员（超管 + 次级管理员）均可访问，按模块权限返回数据。
 * ★ 与 `/survey/system-overview` 同一权限口径：普通用户 403；`system:view` 只解锁资源模块，
 *   不再等于全站业务数据。
 * ★ 统计页的 Tab 由返回的模块字段决定，没权限的模块既不查库也不返回。
 */
@Tag(name = "系统统计", description = "按模块返回系统统计（管理端人员，字段按模块权限裁剪）")
@RestController
public class SystemStatisticsController {

    @Autowired
    private SystemStatisticsService systemStatisticsService;

    /** 只读权限点做字段裁剪，不做任何授权变更。 */
    @Autowired
    private RoleMapper roleMapper;

    @Operation(summary = "系统统计（管理端人员，按模块权限返回）")
    @GetMapping("/api/system/statistics")
    public Map<String, Object> statistics(@RequestHeader("X-User-Id") Integer userId,
                                          @RequestHeader("X-User-Role") String role) {
        if (!Role.isStaff(role)) {
            throw new ForbiddenException("无权限");
        }
        Set<String> permissions;
        if (Role.isAdmin(role)) {
            permissions = Permission.ALL;
        } else {
            List<String> codes = roleMapper.findPermissionCodesByUserId(userId);
            permissions = codes == null || codes.isEmpty() ? Set.of() : new HashSet<>(codes);
        }
        return systemStatisticsService.getStatistics(permissions);
    }
}
