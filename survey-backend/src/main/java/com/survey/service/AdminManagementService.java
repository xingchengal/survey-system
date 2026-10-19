package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.common.Role;
import com.survey.dto.AdminPermissionOption;
import com.survey.dto.SubAdminView;
import com.survey.entity.User;
import com.survey.mapper.RoleMapper;
import com.survey.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 次级管理员的选定、授权与降级（仅超管可调，Controller 用 @RequireAdmin）。
 * ★ 权限只认 ADMIN_GRANTABLE：survey:edit/publish/data/delete 等对象级权限不从这里发。
 * ★ 角色与权限同事务重建（t_user.role + t_user_role + t_user_permission），不能出现"角色已降、权限还在"。
 * 不能操作超管、已注销账号或自己；权限按用户存而不建角色，因为每人可勾不同模块。
 */
@Service
public class AdminManagementService {

    /** 可分配权限目录（顺序即前端勾选框顺序，也是"目录是否漏项"的自检依据）。 */
    private static final List<AdminPermissionOption> GRANTABLE = List.of(
            new AdminPermissionOption(Permission.SURVEY_MANAGE, "全部问卷 / 回收站 / 导出"),
            new AdminPermissionOption(Permission.TEMPLATE_MANAGE, "模板管理（含设为模板、改分类）"),
            new AdminPermissionOption(Permission.USER_MANAGE, "用户管理（普通用户）"),
            new AdminPermissionOption(Permission.REVIEW_HANDLE, "举报审核"),
            new AdminPermissionOption(Permission.FEEDBACK_HANDLE, "反馈 / 客服工单处理"),
            new AdminPermissionOption(Permission.WALLET_MANAGE, "钱包管理"),
            new AdminPermissionOption(Permission.ANNOUNCEMENT_MANAGE, "公告管理"),
            new AdminPermissionOption(Permission.LOG_VIEW, "操作日志 / 异常日志查看"),
            new AdminPermissionOption(Permission.SYSTEM_VIEW, "系统资源（数据库大小 / 表行数 / 快照占用）"));

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    /** 角色/权限变更后必须吊销 refresh token，否则旧票能续出新 access token 绕过 token_valid_after。 */
    @Autowired
    private RefreshTokenService refreshTokenService;

    /** 可分配权限目录（管理端渲染勾选框）。 */
    public List<AdminPermissionOption> grantablePermissions() {
        return GRANTABLE;
    }

    /** 现有次级管理员列表（含权限点）。 */
    public List<SubAdminView> listSubAdmins() {
        List<User> users = userMapper.findActiveUsers(null, Role.SUB_ADMIN, null);
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        List<SubAdminView> result = new ArrayList<>(users.size());
        for (User user : users) {
            List<String> codes = roleMapper.findPermissionCodesByUserId(user.getId());
            List<String> filtered = codes == null ? List.of()
                    : codes.stream().filter(Permission::isAdminGrantable).toList();
            result.add(new SubAdminView(user.getId(), user.getUsername(), user.getNickname(),
                    user.getDisabled(), user.getCreatedTime(), filtered));
        }
        return result;
    }

    /**
     * 把普通用户提升为次级管理员并写入权限。
     * 已经是 subadmin 的走 {@link #updatePermissions}，这里拒绝，避免误以为"重置了权限"。
     */
    @Transactional
    public List<SubAdminView> promote(Integer userId, List<String> permissions, Integer operatorId) {
        User target = requireTarget(userId);
        if (Role.isAdmin(target.getRole())) {
            throw new BizException("超级管理员不能被设为次级管理员");
        }
        if (Role.SUB_ADMIN.equals(target.getRole())) {
            throw new BizException("该用户已经是次级管理员，请直接修改权限");
        }
        if (userId.equals(operatorId)) {
            throw new BizException("不能把自己设为次级管理员");
        }
        List<String> normalized = normalizePermissions(permissions);

        userMapper.updateRole(userId, Role.SUB_ADMIN);
        replaceRoles(userId, Role.SUB_ADMIN);
        replacePermissions(userId, normalized, operatorId);
        invalidateCredentials(userId);
        return listSubAdmins();
    }

