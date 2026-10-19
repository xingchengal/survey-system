package com.survey.controller;

import com.survey.annotation.RequireAdmin;
import com.survey.service.TemplateSeederService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 模板种子接口（薄壳）：重建库后恢复「模板广场」的预置模板，前端无调用入口。
 * ★ @Profile("!prod") + 类级 @RequireAdmin；生产需要播种时临时用 local 启动跑一次。
 * ★ 幂等：已有 userId=0 的预置模板就直接返回 skipped，无脑 INSERT 会让模板重复污染广场。
 */
@Tag(name = "模板种子", description = "初始化系统预置模板（仅管理员）")
@RestController
@RequestMapping("/api/template-seeder")
@Profile("!prod")
@RequireAdmin
public class TemplateSeederController {

    /** 业务与事务都在 TemplateSeederService，本类只做「声明权限 + 转发」（不注入 Mapper、不写 @Transactional）。 */
    @Autowired
    private TemplateSeederService templateSeederService;

    @Operation(summary = "初始化预置模板（仅管理员）")
    @PostMapping("/init")
    public Map<String, Object> init() {
        return templateSeederService.init();
    }
}
