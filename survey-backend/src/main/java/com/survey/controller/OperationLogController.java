package com.survey.controller;

import com.alibaba.excel.EasyExcel;
import com.survey.annotation.RequireAdmin;
import com.survey.common.PageResult;
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
 * 操作日志：查询「谁在什么时候做了什么」。
 *
 * ── 数据从哪来 ────────────────────────────────────────
 *   不是这里写的，而是 aspect/LogAspect.java 拦截 @OperationLog 注解后自动落库的
 *   （表 t_operation_log）。所以这个类只有只读的分页查询与导出接口。
 *   LogAspect 整体包了 try-catch：日志写失败不影响业务，只打印一行错误。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   OperationLog.vue（管理员菜单「操作日志」页）
 *
 * ── 权限 ──────────────────────────────────────────────
 *   需管理员：由**类级 @RequireAdmin** 统一拦截（aspect/AdminCheckAspect 实施）。
 *   page() 上**没有** X-User-Role 形参 —— 角色只由类级 @RequireAdmin 判定，
 *   多收一个不读的形参只会让端点审计误判成"这里另有一道校验"。
 *
 * ── ★ 关于「级别筛选」的落地口径 ──────────────────────
 *   t_operation_log **没有"级别"列**（日志的严重程度在异常日志那边才有的概念）。
 *   本页的「级别」= **操作人角色**（admin / user），对应表里的 `role` 列：
 *   管理员操作与普通用户操作混在一张表里，筛出"管理员做了什么"才是真实的审计需求。
 *   ★ 不新增 level 列：那需要改表 + 回填历史数据 + 改 LogAspect 的写入契约，
 *     而"高危操作"（删除类）完全可以用 operation 关键字筛出来。
 */
@Tag(name = "操作日志", description = "系统操作日志查询与导出")
@RestController
@RequestMapping("/api/log")
@RequireAdmin
public class OperationLogController {

    @Autowired
    private OperationLogService operationLogService;

    /**
     * 分页查询操作日志（管理员）。
     *
     * @param username  操作人昵称（模糊）
     * @param operation 操作类型（模糊）
     * @param role      操作人角色：admin / user；空 = 全部（「级别筛选」的落地口径，见类注释）
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
     * 导出操作日志为 Excel（管理员）。
     *
     * ★ 筛选条件与 {@code /page} **完全一致**，前端把当前筛选原样带过来，
     *   "导出的就是屏幕上筛出来的"。
     * ★ 上限 10000 行（OperationLogService.EXPORT_MAX_ROWS），超出明确拒绝，不静默截断。
     * ★ 导出的是**日志原文**（含 IP 与方法名），所以只能管理员下载 —— 权限由类级 @RequireAdmin 保证。
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
        // ★ 文件名两层编码（同 AnswerController.export）：filename 只能是 ASCII，
        //   中文名放 filename*（RFC 5987），否则浏览器下载到的是一串 %E6%93%8D... 乱码名。
        String encodedName = URLEncoder.encode("操作日志", "UTF-8").replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"operation-logs.xlsx\"; filename*=UTF-8''" + encodedName + ".xlsx");

        EasyExcel.write(response.getOutputStream())
                .head(head)
                .sheet("操作日志")
                .doWrite(rows);
    }
}