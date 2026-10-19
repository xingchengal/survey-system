package com.survey.common;

import java.util.Set;

/**
 * 权限点唯一出处：字符串一旦上线不要改名（数据库、注解、审计表、接口文档都用它）。
 * 写法固定 `资源:动作`，全小写。
 *
 * ★ {@code @RequireAdmin} 管“是不是超管”，本类管“有没有这项能力”；admin 在切面兜底放行。
 * ★ 对象级 4 个（edit/publish/data/delete）只给作者/协作者，不发给次级管理员。
 * ★ 新增权限点必须同步 ALL、init.sql / migration 种子与 docs/AUDIT.md。
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

    /** 处理举报（审核/驳回/取消下架） */
    public static final String REVIEW_HANDLE = "review:handle";

    /** 额度钱包管理（H5） */
    public static final String WALLET_MANAGE = "wallet:manage";

    // ===== 模块级权限点（次级管理员可被逐项分配；超管兜底放行） =====

    /** 管理端「全部问卷 / 回收站 / 导出」 */
    public static final String SURVEY_MANAGE = "survey:manage";

    /** 模板管理（设为模板、改分类、编辑模板内容） */
    public static final String TEMPLATE_MANAGE = "template:manage";

    /** 公告管理 */
    public static final String ANNOUNCEMENT_MANAGE = "announcement:manage";

    /** 操作日志 / 异常日志查看（清空、删除仍是超管专属） */
    public static final String LOG_VIEW = "log:view";

    /** 处理反馈与客服工单 */
    public static final String FEEDBACK_HANDLE = "feedback:handle";

    /** 系统级资源视图（统计页「资源」模块：数据库大小 / 表行数 / 版本快照）；不再等于全站业务数据 */
    public static final String SYSTEM_VIEW = "system:view";

    /**
     * 全部权限点（不可变）。
     * ★ Set.of 对重复值会在类初始化时直接抛异常，比“跑起来才发现漏配”安全。
     * ★ 新增权限点必须同时改这里与 sql/init.sql / migration 种子，否则 PermissionTest 先红。
     */
    public static final Set<String> ALL = Set.of(
            SURVEY_EDIT, SURVEY_PUBLISH, SURVEY_DATA, SURVEY_DELETE,
            USER_MANAGE, REVIEW_HANDLE, WALLET_MANAGE,
            SURVEY_MANAGE, TEMPLATE_MANAGE, ANNOUNCEMENT_MANAGE,
            LOG_VIEW, FEEDBACK_HANDLE, SYSTEM_VIEW);

    /**
     * 可分配给次级管理员的权限点：只含模块级，不含对象级 4 个与超管专属项。
     */
    public static final Set<String> ADMIN_GRANTABLE = Set.of(
            SURVEY_MANAGE, TEMPLATE_MANAGE, USER_MANAGE, REVIEW_HANDLE,
            WALLET_MANAGE, ANNOUNCEMENT_MANAGE, LOG_VIEW, FEEDBACK_HANDLE, SYSTEM_VIEW);

    private Permission() {
    }

    /** 是否合法权限点（区分大小写；null/空一律 false） */
    public static boolean isValid(String code) {
        return code != null && ALL.contains(code);
    }

    /** 是否可分配给次级管理员（用于管理端候选清单与授权校验）。 */
    public static boolean isAdminGrantable(String code) {
        return code != null && ADMIN_GRANTABLE.contains(code);
    }
}
