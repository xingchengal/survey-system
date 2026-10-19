package com.survey.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RBAC 的读取入口（角色 / 权限点 / 用户-角色）。
 * ★ 本类只查数据，不判账号有效性（deleted_at / token_valid_after）—— 那是 JwtAuthFilter 的职责。
 * 权限点字符串的合法性由 common/Permission 保证；单用户权限量级是个位数，不需要缓存。
 */
@Mapper
public interface RoleMapper {

    /** 该用户的角色码列表（如 ["admin"] / ["user"]）。 */
    @Select("SELECT r.code FROM t_user_role ur JOIN t_role r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId}")
    List<String> findRoleCodesByUserId(@Param("userId") Integer userId);

    /**
     * 该用户的权限点并集：① 角色权限（t_user_role → t_role_permission）∪ ② 用户直接授权（t_user_permission）。
     * ★ subadmin 没有任何角色级权限，权限全在 ②；UNION 少一支就等于次级管理员完全没权限。
     * 超管由 PermissionAspect 兜底放行，这里仍返回 admin 的全量种子，供登录后前端菜单使用。
     */
    @Select("SELECT DISTINCT permission_code FROM ("
            + "  SELECT rp.permission_code FROM t_user_role ur "
            + "  JOIN t_role_permission rp ON rp.role_id = ur.role_id "
            + "  WHERE ur.user_id = #{userId} "
            + "  UNION "
            + "  SELECT up.permission_code FROM t_user_permission up "
            + "  WHERE up.user_id = #{userId}"
            + ") merged")
    List<String> findPermissionCodesByUserId(@Param("userId") Integer userId);

    /** 按角色码取 id（分配角色时用；不存在返回 null）。 */
    @Select("SELECT id FROM t_role WHERE code = #{code}")
    Integer findRoleIdByCode(@Param("code") String code);

    // ===== 次级管理员授权（只由 AdminManagementService 在事务内调用） =====

    @org.apache.ibatis.annotations.Delete("DELETE FROM t_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") Integer userId);

    @org.apache.ibatis.annotations.Insert(
            "INSERT IGNORE INTO t_user_role(user_id, role_id) VALUES(#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") Integer userId, @Param("roleId") Integer roleId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM t_user_permission WHERE user_id = #{userId}")
    int deleteUserPermissions(@Param("userId") Integer userId);

    @org.apache.ibatis.annotations.Insert(
            "INSERT IGNORE INTO t_user_permission(user_id, permission_code, created_by) "
                    + "VALUES(#{userId}, #{permissionCode}, #{createdBy})")
    int insertUserPermission(@Param("userId") Integer userId,
                             @Param("permissionCode") String permissionCode,
                             @Param("createdBy") Integer createdBy);
}
