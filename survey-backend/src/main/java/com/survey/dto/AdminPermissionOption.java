package com.survey.dto;

/**
 * 管理端勾选框的权限目录项：code + 中文名。
 * ★ 只列 ADMIN_GRANTABLE（模块级）：对象级 4 个与超管专属项不出现。
 *
 * @param code  权限点字符串（与 common/Permission 一致）
 * @param label 中文展示名
 */
public record AdminPermissionOption(String code, String label) {
}
