package com.survey.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解：打在方法上，由 aspect/LogAspect 切面在方法正常返回后写入 t_operation_log。
 *
 * ★ 别和 Swagger 的 {@code @Operation} 混淆：那个只生成接口文档摘要，本注解才写操作日志。
 * value 原样写进 t_operation_log.operation，供管理端筛选；不写不报错，只是该字段为空。
 * 必须是 RUNTIME 保留，切面要在运行时反射读到它。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {
    /** 操作描述，例如"创建问卷"；原样写进 t_operation_log.operation */
    String value() default "";
}
