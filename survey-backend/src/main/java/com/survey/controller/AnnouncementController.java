package com.survey.controller;

import com.survey.annotation.RequireAdmin;
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
 * 系统公告：管理员发布/上下架/删除公告，所有用户读取未过期公告。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   AnnouncementManage.vue  → 管理员的公告管理页（add/list/status/delete）
 *   AdminLayout.vue         → 用户登录后拉 /active，用弹窗展示未读公告
 *                             （已读记录存在 localStorage：readAnnouncements_<userId>）
 *
 * ── 权限矩阵（详见 docs/问卷系统-端点权限审计表.md，随仓库入库）──
 *   公开(白名单) : GET /active  —— 未登录也要能拿，方便登录页显示公告
 *   需管理员     : POST /add、GET /list、PUT /status/{id}、DELETE /delete/{id}
 *
 * ── 数据表 ────────────────────────────────────────────
 *   t_announcement（含 status 上下架标记与 expireTime 过期时间，/active 会过滤过期项）
 */
@Tag(name = "系统公告", description = "管理员发布公告，用户查看未过期公告")
@RestController
@RequestMapping("/api/announcement")
public class AnnouncementController {

    @Autowired
    private AnnouncementService announcementService;

    /** 用于把公告的发布者写成**真实昵称**（查不到发布者时才回退"管理员"）。 */
    @Autowired
    private UserService userService;

    @OperationLog("发布公告")
    @Operation(summary = "发布公告（管理员）")
    @PostMapping("/add")
    @RequireAdmin
    public Map<String, Object> add(@RequestBody Announcement announcement,
                                   @RequestHeader("X-User-Id") Integer userId) {
        // ★ 失败**抛 BizException**（400），不是 {success:false}（HTTP 200）：
        //   失败返 200 时前端必须判断 data.success，漏判就会把"无权限"当成功；现在只看状态码。
        announcement.setPublisherId(userId);
        // ★ 发布者名字取**真实昵称**（回退用户名），与 LogAspect / GlobalExceptionHandler 的取法一致。
        //   ★ 若在这里把名字**硬编码成 "管理员"**：系统里不止一个管理员时，公告列表会全部显示
        //   "管理员" —— "这条公告到底是谁发的"这条信息在库里就永久丢了，出事也没法追溯。
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
    @RequireAdmin
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
    @RequireAdmin
    public String updateStatus(@PathVariable Integer id,
                               @RequestParam Integer status) {
        // ★ 上下架只能是 0/1：若接受任意整数，传 status=2 会让
        //   /active 的 `status = 1` 过滤永远匹配不到它 —— 公告从"有效列表"和
        //   "管理列表的状态标签"两边同时语义失真（管理页的上下架按钮也会显示错）。
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
    @RequireAdmin
    public String delete(@PathVariable Integer id) {
        int r = announcementService.delete(id);
        if (r <= 0) throw new BizException("删除失败");
        return "删除成功";
    }
}