package com.survey.controller;

import com.survey.entity.Announcement;
import com.survey.service.AnnouncementService;
import com.survey.annotation.OperationLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "系统公告", description = "管理员发布公告，用户查看未过期公告")
@RestController
@RequestMapping("/api/announcement")
public class AnnouncementController {

    @Autowired
    private AnnouncementService announcementService;

    @OperationLog("发布公告")
    @Operation(summary = "发布公告（管理员）")
    @PostMapping("/add")
    public Map<String, Object> add(@RequestBody Announcement announcement,
                                   @RequestHeader("X-User-Id") Integer userId,
                                   @RequestHeader("X-User-Role") String role) {
        Map<String, Object> result = new HashMap<>();
        if (!"admin".equals(role)) {
            result.put("success", false);
            result.put("message", "无权限");
            return result;
        }
        announcement.setPublisherId(userId);
        announcement.setPublisherName("管理员");
        announcementService.create(announcement);
        result.put("success", true);
        result.put("message", "发布成功");
        return result;
    }

    @Operation(summary = "查询全部公告（管理员）")
    @GetMapping("/list")
    public List<Announcement> list(@RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) return List.of();
        return announcementService.listAll();
    }

    @Operation(summary = "查询有效公告（用户登录后调用）")
    @GetMapping("/active")
    public List<Announcement> active() {
        return announcementService.listActive();
    }

    @Operation(summary = "上下架公告（管理员）")
    @PutMapping("/status/{id}")
    public String updateStatus(@PathVariable Integer id,
                               @RequestParam Integer status,
                               @RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) return "无权限";
        int r = announcementService.updateStatus(id, status);
        return r > 0 ? "操作成功" : "操作失败";
    }

    @OperationLog("删除公告")
    @Operation(summary = "删除公告（管理员）")
    @DeleteMapping("/delete/{id}")
    public String delete(@PathVariable Integer id,
                         @RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) return "无权限";
        int r = announcementService.delete(id);
        return r > 0 ? "删除成功" : "删除失败";
    }
}