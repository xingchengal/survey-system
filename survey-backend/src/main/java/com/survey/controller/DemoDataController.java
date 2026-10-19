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
 * 演示数据接口（薄壳）：生成 / 重置测试用数据。
 * ★ @Profile("!prod")：/init 批量创建密码统一 123456 的 demo_user* 账号，生产误点即开撞库入口。
 * ★ 类级 @RequireAdmin；/reset 清空全部演示数据，不可恢复。
 * 业务与事务都在 DemoDataService，本类只声明权限 + 转发。
 */
@Tag(name = "演示数据", description = "生成/重置演示数据（测试用，仅管理员）")
@RestController
@RequestMapping("/api/demo")
@Profile("!prod")
@RequireAdmin
public class DemoDataController {

    /** 业务与事务都在 DemoDataService，本类只做「声明权限 + 转发」（不注入 Mapper、不写 @Transactional）。 */
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
