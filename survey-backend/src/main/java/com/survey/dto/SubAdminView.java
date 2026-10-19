package com.survey.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 次级管理员列表项：用户信息 + 逐人权限点（只含 ADMIN_GRANTABLE）；超管不进这个列表（只查 role=subadmin）。
 *
 * @param userId      用户 id
 * @param username    账号
 * @param nickname    昵称（前端优先展示）
 * @param disabled    是否被禁用（0/1）
 * @param createdTime 账号创建时间
 * @param permissions 被授予的模块权限点（只含 ADMIN_GRANTABLE）
 */
public record SubAdminView(
        Integer userId,
        String username,
        String nickname,
        Integer disabled,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdTime,
        List<String> permissions) {
}
