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
 * 模板种子接口（薄壳）。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   前端没有调用入口 —— 它是运维/初始化工具（重建数据库后恢复「模板广场」的 31 个模板）。
 *
 * ── 权限 ──────────────────────────────────────────────
 *   **类级 @RequireAdmin**（由 aspect/AdminCheckAspect 统一实施，方法里不再手写校验）。
 *
 * ── 生产环境不注册 ────────────────────────────────────
 *   @Profile("!prod")。★ 注意：模板本身是「模板广场」的数据来源，生产重建库时**需要**跑一次 ——
 *   那种场景请临时用 --spring.profiles.active=local 启动，执行后再切回 prod。
 *
 * ── 幂等 ──────────────────────────────────────────────
 *   先查库里是否已有**预置模板（user_id = 0）**，有就直接返回 {count:0, skipped:N} ——
 *   若改成无脑 INSERT，点两次就 31→62，重复模板会直接污染「模板广场」。
 *   ★ 判据是"预置模板"而不是"任意模板"：管理员把自建问卷设为模板，不会阻塞播种。
 */
@Tag(name = "模板种子", description = "初始化系统预置模板（仅管理员）")
@RestController
@RequestMapping("/api/template-seeder")
@Profile("!prod")
@RequireAdmin
public class TemplateSeederController {

    /** 业务与事务都在 TemplateSeederService，本类只做"声明权限 + 转发"
     *   （Controller 层不注入 Mapper、不写 @Transactional）。 */
    @Autowired
    private TemplateSeederService templateSeederService;

    @Operation(summary = "初始化预置模板（仅管理员）")
    @PostMapping("/init")
    public Map<String, Object> init() {
        return templateSeederService.init();
    }
}
