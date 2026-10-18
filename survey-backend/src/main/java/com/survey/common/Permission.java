package com.survey.common;

import java.util.Set;

/**
 * 权限点 —— **全项目唯一出处**（t_role_permission.permission_code 只允许出现这里的值）。
 *
 * ── 为什么要有权限点，而不是继续判角色 ──────────────────
 *   现在只有 admin / user 两级，判断散落在 {@code @RequireAdmin} 与
 *   {@code OwnershipGuard} 里；一旦出现"能被邀请来看数据、但不能改题"的协作者，
 *   "角色"就不够用了 —— 需要"这个用户对**这份问卷**能做什么"。
 *   权限点是这个问题的中间层：角色 → 权限点集合；问卷 → 协作者角色 → 再映射成权限点。
 *
 * ── 与 @RequireAdmin 的关系 ───────────────────────────
 *   并存。{@code @RequireAdmin} 管"是不是管理员"，本类管"有没有这项能力"；
 *   admin 在切面里**兜底放行**（见 aspect/PermissionAspect），
 *   所以给 admin 角色也配满权限点只是为了让矩阵可展示、可审计，不是判定依据。
 *
 * ── 数字/字符串约定 ───────────────────────────────────
 *   ★ 权限点字符串**一旦上线就不要改名**：它同时存在于数据库、注解、审计表与接口文档。
 *     要废弃就新增一个，旧的在迁移脚本里删，不要原地改。
 *   ★ 写法固定为 `资源:动作`，全小写；不要出现下划线/驼峰混用。
 *
 * 关联：annotation/RequirePermission、aspect/PermissionAspect、mapper/RoleMapper、
 *       sql/migration-rbac.sql（种子数据）、common/CollaboratorRole（协作者 → 权限点）
 */
public final class Permission {

    /** 改问卷内容（标题/描述/设置/题目与选项） */
    public static final String SURVEY_EDIT = "survey:edit";

    /** 发布 / 重新发布（对外可见的那一刻） */
    public static final String SURVEY_PUBLISH = "survey:publish";

    /** 看答卷数据（统计、明细、导出） */
    public static final String SURVEY_DATA = "survey:data";

    /** 删除（移入回收站 / 彻底删除） */
    public static final String SURVEY_DELETE = "survey:delete";

    /** 用户管理（管理端用户列表、禁用、恢复） */
    public static final String USER_MANAGE = "user:manage";

    /** 处理举报（H1 才真正用上；先占位，避免上线后再改注解） */
    public static final String REVIEW_HANDLE = "review:handle";

    /** 额度钱包管理（H5） */
    public static final String WALLET_MANAGE = "wallet:manage";

    /**
     * 全部权限点（不可变）。
     * ★ 用 Set.of：重复值会在类初始化时直接抛异常 —— 比"跑起来才发现漏配"安全。
     * ★ 新增权限点时**必须**同时改这里与 sql/migration-rbac.sql 的种子数据，
     *   否则 PermissionTest 会先红。
     */
    public static final Set<String> ALL = Set.of(
            SURVEY_EDIT, SURVEY_PUBLISH, SURVEY_DATA, SURVEY_DELETE,
            USER_MANAGE, REVIEW_HANDLE, WALLET_MANAGE);

    private Permission() {
    }

    /** 是否合法权限点（区分大小写；null/空一律 false） */
    public static boolean isValid(String code) {
        return code != null && ALL.contains(code);
    }
}
