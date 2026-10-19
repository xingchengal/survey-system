package com.survey.controller;

import com.survey.annotation.RequireAdmin;
import com.survey.annotation.RequirePermission;
import com.survey.common.BizException;
import com.survey.common.PageResult;
import com.survey.common.Permission;
import com.survey.entity.ErrorLog;
import com.survey.service.ErrorLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 异常日志：查询 GlobalExceptionHandler 落库的异常（含堆栈，超 5000 字符截断）。
 * 权限：查询按 log:view（可授予次级管理员），删除 / 清空是 @RequireAdmin 超管专属。
 * ★ 删除单条与清空都不可恢复；clear 只清 t_error_log，不动操作日志。
 * 失败统一抛异常（BizException / 403），不返回 {count:0} 这种伪成功。
 */
@Tag(name = "异常日志", description = "系统异常记录查询")
@RestController
@RequestMapping("/api/error-log")
public class ErrorLogController {

    @Autowired
    private ErrorLogService errorLogService;

    @RequirePermission(Permission.LOG_VIEW)
    @Operation(summary = "分页查询异常日志（管理员）")
    @GetMapping("/page")
    public PageResult<ErrorLog> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        return errorLogService.page(pageNum, pageSize, keyword);
    }

    @RequirePermission(Permission.LOG_VIEW)
    @Operation(summary = "异常数量（管理员）")
    @GetMapping("/count")
    public Map<String, Object> count() {
        Map<String, Object> result = new HashMap<>();
        result.put("count", errorLogService.count());
        return result;
    }

    @RequireAdmin
    @Operation(summary = "删除单条异常日志（管理员）")
    @DeleteMapping("/{id}")
    public String delete(@PathVariable Integer id) {
        int r = errorLogService.delete(id);
        if (r <= 0) throw new BizException("删除失败");
        return "已删除";
    }

    @RequireAdmin
    @Operation(summary = "清空所有异常日志（管理员）")
    @DeleteMapping("/clear")
    public String clear() {
        errorLogService.clearAll();
        return "已清空";
    }
}