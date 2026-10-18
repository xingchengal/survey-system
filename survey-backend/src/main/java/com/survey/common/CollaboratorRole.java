package com.survey.common;

import java.util.Set;

/**
 * 问卷协作者角色 —— 「被邀请来协作的人对**这份问卷**能做什么」的唯一口径。
 *
 * ── 三种角色的边界（与 §H 的验收口径一致）────────────────
 *   viewer  : 只能看到问卷本身（标题/描述/题目结构），**不能**改、**不能**看答卷数据
 *   editor  : 可以改内容（survey:edit）**并且**看数据（survey:data），**不能**删
 *   analyst : **只能**看数据（survey:data）
 *
 * ★ 为什么 editor 也带 survey:data：编问卷的人通常要对照回收情况调整选项；
 *   而"不能删"才是区分 editor 与作者的关键（删除是不可逆的业务动作，只留给作者/管理员）。
 *
 * ── 与 t_survey_collaborator.collaborator_role 的对应 ──
 *   库里存的是 {@link #getCode()}（小写字符串），读取时用 {@link #fromCode(String)}。
 *   ★ 解析失败（脏数据 / 手工改库写错）返回 null，由调用方**按无权限**处理 ——
 *     不要在 fromCode 里抛异常：一条脏数据不该让整份问卷的所有人都打不开。
 *
 * 关联：common/Permission（权限点）、mapper/SurveyCollaboratorMapper、
 *       sql/migration-rbac.sql（t_survey_collaborator.collaborator_role）
 */
public enum CollaboratorRole {

    /** 只读：看看问卷长什么样，不能改、不能看数据 */
    VIEWER("viewer", Set.of()),

    /** 可编辑 + 可看数据，不能删除 */
    EDITOR("editor", Set.of(Permission.SURVEY_EDIT, Permission.SURVEY_DATA)),

    /** 只能看数据（统计/明细/导出口径由 Service 决定） */
    ANALYST("analyst", Set.of(Permission.SURVEY_DATA));

    private final String code;
    private final Set<String> permissions;

    CollaboratorRole(String code, Set<String> permissions) {
        this.code = code;
        this.permissions = permissions;
    }

    /** 存库用的字符串 */
    public String getCode() {
        return code;
    }

    /** 这个角色拥有的权限点（不可变集合） */
    public Set<String> getPermissions() {
        return permissions;
    }

    /** 是否拥有某个权限点 */
    public boolean has(String permission) {
        return permission != null && permissions.contains(permission);
    }

    /**
     * 按存库字符串解析；未知值返回 null（调用方按"无任何权限"处理）。
     * ★ null / 空串 / 大小写不符 一律 null —— 与 Role.isAdmin 一样不做归一化，
     *   避免"库里写 Editor 也能生效"这种隐性口径。
     */
    public static CollaboratorRole fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (CollaboratorRole r : values()) {
            if (r.code.equals(code)) {
                return r;
            }
        }
        return null;
    }
}
