package com.survey.service;

import com.survey.entity.Announcement;
import com.survey.mapper.AnnouncementMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnnouncementService {

    @Autowired
    private AnnouncementMapper announcementMapper;

    public Announcement create(Announcement a) {
        announcementMapper.insert(a);
        return a;
    }

    public List<Announcement> listAll() {
        return announcementMapper.findAll();
    }

    public List<Announcement> listActive() {
        return announcementMapper.findActive();
    }

    public int updateStatus(Integer id, Integer status) {
        return announcementMapper.updateStatus(id, status);
    }

    public int delete(Integer id) {
        return announcementMapper.deleteById(id);
    }
}