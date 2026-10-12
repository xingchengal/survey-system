package com.survey.controller;

import com.survey.annotation.RequireAdmin;
import com.survey.service.DemoDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 演示数据接口（薄壳）。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   DemoData.vue（管理员菜单「演示数据」页），两个按钮分别对应 /init 和 /reset。
 *
 * ── 权限 ──────────────────────────────────────────────
 *   **类级 @RequireAdmin**：两个端点都需管理员，由 aspect/AdminCheckAspect 统一实施。
 *   ★ 业务与事务都在 DemoDataService：若把 Mapper 与 @Transactional 搬回这里，
 *     事务边界会散到 Web 层，这段逻辑也没法在 Service 层复用或单测。
 *
 * ── 生产环境不注册 ────────────────────────────────────
 *   @Profile("!prod")：/init 会批量创建密码统一为 123456 的 demo_user* 真实账号，
 *   生产误点一次就多 20 个可被撞库的弱口令入口。
 */
@Tag(name = "演示数据", description = "生成/重置演示数据（测试用，仅管理员）")
@RestController
@RequestMapping("/api/demo")
@Profile("!prod")
@RequireAdmin
public class DemoDataController {

    /** 业务与事务都在 DemoDataService，本类只做"声明权限 + 转发"
     *   （Controller 层不注入 Mapper、不写 @Transactional）。 */
    @Autowired
    private DemoDataService demoDataService;

    @Operation(summary = "生成演示数据（仅管理员）")
    @PostMapping("/init")
    public Map<String, Object> init() {
        return demoDataService.init();
    }

    @Operation(summary = "重置演示数据（清空所有演示数据，仅管理员）")
    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        return demoDataService.reset();
    }
}
