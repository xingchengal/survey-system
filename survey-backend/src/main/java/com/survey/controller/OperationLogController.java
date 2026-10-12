package com.survey.controller;

import com.survey.annotation.RequireAdmin;
import com.survey.common.PageResult;
import com.survey.entity.OperationLog;
import com.survey.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 操作日志：查询「谁在什么时候做了什么」。
 *
 * ── 数据从哪来 ────────────────────────────────────────
 *   不是这里写的，而是 aspect/LogAspect.java 拦截 @OperationLog 注解后自动落库的
 *   （表 t_operation_log）。所以这个类只有一个只读的分页查询接口。
 *   LogAspect 整体包了 try-catch：日志写失败不影响业务，只打印一行错误。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   OperationLog.vue（管理员菜单「操作日志」页）
 *
 * ── 权限 ──────────────────────────────────────────────
 *   需管理员：由**类级 @RequireAdmin** 统一拦截（aspect/AdminCheckAspect 实施）。
 *   page() 上**没有** X-User-Role 形参 —— 角色只由类级 @RequireAdmin 判定，
 *   多收一个不读的形参只会让端点审计误判成"这里另有一道校验"。
 */
@Tag(name = "操作日志", description = "系统操作日志查询")
@RestController
@RequestMapping("/api/log")
@RequireAdmin
public class OperationLogController {

    @Autowired
    private OperationLogService operationLogService;

    @Operation(summary = "分页查询操作日志（管理员）")
    @GetMapping("/page")
    public PageResult<OperationLog> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String operation) {
        return operationLogService.page(pageNum, pageSize, username, operation);
    }
}