package com.survey.service;

import com.survey.entity.Announcement;
import com.survey.mapper.AnnouncementMapper;
import com.survey.common.TextLimits;
import com.survey.common.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统公告（t_announcement）：发布、查询、上下架、删除。
 * ★ active 必须同时过滤 status=1 与未过期；status 只认 0/1（Controller 校验），其它值会让
 *   /active 与状态标签同时语义失真。
 * 权限（管理员）在 Controller 层做，本类不做身份判断；新增入口要自己补校验。
 */
@Service
public class AnnouncementService {

    @Autowired
    private AnnouncementMapper announcementMapper;

    /**
     * 发布公告：publisherId / publisherName 由 Controller 写入（名字取真实昵称）。
     * 正文是 text NOT NULL：空校验与长度校验都要有，否则落到 MySQL 报错 → 500 而不是 400。
     */
    public Announcement create(Announcement a) {
        TextLimits.requireWithin(a.getTitle(), TextLimits.ANNOUNCEMENT_TITLE, "公告标题");
        if (a.getContent() == null || a.getContent().trim().isEmpty()) {
            throw new BizException("公告正文不能为空");
        }
        TextLimits.requireWithin(a.getContent(), TextLimits.ANNOUNCEMENT_CONTENT, "公告正文");
        announcementMapper.insert(a);
        return a;
    }

    /** 全部公告（管理员管理页用，包含已下架 / 已过期的）。 */
    public List<Announcement> listAll() {
        return announcementMapper.findAll();
    }

    /** 有效公告（登录后弹窗用）：SQL 里已同时过滤下架与过期。 */
    public List<Announcement> listActive() {
        return announcementMapper.findActive();
    }

    /** 上下架公告（status：1=上架、0=下架）。 */
    public int updateStatus(Integer id, Integer status) {
        return announcementMapper.updateStatus(id, status);
    }

    /** 删除公告。 */
    public int delete(Integer id) {
        return announcementMapper.deleteById(id);
    }
}
