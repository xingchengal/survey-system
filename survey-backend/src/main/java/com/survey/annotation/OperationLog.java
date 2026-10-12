package com.survey.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解 —— 打在方法上，由 aspect/LogAspect.java 切面自动记录。
 *
 * ── 怎么用 ────────────────────────────────────────────
 *   ＠OperationLog("发布问卷")
 *   ＠PostMapping("/publish/{id}")
 *   public Survey publish(...) { ... }
 *   方法正常返回后，LogAspect 会把 "发布问卷" 连同当前用户、类名.方法名、IP 写进 t_operation_log。
 *
 * ── 三个元注解的作用 ──────────────────────────────────
 *   @Target(METHOD)      只能打在方法上（本项目也只用在 Controller 方法上）
 *   @Retention(RUNTIME)  切面要在运行时反射读到它，所以必须是 RUNTIME
 *   @Documented          出现在 JavaDoc 里
 *
 * ── ★ 注意别和 @Operation 混淆（很常见）────────────────
 *   @OperationLog（本注解，com.survey.annotation）
 *       → 本项目自己的，作用是**写操作日志**
 *   @Operation（io.swagger.v3.oas.annotations.Operation）
 *       → Swagger / Knife4j 的，作用是**生成接口文档的摘要**
 *   两个注解经常同时出现在一个方法上，含义完全不同。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {
    /**
     * 操作描述，例如"创建问卷"。
     * ★ 会原样写进 t_operation_log.operation 字段，供管理端筛选；
     *   不写也不会报错，只是日志里该字段为空。
     */
    String value() default "";
}
