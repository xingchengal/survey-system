package com.survey.dto;

/**
 * 可分配给次级管理员的权限选项（管理端勾选框用）。
 *
 * @param code  权限点字符串（与 common/Permission 一致）
 * @param label 中文展示名
 */
public record AdminPermissionOption(String code, String label) {
}
