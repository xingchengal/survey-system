package com.survey.common;

/**
 * 角色常量 —— 全项目角色判断的唯一口径。
 *
 * ── 为什么必须收口在这里 ────────────────────────────────
 *   {@code "admin"} 这个字面量一旦散落在切面、过滤器、各 Controller 里，
 *   想改名或新增角色（如"协作者 / 只读分享者"）时就没有收口点，
 *   只能全文替换，**漏一处就是一个静默的越权口径不一致**。
 *
 * ── 为什么用「常量 + 静态方法」而不是 enum ──────────────
 *   与 SurveyStatus / QuestionType 同样的理由：
 *   角色的来源是 JWT 的 claim（String）与数据库列（varchar），实体字段也是 String。
 *   直接比较枚举要到处 valueOf / 判空，反而更容易写错；
 *   而 {@link #isAdmin(String)} 内部是常量.equals(...)，**天然容忍 null**（匿名请求）。
 *
 * 关联：filter/JwtAuthFilter（把 role 写进 X-User-Role）、aspect/AdminCheckAspect、
 *       common/OwnershipGuard（归属校验）。
 */
public final class Role {

    /** 管理员 */
    public static final String ADMIN = "admin";

    /** 普通用户 */
    public static final String USER = "user";

    private Role() {
    }

    /**
     * 是否管理员。
     * ★ role 为 null（未登录 / 匿名访客）时返回 false —— 调用方不必再判空。
     */
    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }
}
