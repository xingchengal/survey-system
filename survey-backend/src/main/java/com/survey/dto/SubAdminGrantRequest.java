package com.survey.dto;

import java.util.List;

/**
 * 新增 / 修改次级管理员权限的请求体：userId + permissions，至少一项。
 * ★ 只认 ADMIN_GRANTABLE（模块级）；对象级 4 个（edit/publish/data/delete）永不发给次级管理员。
 * 不接收 role：角色只能由服务端写成 subadmin/user。
 */
public class SubAdminGrantRequest {

    /** 目标用户 id（新增时必填；修改权限时以路径变量为准） */
    private Integer userId;

    /** 模块权限点；必须属于 Permission.ADMIN_GRANTABLE，至少一项、自动去重 */
    private List<String> permissions;

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) { this.permissions = permissions; }
}
