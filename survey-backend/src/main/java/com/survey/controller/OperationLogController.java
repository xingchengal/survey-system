package com.survey.controller;

import com.alibaba.excel.EasyExcel;
import com.survey.annotation.RequirePermission;
import com.survey.common.PageResult;
import com.survey.common.Permission;
import com.survey.entity.OperationLog;
import com.survey.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 操作日志：查询 LogAspect 按 @OperationLog 落库的记录（t_operation_log），只读。
 * 权限：类级 @RequirePermission(LOG_VIEW)；方法不收 X-User-Role 形参，角色由切面判定。
 * ★ 模糊筛选参数由 Service 过 SqlLikeEscaper，避免 % / _ 被当通配符。
 * 「级别」= 操作人角色（role 列），表里没有 level 列；高危操作按 operation 关键字筛。
 */
@Tag(name = "操作日志", description = "系统操作日志查询与导出")
@RestController
@RequestMapping("/api/log")
@RequirePermission(Permission.LOG_VIEW)
public class OperationLogController {

    @Autowired
    private OperationLogService operationLogService;

    /**
     * 分页查询操作日志（管理员）。
     * @param role      操作人角色 admin / user；空 = 全部（「级别筛选」的落地口径）
     * @param startDate 操作时间起（yyyy-MM-dd，含当天）
     * @param endDate   操作时间止（yyyy-MM-dd，含当天）
     */
    @Operation(summary = "分页查询操作日志（管理员，支持时间范围与角色筛选）")
    @GetMapping("/page")
    public PageResult<OperationLog> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return operationLogService.page(pageNum, pageSize, username, operation, role, startDate, endDate);
    }

    /**
     * 导出操作日志为 Excel（管理员）：筛选条件与 /page 完全一致，导出即屏幕所见。
     * ★ 上限 10000 行，超出明确拒绝、不静默截断（OperationLogService.EXPORT_MAX_ROWS）。
     */
    @Operation(summary = "导出操作日志为 Excel（管理员）")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String username,
                       @RequestParam(required = false) String operation,
                       @RequestParam(required = false) String role,
                       @RequestParam(required = false) String startDate,
                       @RequestParam(required = false) String endDate,
                       HttpServletResponse response) throws IOException {
        List<OperationLog> logs = operationLogService.exportLogs(username, operation, role, startDate, endDate);

        List<List<String>> head = List.of(
                List.of("ID"), List.of("操作人"), List.of("角色"), List.of("操作类型"),
                List.of("调用方法"), List.of("IP"), List.of("操作时间"));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<List<String>> rows = new ArrayList<>(logs.size());
        for (OperationLog log : logs) {
            List<String> row = new ArrayList<>(7);
            row.add(log.getId() == null ? "" : String.valueOf(log.getId()));
            row.add(log.getUsername() == null ? "" : log.getUsername());
            row.add("admin".equals(log.getRole()) ? "管理员" : "普通用户");
            row.add(log.getOperation() == null ? "" : log.getOperation());
            row.add(log.getMethod() == null ? "" : log.getMethod());
            row.add(log.getIp() == null ? "" : log.getIp());
            row.add(log.getCreateTime() == null ? "" : log.getCreateTime().format(fmt));
            rows.add(row);
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        // 文件名两层编码：filename 只能是 ASCII，中文名放 filename*（RFC 5987），否则下载到 %E6... 乱码名。
        String encodedName = URLEncoder.encode("操作日志", "UTF-8").replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"operation-logs.xlsx\"; filename*=UTF-8''" + encodedName + ".xlsx");

        EasyExcel.write(response.getOutputStream())
                .head(head)
                .sheet("操作日志")
                .doWrite(rows);
    }
}