package com.survey.common;

import java.util.Set;

/**
 * 问卷协作者角色 —— 「被邀请的人对**这份问卷**能做什么」的唯一口径。
 * viewer 只看问卷本身；editor 可改内容 + 看数据、不能删；analyst 只能看数据。
 * ★ 解析失败（脏数据 / 手工改库写错）返回 null，由调用方按无权限处理 ——
 *   不要在 fromCode 里抛异常：一条脏数据不该让整份问卷的人都打不开。
 */
public enum CollaboratorRole {

    /** 只读：看得到问卷结构，不能改、不能看数据 */
    VIEWER("viewer", Set.of()),

    /** 可编辑 + 可看数据（编问卷常要对照回收情况调选项），不能删除 */
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
     * ★ 不做大小写 / 空白归一化 —— 避免"库里写 Editor 也能生效"这种隐性口径。
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
