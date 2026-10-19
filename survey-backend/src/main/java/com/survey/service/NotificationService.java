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
 * 站内通知（t_notification）：发送、我的通知、已读、删除。
 * ★ send 是 @Async：发通知是顺手做的事，失败只记日志、绝不阻塞提交答卷等主流程。
 * 读写都带 userId（SQL 里 WHERE user_id 再过一层），别人的通知 id 报上来也改不动 / 删不掉。
 * link 存点击后跳转的前端路由（如 /my/surveys/98/statistics），不是后端接口。
 */
@Service
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private NotificationMapper notificationMapper;

    /**
     * 发一条站内通知（异步）：只 insert，不读回；userId 为 null 直接返回（匿名答卷没有接收者）。
     *
     * @param link 前端路由（如 /my/surveys/98/statistics）
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
            // @Async 抛出的异常不会传回调用方（也没有 AsyncUncaughtExceptionHandler）：
            // 自己兜住并留明确日志，否则「收到新答卷」这类关键提醒会静默丢失
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

    /** 标记单条通知为已读（带 userId，传别人的 id 只会更新 0 行）。 */
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

    /**
     * 删除指向某份问卷的通知（问卷被彻底删除时调用）：不清理的话，接收人点开必然「问卷不存在」，未读红点也一直算着。
     * ★ 只精确匹配带问卷 id 的 link；不能按 /my/surveys% 一把清 —— 协作者 / 审核 / 恢复申请的泛化入口删了仍有效。
     *
     * @return 清掉的行数（0 正常：没人收到过这份问卷的答卷通知）
     */
    public int deleteBySurveyLink(Integer surveyId) {
        if (surveyId == null) return 0;
        return notificationMapper.deleteBySurveyLink(surveyId);
    }
}
