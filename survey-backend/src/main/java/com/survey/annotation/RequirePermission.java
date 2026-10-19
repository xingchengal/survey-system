package com.survey.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「需要某个权限点才能访问」的 Controller 类或方法，由 {@link com.survey.aspect.PermissionAspect} 统一实施。
 *
 * ★ value 里列出的权限点**全部**都需要（AND）；不提供 OR —— OR 会让「少配一个权限」变成隐性放行。
 * 取值一律用 {@link com.survey.common.Permission} 常量；写错会在运行期抛 500 并打日志，不会静默放行。
 * admin 在切面里兜底放行；与 {@code @RequireAdmin} 并存，不必为了统一而替换已有注解。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 需要的权限点（全部满足才放行）。取值必须来自 common/Permission */
    String[] value();
}
