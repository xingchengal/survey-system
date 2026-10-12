package com.survey.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「必须是管理员才能访问」的 Controller 类或方法。
 *
 * ── 为什么要有它 ──────────────────────────────────────
 *   手写校验（每个端点各写一段 `if (!"admin".equals(role)) throw ...`）漏一行就是横向越权，
 *   而且**不会有任何编译期或运行期提示** —— 全站统计、别人的答卷这类端点都曾因此漏过校验。
 *
 *   现在改成声明式：需要管理员的端点只写 {@code @RequireAdmin}，
 *   由 {@link com.survey.aspect.AdminCheckAspect} 统一实施（读 JwtAuthFilter 注入的 X-User-Role）。
 *
 * ── 用法 ──────────────────────────────────────────────
 *   · 整个类都是管理接口（如 OperationLogController）→ 标在**类**上；
 *   · 类里只有部分方法是管理接口（如 AnnouncementController：/active 是公开的）→ 标在**方法**上。
 *
 * ── 为什么不做 @RequireOwner ──────────────────────────
 *   "问卷作者或管理员"这类**归属校验**需要按资源 id 查库、不同资源规则也不同
 *   （问卷看 user_id、答卷要反查所属问卷）。把它强行注解化会引入一个
 *   "注解 → 资源类型 → 查询方式"的映射表，可读性反而下降。
 *   所以归属校验由 common/OwnershipGuard 统一实现（端点只调用、不重写），
 *   本注解只负责**角色**这一半 —— 这正是漏写最多、最机械的那一半。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
