package com.survey.controller;

import com.survey.common.PageResult;
import com.survey.entity.OperationLog;
import com.survey.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "操作日志", description = "系统操作日志查询")
@RestController
@RequestMapping("/api/log")
public class OperationLogController {

    @Autowired
    private OperationLogService operationLogService;

    @Operation(summary = "分页查询操作日志（管理员）")
    @GetMapping("/page")
    public PageResult<OperationLog> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String operation,
            @RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) {
            throw new RuntimeException("无权限");
        }
        return operationLogService.page(pageNum, pageSize, username, operation);
    }
}