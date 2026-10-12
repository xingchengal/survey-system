package com.survey.service;

import com.survey.entity.Notification;
import com.survey.mapper.NotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内通知业务：发通知、查我的通知、标记已读、删除。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   NotificationController  → 站内通知的全部接口
 *   AnswerService.submit()  → 有人提交答卷后，给问卷创建者发一条
 *
 * ── 涉及的表 ──────────────────────────────────────────
 *   t_notification（type 区分来源，link 存点击后要跳的前端路由）
 *
 * ── ★ 用户隔离（这个类的重点）──────────────────────────
 *   markReadByUser / deleteByUser / clearAll 都**要求传 userId**，而且 mapper 的
 *   SQL 里 WHERE 也带 user_id —— 双保险。
 *   目的：就算有人把别人的通知 id 报上来，也改不动/删不掉别人的数据。
 *
 * ── 异步 ──────────────────────────────────────────────
 *   send() 标了 @Async：发通知是"顺手做的事"，不能拖慢提交答卷的主流程。
 *   依赖 SurveyBackendApplication 上的 @EnableAsync，去掉它就会退化成同步执行。
 */
@Service
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private NotificationMapper notificationMapper;

    /**
     * 发一条站内通知（异步）。
     * ★ userId 为 null 直接返回 —— 匿名答卷没有明确的"接收者"。
     *
     * @param userId  接收者 id
     * @param title   标题（如「收到新答卷」）
     * @param content 正文
     * @param type    类型标记（如 "answer"），前端可据此显示不同图标
     * @param link    点击后跳转的前端路由（如 /my/surveys/98/statistics）
     */
    @Async
    public void send(Integer userId, String title, String content, String type, String link) {
        if (userId == null) return;
        try {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setTitle(title);
            n.setContent(content);
            n.setType(type);
            n.setLink(link);
            notificationMapper.insert(n);
        } catch (Exception e) {
            // ★ @Async 方法抛出的异常**不会传回调用方**，而项目没有注册
            //   AsyncUncaughtExceptionHandler，所以 insert 失败只会由默认处理器打一行日志，
            //   业务侧（提交答卷那条链路）完全不知道通知没发出去 ——
            //   "收到新答卷"这类关键提醒就静默丢了。
            //   所以与 ErrorLogService.save 同款：自己兜住，并留下一条可查的明确日志。
            LOG.error("发送站内通知失败：userId={} title={} —— {}", userId, title, e.getMessage(), e);
        }
    }

    /** 我的通知列表（时间倒序）。 */
    public List<Notification> listByUser(Integer userId) {
        return notificationMapper.findByUserId(userId);
    }

    /** 我的未读数（顶栏铃铛的红点，前端每 30 秒轮询一次）。 */
    public int countUnread(Integer userId) {
        return notificationMapper.countUnread(userId);
    }

    /**
     * 标记单条通知为已读。
     * ★ 带 userId：只能标记「自己的」通知，传别人的 id 会更新 0 行。
     *
     * @return 受影响行数；0 表示不是你的（或不存在）
     */
    public int markReadByUser(Integer id, Integer userId) {
        return notificationMapper.markReadByUser(id, userId);
    }

    /** 全部标记已读（只影响自己的）。 */
    public int markAllRead(Integer userId) {
        return notificationMapper.markAllRead(userId);
    }

    /** 删除单条通知（同样带 userId 做隔离）。 */
    public int deleteByUser(Integer id, Integer userId) {
        return notificationMapper.deleteByUser(id, userId);
    }

    /** 清空我的所有通知。 */
    public int clearAll(Integer userId) {
        return notificationMapper.deleteAllByUser(userId);
    }
}
