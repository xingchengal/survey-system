package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.common.Role;
import com.survey.dto.SubAdminView;
import com.survey.entity.User;
import com.survey.mapper.RoleMapper;
import com.survey.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 次级管理员授权规则（Mockito，不连库）。
 *
 * ★ 钉住安全边界：
 *   · 超管不能被设/改/降；
 *   · 只发 ADMIN_GRANTABLE（对象级 survey:edit 等不能发）；
 *   · 角色与权限必须一起重建（先清后插）；
 *   · 降级保留账号与数据，只回退角色/权限。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminManagementServiceTest {

    private static final Integer TARGET = 500;
    private static final Integer OPERATOR = 1;

    @Mock
    private UserMapper userMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AdminManagementService service;

    private static User user(Integer id, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setNickname("用户" + id);
        u.setRole(role);
        u.setDisabled(0);
        return u;
    }

    private void stubRoles() {
        when(roleMapper.findRoleIdByCode(Role.SUB_ADMIN)).thenReturn(3);
        when(roleMapper.findRoleIdByCode(Role.USER)).thenReturn(2);
        when(userMapper.findActiveUsers(null, Role.SUB_ADMIN, null)).thenReturn(List.of());
    }

    @Test
    @DisplayName("★ 提升普通用户：改 role、重建角色与权限，只发允许的模块权限")
    void promoteNormalUser() {
        when(userMapper.findByIdIncludeDeleted(TARGET)).thenReturn(user(TARGET, Role.USER));
        stubRoles();

        service.promote(TARGET, List.of(Permission.TEMPLATE_MANAGE, Permission.FEEDBACK_HANDLE), OPERATOR);

        verify(userMapper).updateRole(TARGET, Role.SUB_ADMIN);
        verify(roleMapper).deleteUserRoles(TARGET);
        verify(roleMapper).insertUserRole(TARGET, 3);
        verify(roleMapper).deleteUserPermissions(TARGET);
        verify(roleMapper).insertUserPermission(TARGET, Permission.TEMPLATE_MANAGE, OPERATOR);
        verify(roleMapper).insertUserPermission(TARGET, Permission.FEEDBACK_HANDLE, OPERATOR);
        // ★ 升级后旧票必须作废：否则对方仍拿着 user token 看不到管理端
        verify(userMapper).updateTokenValidAfter(eq(TARGET), any(java.time.LocalDateTime.class));
        verify(refreshTokenService).revokeAllByUser(TARGET);
    }

    @Test
    @DisplayName("★ 超管不能被设为次级管理员，也不能被改权限/降级")
    void superAdminIsProtected() {
        when(userMapper.findByIdIncludeDeleted(TARGET)).thenReturn(user(TARGET, Role.ADMIN));

        assertThrows(BizException.class,
                () -> service.promote(TARGET, List.of(Permission.TEMPLATE_MANAGE), OPERATOR));
        assertThrows(BizException.class,
                () -> service.updatePermissions(TARGET, List.of(Permission.TEMPLATE_MANAGE), OPERATOR));
        assertThrows(BizException.class, () -> service.demote(TARGET, OPERATOR));

        verify(userMapper, never()).updateRole(anyInt(), anyString());
        verify(userMapper, never()).updateTokenValidAfter(anyInt(), any(java.time.LocalDateTime.class));
        verify(refreshTokenService, never()).revokeAllByUser(anyInt());
    }

    @Test
    @DisplayName("★ 权限不能为空、不能是对象级 survey:edit、不能拼错")
    void permissionsMustBeAdminGrantable() {
        when(userMapper.findByIdIncludeDeleted(TARGET)).thenReturn(user(TARGET, Role.USER));

        assertThrows(BizException.class, () -> service.promote(TARGET, List.of(), OPERATOR));
        assertThrows(BizException.class, () -> service.promote(TARGET, null, OPERATOR));
        assertThrows(BizException.class,
                () -> service.promote(TARGET, List.of(Permission.SURVEY_EDIT), OPERATOR));
        assertThrows(BizException.class,
                () -> service.promote(TARGET, List.of("template:manag"), OPERATOR));

        verify(userMapper, never()).updateRole(anyInt(), anyString());
    }

    @Test
    @DisplayName("★ 权限去重：重复项只插一次")
    void permissionsAreDeduplicated() {
        when(userMapper.findByIdIncludeDeleted(TARGET)).thenReturn(user(TARGET, Role.USER));
        stubRoles();

        service.promote(TARGET, List.of(Permission.TEMPLATE_MANAGE,
                Permission.TEMPLATE_MANAGE, Permission.FEEDBACK_HANDLE), OPERATOR);

        verify(roleMapper, times(1)).insertUserPermission(eq(TARGET), eq(Permission.TEMPLATE_MANAGE), eq(OPERATOR));
        verify(roleMapper, times(1)).insertUserPermission(eq(TARGET), eq(Permission.FEEDBACK_HANDLE), eq(OPERATOR));
    }

    @Test
    @DisplayName("修改权限：目标必须是次级管理员，且只重建权限、不改角色")
    void updatePermissionsRequiresSubAdmin() {
        when(userMapper.findByIdIncludeDeleted(TARGET)).thenReturn(user(TARGET, Role.USER));
        assertThrows(BizException.class,
                () -> service.updatePermissions(TARGET, List.of(Permission.FEEDBACK_HANDLE), OPERATOR));

        when(userMapper.findByIdIncludeDeleted(TARGET)).thenReturn(user(TARGET, Role.SUB_ADMIN));
        service.updatePermissions(TARGET, List.of(Permission.FEEDBACK_HANDLE), OPERATOR);

        verify(roleMapper).deleteUserPermissions(TARGET);
        verify(roleMapper).insertUserPermission(TARGET, Permission.FEEDBACK_HANDLE, OPERATOR);
        verify(userMapper, never()).updateRole(anyInt(), anyString());
        // ★ 改权限也要让旧票失效：否则前端菜单与后端权限对不上
        verify(refreshTokenService).revokeAllByUser(TARGET);
    }

    @Test
    @DisplayName("降级：角色回 user、角色表重建、权限清空；不删用户")
    void demoteRestoresUserRole() {
        when(userMapper.findByIdIncludeDeleted(TARGET)).thenReturn(user(TARGET, Role.SUB_ADMIN));
        stubRoles();

        service.demote(TARGET, OPERATOR);

        verify(userMapper).updateRole(TARGET, Role.USER);
        verify(roleMapper).deleteUserRoles(TARGET);
        verify(roleMapper).insertUserRole(TARGET, 2);
        verify(roleMapper).deleteUserPermissions(TARGET);
        verify(userMapper, never()).deleteById(any());
        // ★ 降级后旧 token / refresh token 都要作废：否则人还能留在管理端页面
        verify(userMapper).updateTokenValidAfter(eq(TARGET), any(java.time.LocalDateTime.class));
        verify(refreshTokenService).revokeAllByUser(TARGET);
    }

    @Test
    @DisplayName("列表：只返回 ADMIN_GRANTABLE 内的权限点（脏数据过滤）")
    void listFiltersUnknownPermissions() {
        User sub = user(TARGET, Role.SUB_ADMIN);
        when(userMapper.findActiveUsers(null, Role.SUB_ADMIN, null)).thenReturn(List.of(sub));
        when(roleMapper.findPermissionCodesByUserId(TARGET))
                .thenReturn(List.of(Permission.TEMPLATE_MANAGE, "legacy:junk", Permission.SURVEY_EDIT));

        List<SubAdminView> list = service.listSubAdmins();

        assertEquals(1, list.size());
        assertEquals(List.of(Permission.TEMPLATE_MANAGE), list.get(0).permissions());
    }

    @Test
    @DisplayName("已注销账号不能被提升/授权")
    void deletedUserIsRejected() {
        User deleted = user(TARGET, Role.USER);
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        when(userMapper.findByIdIncludeDeleted(TARGET)).thenReturn(deleted);

        assertThrows(BizException.class,
                () -> service.promote(TARGET, List.of(Permission.TEMPLATE_MANAGE), OPERATOR));
        verify(userMapper, never()).updateRole(anyInt(), anyString());
    }
}
