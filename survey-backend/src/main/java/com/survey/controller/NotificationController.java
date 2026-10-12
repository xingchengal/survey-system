package com.survey.controller;

import com.survey.common.BizException;
import com.survey.entity.Notification;
import com.survey.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知（消息中心）：查看我的通知、未读数、标记已读、删除。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   AdminLayout.vue → 顶栏铃铛：每 30 秒轮询 /unread；点开拉 /list；
 *                     标记已读、删除、清空也都从这里发起。
 *
 * ── 权限与隔离（这个类做得比较对，可作参考）────────────
 *   需登录：全部 6 个端点都从 X-User-Id 取当前用户。
 *   ★ 关键：已读 / 删除在 Service 层也带 userId（markReadByUser / deleteByUser），
 *     不是只在 Controller 层过一下 —— 双保险，防止拿别人的通知 id 来标记或删除。
 *     所以就算有人在请求里塞别人的通知 id，SQL 的 WHERE 也匹配不到。
 *
 * ── 数据来源 ──────────────────────────────────────────
 *   通知由业务动作产生：AnswerService.submit() 会在有人提交答卷后，
 *   给问卷创建者发一条 type="answer"、link=/my/surveys/{id}/statistics 的通知。
 *   写入走 NotificationService.send()，它标了 @Async，不阻塞主流程。
 */
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

    // ★ 必须把 userId 传给 Service（markReadByUser 在 SQL 里也按 userId 过滤）——
    //   否则拿别人的通知 id 就能把它标记已读。
    @Operation(summary = "标记单条已读")
    @PutMapping("/read/{id}")
    public String markRead(@PathVariable Integer id,
                           @RequestHeader("X-User-Id") Integer userId) {
        int r = notificationService.markReadByUser(id, userId);
        if (r <= 0) throw new BizException("标记失败或无权限");
        return "已读";
    }

    @Operation(summary = "全部标记已读")
    @PutMapping("/read-all")
    public String markAllRead(@RequestHeader("X-User-Id") Integer userId) {
        notificationService.markAllRead(userId);
        return "全部已读";
    }

    // ★ 必须把 userId 传给 Service（deleteByUser 在 SQL 里也按 userId 过滤）——
    //   否则拿别人的通知 id 就能把它删掉。
    @Operation(summary = "删除单条通知")
    @DeleteMapping("/{id}")
    public String delete(@PathVariable Integer id,
                         @RequestHeader("X-User-Id") Integer userId) {
        int r = notificationService.deleteByUser(id, userId);
        if (r <= 0) throw new BizException("删除失败或无权限");
        return "已删除";
    }

    @Operation(summary = "清空所有通知")
    @DeleteMapping("/clear")
    public String clear(@RequestHeader("X-User-Id") Integer userId) {
        notificationService.clearAll(userId);
        return "已清空";
    }
}