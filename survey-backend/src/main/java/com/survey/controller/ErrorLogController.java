package com.survey.controller;

import com.survey.common.PageResult;
import com.survey.entity.ErrorLog;
import com.survey.service.ErrorLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "异常日志", description = "系统异常记录查询")
@RestController
@RequestMapping("/api/error-log")
public class ErrorLogController {

    @Autowired
    private ErrorLogService errorLogService;

    @Operation(summary = "分页查询异常日志（管理员）")
    @GetMapping("/page")
    public PageResult<ErrorLog> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) {
            throw new RuntimeException("无权限");
        }
        return errorLogService.page(pageNum, pageSize, keyword);
    }

    @Operation(summary = "异常数量（管理员）")
    @GetMapping("/count")
    public Map<String, Object> count(@RequestHeader("X-User-Role") String role) {
        Map<String, Object> result = new HashMap<>();
        if (!"admin".equals(role)) {
            result.put("count", 0);
            return result;
        }
        result.put("count", errorLogService.count());
        return result;
    }

    @Operation(summary = "删除单条异常日志（管理员）")
    @DeleteMapping("/{id}")
    public String delete(@PathVariable Integer id, @RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) return "无权限";
        int r = errorLogService.delete(id);
        return r > 0 ? "已删除" : "失败";
    }

    @Operation(summary = "清空所有异常日志（管理员）")
    @DeleteMapping("/clear")
    public String clear(@RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) return "无权限";
        errorLogService.clearAll();
        return "已清空";
    }
}