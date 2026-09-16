package com.survey.controller;

import com.survey.annotation.OperationLog;
import com.survey.common.PageResult;
import com.survey.entity.Survey;
import com.survey.service.AnswerService;
import com.survey.service.SurveyService;
import com.survey.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "问卷管理", description = "问卷的增删改查、发布、复制、回收站、统计等")
@RestController
@RequestMapping("/api/survey")
public class SurveyController {

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private AnswerService answerService;

    @Autowired
    private UserService userService;

    // ==================== 基础 CRUD ====================

    @OperationLog("创建问卷")
    @Operation(summary = "新增问卷")
    @PostMapping("/add")
    public Survey add(@RequestBody Survey survey) {
        if (survey.getStatus() == null) survey.setStatus(0);
        if (survey.getViewCount() == null) survey.setViewCount(0);
        return surveyService.createSurvey(survey);
    }

    @OperationLog("删除问卷")
    @Operation(summary = "删除问卷（彻底删除）")
    @DeleteMapping("/delete/{id}")
    public String delete(@PathVariable Integer id,
                         @RequestHeader("X-User-Id") Integer userId,
                         @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(id);
        if (survey == null) return "问卷不存在";
        if (!"admin".equals(role) && !survey.getUserId().equals(userId)) {
            return "无权限删除他人问卷";
        }
        int result = surveyService.deleteSurvey(id);
        return result > 0 ? "删除成功" : "删除失败";
    }

    // ★ 修复问题 7：把 required 改为默认 true（默认就是 true，去掉 required=false）
    @OperationLog("更新问卷")
    @Operation(summary = "更新问卷（含标题、描述、时间等）")
    @PutMapping("/update")
    public String update(@RequestBody Survey survey,
                         @RequestHeader("X-User-Id") Integer userId,
                         @RequestHeader("X-User-Role") String role) {
        Survey old = surveyService.getById(survey.getId());
        if (old == null) return "问卷不存在";

        // ★ 硬校验：只要不是管理员，且不是自己的问卷，就拒绝
        if (!"admin".equals(role) && !old.getUserId().equals(userId)) {
            return "无权限修改他人问卷";
        }

        survey.setUserId(old.getUserId());
        if (survey.getShareCode() == null) {
            survey.setShareCode(old.getShareCode());
        }
        int result = surveyService.updateSurvey(survey);
        return result > 0 ? "修改成功" : "修改失败";
    }

    // ==================== 查询 ====================

    @Operation(summary = "查询所有问卷（管理员）")
    @GetMapping("/list")
    public List<Survey> list() {
        return surveyService.listAll();
    }

