package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 站内通知 —— 表 t_notification。
 * 写入：NotificationService.send()（@Async），当前唯一触发点是有人提交答卷后通知问卷创建者；
 * 读取：NotificationController → AdminLayout.vue 顶栏铃铛（30 秒轮询 /unread 取红点）。
 * ★ link 存前端路由（如 /my/surveys/98/statistics）而不是完整 URL，换域名不用改数据。
 */
public class Notification {
    private Integer id;
    private Integer userId;      // 接收者 id
    private String title;        // 标题，如「收到新答卷」
    private String content;      // 正文
    private String type;         // 来源标记：answer（已用）/ announcement / system（预留）
    private Integer isRead;      // 0=未读，1=已读（插入时 mapper 硬编码 0）
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