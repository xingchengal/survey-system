package com.survey.service;

import com.survey.entity.Announcement;
import com.survey.mapper.AnnouncementMapper;
import com.survey.common.TextLimits;
import com.survey.common.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统公告业务：发布、查询、上下架、删除。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   AnnouncementController（公告的全部接口）
 *
 * ── 涉及的表 ──────────────────────────────────────────
 *   t_announcement（status 控制上下架，expireTime 控制过期时间）
 *
 * ── 说明 ──────────────────────────────────────────────
 *   这一层很薄，基本只是转发给 mapper。**权限校验（必须管理员）在 Controller 里做**，
 *   所以这里的方法本身不做身份判断 —— 以后若新增别的入口调用它，需要自己补校验。
 */
@Service
public class AnnouncementService {

    @Autowired
    private AnnouncementMapper announcementMapper;

    /**
     * 发布公告。
     * ★ publisherId 由 Controller 从 X-User-Id 写入；publisherName 取该用户的**真实昵称**
     *   （查不到用户时才回退为"管理员"），见 AnnouncementController.add。
     * ★ 标题长度校验：t_announcement.title 是 varchar(200)，超长会以 500 收场。
     */
    public Announcement create(Announcement a) {
        TextLimits.requireWithin(a.getTitle(), TextLimits.ANNOUNCEMENT_TITLE, "公告标题");
        // ★ 正文必须同时有非空校验与长度校验：
        //   t_announcement.content 是 **text NOT NULL** ——
        //   超长或缺字段都会落到 MySQL 报错 → 500「系统繁忙」，而不是 400 + 明确文案。
        if (a.getContent() == null || a.getContent().trim().isEmpty()) {
            throw new BizException("公告正文不能为空");
        }
        TextLimits.requireWithin(a.getContent(), TextLimits.ANNOUNCEMENT_CONTENT, "公告正文");
        announcementMapper.insert(a);
        return a;
    }

    /** 全部公告（管理员管理页用，包含已下架/已过期的）。 */
    public List<Announcement> listAll() {
        return announcementMapper.findAll();
    }

    /**
     * 有效公告（用户登录后弹窗展示用）。
     * ★ SQL 里会过滤掉「已下架」和「已过期」的，所以前端拿到的都是该展示的。
     */
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
