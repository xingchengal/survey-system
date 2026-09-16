package com.survey.service;

import com.survey.entity.Notification;
import com.survey.mapper.NotificationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotificationService {

    @Autowired
    private NotificationMapper notificationMapper;

    @Async
    public void send(Integer userId, String title, String content, String type, String link) {
        if (userId == null) return;
        Notification n = new Notification();
        n.setUserId(userId);
        n.setTitle(title);
        n.setContent(content);
        n.setType(type);
        n.setLink(link);
        notificationMapper.insert(n);
    }

    public List<Notification> listByUser(Integer userId) {
        return notificationMapper.findByUserId(userId);
    }

    public int countUnread(Integer userId) {
        return notificationMapper.countUnread(userId);
    }

    // ★ 修复问题 6
    public int markReadByUser(Integer id, Integer userId) {
        return notificationMapper.markReadByUser(id, userId);
    }

    public int markAllRead(Integer userId) {
        return notificationMapper.markAllRead(userId);
    }

    // ★ 修复问题 6
    public int deleteByUser(Integer id, Integer userId) {
        return notificationMapper.deleteByUser(id, userId);
    }

    public int clearAll(Integer userId) {
        return notificationMapper.deleteAllByUser(userId);
    }
}