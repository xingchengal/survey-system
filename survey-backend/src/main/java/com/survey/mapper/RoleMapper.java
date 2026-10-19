package com.survey.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RBAC 的读取入口（角色 / 权限点 / 用户-角色）。
 *
 * ── 为什么全在本类，而不拆三个 Mapper ──────────────────
 *   这三张表只有一个读取场景：**"这个用户有哪些权限点"**（登录下发 + 切面校验）。
 *   拆开只会让一次请求里出现三个 Mapper 依赖，读代码要来回跳。
 *   写入（分配角色）目前在 SQL 脚本里做，暂不开接口 —— 开之前先想清"谁能改角色"。
 *
 * ── 注意 ──────────────────────────────────────────────
 *   · 这里**不判**账号是否有效（deleted_at / token_valid_after）：那是 JwtAuthFilter 的职责，
 *     走到这里说明请求已经过鉴权；
 *   · 权限点字符串的合法性由 common/Permission 保证（种子数据只写常量值）；
 *   · 查询都走唯一键/普通索引，单用户权限量级是个位数，不需要缓存 ——
 *     同一请求内由 PermissionAspect 用 request attribute 去重（见切面）。
 */
@Mapper
public interface RoleMapper {

    /** 该用户的角色码列表（如 ["admin"] / ["user"]） */
    @Select("SELECT r.code FROM t_user_role ur JOIN t_role r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId}")
    List<String> findRoleCodesByUserId(@Param("userId") Integer userId);

    /**
     * 该用户的**权限点并集**（多角色 + 直接授权，去重）。
     *
     * ★ 两条来源：
     *   ① 角色 → 权限点（t_user_role → t_role_permission）：admin / user / 未来自定义角色；
     *   ② 用户 → 权限点（t_user_permission）：次级管理员逐项授权。
     * ★ 用 UNION：同一个人既有角色权限又有直接授权时，同一个权限点只返回一次。
     * ★ 超管（admin）在 PermissionAspect 里**兜底放行**，不依赖这里返回全部；
     *   这里仍返回 admin 角色的全量种子，便于登录后前端菜单直接使用。
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

    /** 按角色码取 id（分配角色时用；不存在返回 null） */
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
