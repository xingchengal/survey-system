package com.survey.common;

/**
 * 角色常量唯一口径。来源是 JWT claim 与 varchar 列，故用常量 + 静态方法而非 enum。
 *
 * ★ {@code @RequireAdmin} 只认 {@link #ADMIN}；{@link #SUB_ADMIN} 只是 staff，
 *   模块权限必须走 {@code @RequirePermission}。
 * ★ isAdmin / isStaff 都容忍 null（匿名请求）。
 */
public final class Role {

    /** 管理员（超级管理员） */
    public static final String ADMIN = "admin";

    /**
     * 次级管理员：staff，但不自动拥有任何管理端点 —— 只认逐人授予的权限点。
     */
    public static final String SUB_ADMIN = "subadmin";

    /** 普通用户 */
    public static final String USER = "user";

    private Role() {
    }

    /**
     * 是否管理员（超级管理员）。
     * ★ role 为 null（未登录 / 匿名访客）时返回 false —— 调用方不必再判空。
     */
    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }

    /**
     * 是否“管理端人员”（超级管理员或次级管理员）。
     *
     * ★ 只用于**数据范围/归属放行**（例如 staff 可以查看全部问卷），
     *   **不能**用来替代权限点判断 —— 次级管理员能进管理端，不代表他能打开某个模块。
     * ★ null / user / 未知值一律 false。
     */
    public static boolean isStaff(String role) {
        return ADMIN.equals(role) || SUB_ADMIN.equals(role);
    }
}
