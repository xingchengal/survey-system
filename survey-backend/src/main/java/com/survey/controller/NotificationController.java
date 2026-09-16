package com.survey.controller;

import com.survey.entity.Notification;
import com.survey.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "站内通知", description = "消息中心")
@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    @Autowired
    private NotificationService notificationService;

    @Operation(summary = "查询我的通知列表")
    @GetMapping("/list")
    public List<Notification> list(@RequestHeader("X-User-Id") Integer userId) {
        return notificationService.listByUser(userId);
    }

    @Operation(summary = "未读数量")
    @GetMapping("/unread")
    public Map<String, Object> unread(@RequestHeader("X-User-Id") Integer userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("count", notificationService.countUnread(userId));
        return result;
    }

    // ★ 修复问题 6：加 userId 校验
    @Operation(summary = "标记单条已读")
    @PutMapping("/read/{id}")
    public String markRead(@PathVariable Integer id,
                           @RequestHeader("X-User-Id") Integer userId) {
        int r = notificationService.markReadByUser(id, userId);
        return r > 0 ? "已读" : "失败或无权限";
    }

    @Operation(summary = "全部标记已读")
    @PutMapping("/read-all")
    public String markAllRead(@RequestHeader("X-User-Id") Integer userId) {
        notificationService.markAllRead(userId);
        return "全部已读";
    }

    // ★ 修复问题 6：加 userId 校验
    @Operation(summary = "删除单条通知")
    @DeleteMapping("/{id}")
    public String delete(@PathVariable Integer id,
                         @RequestHeader("X-User-Id") Integer userId) {
        int r = notificationService.deleteByUser(id, userId);
        return r > 0 ? "已删除" : "失败或无权限";
    }

    @Operation(summary = "清空所有通知")
    @DeleteMapping("/clear")
    public String clear(@RequestHeader("X-User-Id") Integer userId) {
        notificationService.clearAll(userId);
        return "已清空";
    }
}