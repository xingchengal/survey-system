package com.survey.controller;

import com.survey.annotation.RequirePermission;
import com.survey.common.Permission;
import com.survey.entity.Announcement;
import com.survey.entity.User;
import com.survey.service.AnnouncementService;
import com.survey.service.UserService;
import com.survey.annotation.OperationLog;
import com.survey.common.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统公告：管理员发布 / 上下架 / 删除，登录用户读取未过期公告。
 * ★ GET /active 是公开白名单：登录页未登录也要能显示公告；其余端点按 announcement:manage 校验。
 * /active 只返回 status=1 且未过期的公告。
 */
@Tag(name = "系统公告", description = "管理员发布公告，用户查看未过期公告")
@RestController
@RequestMapping("/api/announcement")
public class AnnouncementController {

    @Autowired
    private AnnouncementService announcementService;

    /** 发布者写成真实昵称（查不到发布者时才回退"管理员"）。 */
    @Autowired
    private UserService userService;

    @OperationLog("发布公告")
    @Operation(summary = "发布公告（管理员）")
    @PostMapping("/add")
    @RequirePermission(Permission.ANNOUNCEMENT_MANAGE)
    public Map<String, Object> add(@RequestBody Announcement announcement,
                                   @RequestHeader("X-User-Id") Integer userId) {
        // 失败抛 BizException（400）而非 200+{success:false}：前端只看状态码，漏判会把失败当成功。
        announcement.setPublisherId(userId);
        // 发布者取真实昵称：硬编码成"管理员"会让多管理员场景的发布人信息永久丢失，出事无法追溯。
        User publisher = userService.getById(userId);
        announcement.setPublisherName(
                publisher == null ? "管理员"
                        : (publisher.getNickname() != null && !publisher.getNickname().isBlank()
                                ? publisher.getNickname() : publisher.getUsername()));
        announcementService.create(announcement);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "发布成功");
        return result;
    }

    @Operation(summary = "查询全部公告（管理员）")
    @GetMapping("/list")
    @RequirePermission(Permission.ANNOUNCEMENT_MANAGE)
    public List<Announcement> list() {
        return announcementService.listAll();
    }

    @Operation(summary = "查询有效公告（用户登录后调用）")
    @GetMapping("/active")
    public List<Announcement> active() {
        return announcementService.listActive();
    }

    @Operation(summary = "上下架公告（管理员）")
    @PutMapping("/status/{id}")
    @RequirePermission(Permission.ANNOUNCEMENT_MANAGE)
    public String updateStatus(@PathVariable Integer id,
                               @RequestParam Integer status) {
        // status 只认 0/1：其它值会让 /active 的过滤与列表状态标签同时失真。
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException("公告状态只能是 0（下架）或 1（上架）");
        }
        int r = announcementService.updateStatus(id, status);
        if (r <= 0) throw new BizException("操作失败");
        return "操作成功";
    }

    @OperationLog("删除公告")
    @Operation(summary = "删除公告（管理员）")
    @DeleteMapping("/delete/{id}")
    @RequirePermission(Permission.ANNOUNCEMENT_MANAGE)
    public String delete(@PathVariable Integer id) {
        int r = announcementService.delete(id);
        if (r <= 0) throw new BizException("删除失败");
        return "删除成功";
    }
}