    @Operation(summary = "分页查询全部问卷（管理员，支持搜索+状态筛选）")
    @GetMapping("/page")
    public PageResult<Survey> pageSurveys(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) {
            throw new RuntimeException("无权限");
        }
        return surveyService.pageAllSurveys(pageNum, pageSize, keyword, status);
    }

    @Operation(summary = "查询当前用户的问卷（我的问卷）")
    @GetMapping("/my")
    public List<Survey> mySurveys(@RequestHeader("X-User-Id") Integer userId) {
        return surveyService.listByUserId(userId);
    }

    @Operation(summary = "根据ID查询问卷")
    @GetMapping("/{id}")
    public Survey getOne(@PathVariable Integer id) {
        Survey survey = surveyService.getById(id);
        if (survey == null) {
            throw new RuntimeException("问卷不存在，ID：" + id);
        }
        return survey;
    }

    @Operation(summary = "根据分享码查询问卷（匿名访客）")
    @GetMapping("/share/{shareCode}")
    public Survey getByShareCode(@PathVariable String shareCode) {
        return surveyService.getByShareCode(shareCode);
    }

    // ==================== 发布 / 复制 ====================

    // ★ 修复问题 7：publish 也做硬校验
    @OperationLog("发布问卷")
    @Operation(summary = "发布问卷（生成分享码）")
    @PostMapping("/publish/{id}")
    public Survey publish(@PathVariable Integer id,
                          @RequestHeader("X-User-Id") Integer userId,
                          @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(id);
        if (survey == null) return null;

        if (!"admin".equals(role) && !survey.getUserId().equals(userId)) {
            return null;
        }
        return surveyService.publish(id);
    }

    @OperationLog("复制问卷")
    @Operation(summary = "复制问卷")
    @PostMapping("/copy/{id}")
    public Survey copy(@PathVariable Integer id,
                       @RequestHeader("X-User-Id") Integer userId,
                       @RequestHeader("X-User-Role") String role) {
        Survey old = surveyService.getById(id);
        if (old == null) return null;
        if (!"admin".equals(role) && !old.getUserId().equals(userId)) {
            return null;
        }
        return surveyService.copySurvey(id, userId);
    }

    // ==================== 统计 ====================

    @Operation(summary = "系统统计（管理员看板）")
    @GetMapping("/statistics")
    public Map<String, Object> statistics() {
        List<Survey> all = surveyService.listAll();
        // 排除模板
        List<Survey> normalSurveys = all.stream()
                .filter(s -> s.getIsTemplate() == null || s.getIsTemplate() == 0)
                .collect(java.util.stream.Collectors.toList());

        long total = normalSurveys.size();
        long published = normalSurveys.stream().filter(s -> s.getStatus() != null && s.getStatus() == 1).count();
        long draft = normalSurveys.stream().filter(s -> s.getStatus() == null || s.getStatus() == 0).count();
        long ended = normalSurveys.stream().filter(s -> s.getStatus() != null && s.getStatus() == 2).count();

        int responses = 0;
        for (Survey s : normalSurveys) {
            responses += answerService.countResponses(s.getId());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("published", published);
        result.put("draft", draft);
        result.put("ended", ended);
        result.put("responses", responses);
        return result;
    }

    @Operation(summary = "系统总览（含用户数、访客次数）")
    @GetMapping("/system-overview")
    public Map<String, Object> systemOverview() {
        Map<String, Object> result = surveyService.getSystemOverview();
        result.put("users", userService.countNormalUsers());
        return result;
    }

    @Operation(summary = "当前用户个人统计")
    @GetMapping("/my-statistics")
    public Map<String, Object> myStatistics(@RequestHeader("X-User-Id") Integer userId) {
        return surveyService.getUserStatistics(userId);
    }

    // ==================== 访问记录 ====================

    @Operation(summary = "记录访问次数（匿名访客打开链接时调用）")
    @PostMapping("/view/{shareCode}")
    public String recordView(@PathVariable String shareCode) {
        surveyService.incrementViewCount(shareCode);
        return "ok";
    }

    // ==================== 回收站 ====================

    @OperationLog("移入回收站")
    @Operation(summary = "移到回收站")
    @PutMapping("/recycle/{id}")
    public String moveToRecycle(@PathVariable Integer id,
                                @RequestHeader("X-User-Id") Integer userId,
                                @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(id);
        if (survey == null) return "问卷不存在";
        if (!"admin".equals(role) && !survey.getUserId().equals(userId)) {
            return "无权限";
        }
        int result = surveyService.moveToRecycle(id, userId);
        return result > 0 ? "已移入回收站" : "操作失败";
    }

    @OperationLog("恢复问卷")
    @Operation(summary = "从回收站恢复")
    @PutMapping("/restore/{id}")
    public String restore(@PathVariable Integer id,
                          @RequestHeader("X-User-Id") Integer userId,
                          @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(id);
        if (survey == null) return "问卷不存在";
        if (!"admin".equals(role) && !survey.getUserId().equals(userId)) {
            return "无权限";
        }
        int result = surveyService.restoreFromRecycle(id);
        return result > 0 ? "已恢复" : "操作失败";
    }

    @OperationLog("彻底删除问卷")
    @Operation(summary = "彻底删除问卷")
    @DeleteMapping("/permanent/{id}")
    public String permanentDelete(@PathVariable Integer id,
                                  @RequestHeader("X-User-Id") Integer userId,
                                  @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(id);
        if (survey == null) return "问卷不存在";
        if (!"admin".equals(role) && !survey.getUserId().equals(userId)) {
            return "无权限";
        }
        int result = surveyService.permanentDelete(id);
        return result > 0 ? "已彻底删除" : "删除失败";
    }

    @Operation(summary = "查询回收站（普通用户看自己的，管理员看全部）")
    @GetMapping("/recycle")
    public List<Survey> recycle(@RequestHeader("X-User-Id") Integer userId,
                                @RequestHeader("X-User-Role") String role) {
        if ("admin".equals(role)) {
            return surveyService.listAllRecycle();
        }
        return surveyService.listRecycleByUserId(userId);
    }

    @Operation(summary = "分页查询回收站（支持搜索）")
    @GetMapping("/recycle/page")
    public PageResult<Survey> pageRecycle(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestHeader("X-User-Id") Integer userId,
            @RequestHeader("X-User-Role") String role) {
        if ("admin".equals(role)) {
            return surveyService.pageRecycleAll(pageNum, pageSize, keyword);
        }
        return surveyService.pageRecycleByUser(userId, pageNum, pageSize, keyword);
    }

    // ==================== 模板 ====================

    @Operation(summary = "查询模板广场列表")
    @GetMapping("/templates")
    public List<Survey> templates(@RequestParam(required = false) String keyword) {
        return surveyService.listTemplates(keyword);
    }

    @OperationLog("从模板创建问卷")
    @Operation(summary = "从模板创建问卷")
    @PostMapping("/from-template/{templateId}")
    public Survey createFromTemplate(@PathVariable Integer templateId,
                                     @RequestHeader("X-User-Id") Integer userId) {
        return surveyService.createFromTemplate(templateId, userId);
    }

    @OperationLog("设为模板")
    @Operation(summary = "设为模板 / 取消模板（管理员）")
    @PutMapping("/set-template/{id}")
    public String setTemplate(@PathVariable Integer id,
                              @RequestParam Integer isTemplate,
                              @RequestParam(required = false) String category,
                              @RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) return "无权限";
        int r = surveyService.setTemplate(id, isTemplate, category);
        return r > 0 ? "操作成功" : "操作失败";
    }
}