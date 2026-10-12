package com.survey.controller;

import com.survey.annotation.RequireAdmin;
import com.survey.common.BizException;
import com.survey.common.PageResult;
import com.survey.entity.ErrorLog;
import com.survey.service.ErrorLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 异常日志：查询系统运行中抛出的异常（含堆栈）。
 *
 * ── 数据从哪来 ────────────────────────────────────────
 *   不是这里写的，而是 config/GlobalExceptionHandler.java 捕获异常后自动落库的
 *   （表 t_error_log）。AI 生成失败也会写 —— ★ 但那个方法在 service/AiGenerateService.saveAiErrorLog，
 *   不在 AiGenerateController 里（AI 调用链在 Service 层）。
 *   该处理器会跳过文档类路径，避免「文档 404 又记一条日志」这种循环记录；
 *   堆栈超过 5000 字符会截断后再存。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   管理端「异常日志」页面（page / count），以及删除单条 / 清空。
 *
 * ── 权限 ──────────────────────────────────────────────
 *   四个端点全部需管理员：由**类级 @RequireAdmin** 统一拦截（aspect/AdminCheckAspect 实施，非管理员 400「无权限」）。
 *   失败风格统一为"抛异常"：count() 里没有 {count:0} 分支，delete() 抛 BizException("删除失败")，
 *     本文件没有「无权限」字符串 —— 非管理员由类级 @RequireAdmin 挡在进入方法体之前。
 */
@Tag(name = "异常日志", description = "系统异常记录查询")
@RestController
@RequestMapping("/api/error-log")
@RequireAdmin
public class ErrorLogController {

    @Autowired
    private ErrorLogService errorLogService;

    // ★ 四个方法都**没有** @RequestHeader("X-User-Role") 形参（角色由类级 @RequireAdmin 统一校验）：
    //   多一个"收了不用"的形参只会让签名变噪音、让端点审计误判成"这里另有一道校验"。
    @Operation(summary = "分页查询异常日志（管理员）")
    @GetMapping("/page")
    public PageResult<ErrorLog> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        return errorLogService.page(pageNum, pageSize, keyword);
    }

    @Operation(summary = "异常数量（管理员）")
    @GetMapping("/count")
    public Map<String, Object> count() {
        // ★ 非管理员由类级 @RequireAdmin 抛异常 → 400，前端能明确区分"没数据"和"没权限"；
        //   若返回 {count:0}（HTTP 200），前端会把"无权限"显示成"没有异常"。
        Map<String, Object> result = new HashMap<>();
        result.put("count", errorLogService.count());
        return result;
    }

    @Operation(summary = "删除单条异常日志（管理员）")
    @DeleteMapping("/{id}")
    public String delete(@PathVariable Integer id) {
        int r = errorLogService.delete(id);
        if (r <= 0) throw new BizException("删除失败");
        return "已删除";
    }

    @Operation(summary = "清空所有异常日志（管理员）")
    @DeleteMapping("/clear")
    public String clear() {
        errorLogService.clearAll();
        return "已清空";
    }
}