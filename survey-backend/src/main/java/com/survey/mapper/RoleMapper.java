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
     * 该用户的**权限点并集**（多角色去重）。
     * ★ 用 DISTINCT：一个人同时有 user 与自定义角色时，同一个权限点会出现两次，
     *   不去重会让调用方误以为"配了两条"。
     */
    @Select("SELECT DISTINCT rp.permission_code FROM t_user_role ur "
            + "JOIN t_role_permission rp ON rp.role_id = ur.role_id "
            + "WHERE ur.user_id = #{userId}")
    List<String> findPermissionCodesByUserId(@Param("userId") Integer userId);

    /** 按角色码取 id（分配角色时用；不存在返回 null） */
    @Select("SELECT id FROM t_role WHERE code = #{code}")
    Integer findRoleIdByCode(@Param("code") String code);
}
