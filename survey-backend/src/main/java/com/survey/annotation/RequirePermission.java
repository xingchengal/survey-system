package com.survey.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「需要某个权限点才能访问」的 Controller 类或方法 —— 由
 * {@link com.survey.aspect.PermissionAspect} 统一实施。
 *
 * ── 与 @RequireAdmin 的分工 ────────────────────────────
 *   · {@code @RequireAdmin} ：只认"是不是管理员"，粒度粗、已经覆盖了 20 个端点；
 *   · {@code @RequirePermission}：认"有没有这项能力"，给协作者/多角色用。
 *   两者**并存**，不要为了统一而把已有的 @RequireAdmin 全换掉 —— 那是 H2-6 的事，
 *   要连同审计表与 20 条矩阵断言一起改。
 *   ★ admin 在切面里**兜底放行**：给管理员逐条配权限点只会增加漏配风险。
 *
 * ── 语义：value 里列出的权限点**全部**都需要（AND）────────
 *   ★ 没提供"任一满足即可（OR）"：OR 语义会让"少配一个权限"变成隐性放行，
 *     而这正是权限系统最不该有的失败方向。需要 OR 时请在 Service 里显式判断。
 *
 * ── 用法 ──────────────────────────────────────────────
 * <pre>
 *   {@code @RequirePermission(Permission.USER_MANAGE)}   // 单个
 *   {@code @RequirePermission({Permission.SURVEY_EDIT, Permission.SURVEY_DATA})}  // 两个都要有
 * </pre>
 * ★ 传字符串字面量会失去编译期检查 —— 一律用 {@link com.survey.common.Permission} 的常量；
 *   {@link com.survey.aspect.PermissionAspect} 在运行期还会校验"是不是合法权限点"，
 *   写错（如 "survey:editt"）会**抛 500 并打日志**，而不是静默放行。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 需要的权限点（全部满足才放行）。取值必须来自 common/Permission */
    String[] value();
}