    /** 修改某个次级管理员的权限（不改变角色）。 */
    @Transactional
    public List<SubAdminView> updatePermissions(Integer userId, List<String> permissions, Integer operatorId) {
        User target = requireTarget(userId);
        if (!Role.SUB_ADMIN.equals(target.getRole())) {
            throw new BizException("该用户不是次级管理员，无法修改权限");
        }
        List<String> normalized = normalizePermissions(permissions);
        replacePermissions(userId, normalized, operatorId);
        invalidateCredentials(userId);
        return listSubAdmins();
    }

    /** 降级为普通用户（不删除账号、不动其问卷 / 答卷；已注销账号不能操作）。 */
    @Transactional
    public List<SubAdminView> demote(Integer userId, Integer operatorId) {
        User target = requireTarget(userId);
        if (Role.isAdmin(target.getRole())) {
            throw new BizException("超级管理员不能被降级");
        }
        if (!Role.SUB_ADMIN.equals(target.getRole())) {
            throw new BizException("该用户不是次级管理员");
        }
        if (userId.equals(operatorId)) {
            throw new BizException("不能降级自己");
        }
        userMapper.updateRole(userId, Role.USER);
        replaceRoles(userId, Role.USER);
        roleMapper.deleteUserPermissions(userId);
        invalidateCredentials(userId);
        return listSubAdmins();
    }

    /** 目标必须是存在且未注销的账号（已注销账号不能授权）。 */
    private User requireTarget(Integer userId) {
        if (userId == null) {
            throw new BizException("请选择要操作的用户");
        }
        User user = userMapper.findByIdIncludeDeleted(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        if (user.getDeletedAt() != null) {
            throw new BizException("该账号已注销，请先恢复账号");
        }
        return user;
    }

    /**
     * 权限归一：去空白、去重、只允许 ADMIN_GRANTABLE、至少一项、最多 20 项。
     * 顺序按勾选顺序保留（LinkedHashSet），存库顺序不影响判断，只便于排查。
     */
    private List<String> normalizePermissions(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            throw new BizException("请至少给次级管理员选择一项权限");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : permissions) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String code = raw.trim();
            if (!Permission.isAdminGrantable(code)) {
                throw new BizException("权限点不合法或不允许分配给次级管理员：" + code);
            }
            result.add(code);
        }
        if (result.isEmpty()) {
            throw new BizException("请至少给次级管理员选择一项权限");
        }
        if (result.size() > 20) {
            throw new BizException("单个次级管理员的权限项过多");
        }
        return new ArrayList<>(result);
    }

    /** 重建 t_user_role：先清后插（只保留一个角色，避免 user + subadmin 双角色造成权限并集歧义）。 */
    private void replaceRoles(Integer userId, String roleCode) {
        Integer roleId = roleMapper.findRoleIdByCode(roleCode);
        if (roleId == null) {
            // 种子数据缺失是部署错误，不是用户输入问题 → 让它响（500），不要静默少授权
            throw new IllegalStateException("角色种子缺失：" + roleCode);
        }
        roleMapper.deleteUserRoles(userId);
        roleMapper.insertUserRole(userId, roleId);
    }

    /** 重建 t_user_permission。 */
    private void replacePermissions(Integer userId, List<String> permissions, Integer operatorId) {
        roleMapper.deleteUserPermissions(userId);
        for (String code : permissions) {
            roleMapper.insertUserPermission(userId, code, operatorId);
        }
    }

    /**
     * 作废该用户此刻已签发的全部凭证。
     * ★ 只写 token_valid_after 不够：refresh token 还能续出新 access token；
     *   两处一起做，被降级 / 改权限的人下一次请求就会 401，重新登录后菜单与后端权限才一致。
     */
    private void invalidateCredentials(Integer userId) {
        userMapper.updateTokenValidAfter(userId, LocalDateTime.now().withNano(0));
        refreshTokenService.revokeAllByUser(userId);
    }
}
