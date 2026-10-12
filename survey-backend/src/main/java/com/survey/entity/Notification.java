package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 站内通知实体 —— 对应表 t_notification。
 *
 * ── 谁写入它 ──────────────────────────────────────────
 *   NotificationService.send()（@Async）；目前唯一的业务触发点是
 *   AnswerService.submit() —— 有人提交答卷后，给**问卷创建者**发一条。
 *
 * ── 谁读它 ────────────────────────────────────────────
 *   NotificationController → AdminLayout.vue 顶栏铃铛
 *   （每 30 秒轮询 /unread 拿红点数字，点开拉 /list）
 *
 * ── 字段说明 ──────────────────────────────────────────
 *   type   : 来源标记。目前实际用到的是 "answer"；
 *            注释里列的 announcement / system 是预留（前端可按 type 显示不同图标）
 *   isRead : 0=未读 1=已读。★ 新插入时 mapper 里硬编码写 0
 *   link   : 点击通知后跳转的**前端路由**，如 /my/surveys/98/statistics
 *            （存的是路由不是完整 URL，所以换域名也不用改数据）
 */
public class Notification {
    private Integer id;
    private Integer userId;      // 接收者 id
    private String title;        // 标题，如"收到新答卷"
    private String content;      // 正文
    private String type;         // 来源标记：answer（已用）/ announcement / system（预留）
    private Integer isRead;      // 0=未读，1=已读
    private String link;         // 点击后跳转的前端路由

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getIsRead() { return isRead; }
    public void setIsRead(Integer isRead) { this.isRead = isRead; }

    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}