package com.survey.mapper;

import com.survey.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 用户表（t_user）的数据访问层。deleted_at：NULL=正常，非 NULL=已注销；disabled 是管理员标记，不参与查询过滤。
 * avatar 为 mediumtext（base64，解码后上限 2MB）：列表查询与 findById 都必须显式排除该列。
 * ★ 每个"查用户"的 SQL 都必须带 `deleted_at IS NULL`，漏一处已注销账号就还能用；
 *   故意不过滤的只有 findByIdIncludeDeleted / findByUsernameIncludeDeleted / findByUsernamePrefix。
 */
@Mapper
public interface UserMapper {

    /** 所有有效管理员的 id（恢复上架申请要通知他们）。必须带 deleted_at IS NULL。 */
    @Select("SELECT id FROM t_user WHERE role = 'admin' AND deleted_at IS NULL")
    java.util.List<Integer> findAdminIds();

    /**
     * 拥有某权限点的管理端人员：超管 + 被授予该权限点的次级管理员（未注销、未禁用）。
     * ★ 通知按权限分发时用它，不要再用"全部管理员"——否则没该模块权限的次级管理员也会收到无关通知。
     * ★ 超管直接命中 role='admin'；subadmin 走 t_user_permission（直接授权）或角色权限。
     */
    @Select("SELECT DISTINCT u.id FROM t_user u "
            + "WHERE u.deleted_at IS NULL AND u.disabled = 0 AND u.role IN ('admin', 'subadmin') "
            + "AND (u.role = 'admin' "
            + "OR EXISTS (SELECT 1 FROM t_user_permission up "
            + "           WHERE up.user_id = u.id AND up.permission_code = #{code}) "
            + "OR EXISTS (SELECT 1 FROM t_user_role ur JOIN t_role_permission rp ON rp.role_id = ur.role_id "
            + "           WHERE ur.user_id = u.id AND rp.permission_code = #{code}))")
    java.util.List<Integer> findStaffIdsByPermission(@Param("code") String code);

    /** 按用户名查（登录用）。必须带 deleted_at IS NULL：已注销账号登不进去，且失败路径与"密码错"完全一致。 */
    @Select("SELECT * FROM t_user WHERE username = #{username} AND deleted_at IS NULL")
    User findByUsername(String username);

    /** 按用户名查，包括已注销的。唯一调用方：注册查重 —— 注销账号仍占着唯一索引，用过滤版会"校验可用 → insert 撞索引 → 500"。 */
    @Select("SELECT * FROM t_user WHERE username = #{username}")
    User findByUsernameIncludeDeleted(String username);

    /** 插入用户。调用前 Service 已哈希密码、role 已强制为 user。 */
    @Insert("INSERT INTO t_user(username, password, nickname, role) VALUES(#{username}, #{password}, #{nickname}, #{role})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /**
     * 在用用户（未注销），支持关键字/角色/禁用状态筛选；含管理员，禁用的账号照常返回。
     * disabled 是筛选而非默认过滤：默认只查 disabled=0 会让被禁用账号从列表消失、再也无法解禁。
     * 显式排除 avatar（mediumtext）：列表不显示头像，SELECT * 会为每行白读 MB 级数据。
     */
    @Select("<script>" +
            "SELECT id, username, nickname, role, phone, email, created_time, deleted_at, disabled FROM t_user " +
            "WHERE deleted_at IS NULL " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (username LIKE CONCAT('%', #{keyword}, '%') " +
            "OR nickname LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='role != null and role != \"\"'>AND role = #{role} </if>" +
            "<if test='disabled != null'>AND disabled = #{disabled} </if>" +
            "ORDER BY created_time DESC" +
            "</script>")
    List<User> findActiveUsers(@Param("keyword") String keyword,
                               @Param("role") String role,
                               @Param("disabled") Integer disabled);

    /**
     * 导出用：与 {@link #findActiveUsers} 同口径，不分页，固定截断 10001 条。
     * PageHelper 只影响下一次查询，故单独一条 SQL；10001 多取一条只为判断超限，由调用方明确拒绝。
     */
    @Select("<script>" +
            "SELECT id, username, nickname, role, phone, email, created_time, deleted_at, disabled FROM t_user " +
            "WHERE deleted_at IS NULL " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (username LIKE CONCAT('%', #{keyword}, '%') " +
            "OR nickname LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='role != null and role != \"\"'>AND role = #{role} </if>" +
            "<if test='disabled != null'>AND disabled = #{disabled} </if>" +
            "ORDER BY created_time DESC LIMIT 10001" +
            "</script>")
    List<User> findActiveUsersForExport(@Param("keyword") String keyword,
                                        @Param("role") String role,
                                        @Param("disabled") Integer disabled);

    /**
     * 已注销用户列表（管理端"已注销用户"视图，配合 restore 端点）。
     * 必须单独有它：findActiveUsers 把已注销的过滤掉了，管理员看不见就无从恢复。同样排除 avatar。
     */
    @Select("<script>" +
            "SELECT id, username, nickname, role, phone, email, created_time, deleted_at, disabled FROM t_user " +
            "WHERE deleted_at IS NOT NULL " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (username LIKE CONCAT('%', #{keyword}, '%') " +
            "OR nickname LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='role != null and role != \"\"'>AND role = #{role} </if>" +
            "ORDER BY deleted_at DESC" +
            "</script>")
    List<User> findDeletedUsers(@Param("keyword") String keyword, @Param("role") String role);

    /** 导出用：已注销用户全量，与 {@link #findDeletedUsers} 同口径，固定截断 10001 条。 */
    @Select("<script>" +
            "SELECT id, username, nickname, role, phone, email, created_time, deleted_at, disabled FROM t_user " +
            "WHERE deleted_at IS NOT NULL " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (username LIKE CONCAT('%', #{keyword}, '%') " +
            "OR nickname LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='role != null and role != \"\"'>AND role = #{role} </if>" +
            "ORDER BY deleted_at DESC LIMIT 10001" +
            "</script>")
    List<User> findDeletedUsersForExport(@Param("keyword") String keyword, @Param("role") String role);

    /** 物理删除。调用前 UserService.deleteUser 已清完他的问卷/答卷/通知；按主键删，不过滤 deleted_at。 */
    @Delete("DELETE FROM t_user WHERE id = #{id}")
    int deleteById(Integer id);

    /** 普通用户数量（不含管理员、不含已注销），管理员看板用。 */
    @Select("SELECT COUNT(*) FROM t_user WHERE role = 'user' AND deleted_at IS NULL")
    int countNormalUsers();

    /** 管理员数量（只数未注销的）—— 删除保护用：删掉最后一个管理员就没人能进管理后台，只能改库恢复。 */
    @Select("SELECT COUNT(*) FROM t_user WHERE role = 'admin' AND deleted_at IS NULL")
    int countAdmins();

    /**
     * 可用管理员数量（未注销且未禁用）—— "不能禁用最后一个管理员"的保护。
     * 不能复用 {@link #countAdmins}：它把已禁用的也算进来，只剩一个已禁用管理员时仍会放行。
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE role = 'admin' AND deleted_at IS NULL AND disabled = 0")
    int countActiveAdmins();

    /** 今日注册的账号数（未注销）。 */
    @Select("SELECT COUNT(*) FROM t_user WHERE deleted_at IS NULL AND created_time >= CURDATE()")
    int countCreatedToday();

    /** 被禁用账号数（未注销）；解禁入口在用户管理页。 */
    @Select("SELECT COUNT(*) FROM t_user WHERE deleted_at IS NULL AND disabled = 1")
    int countDisabled();

    /** 近 7 天每日注册数（未注销）；数据库只返回有数据的日子，由 Service 补齐。 */
    @Select("SELECT DATE_FORMAT(created_time, '%Y-%m-%d') AS d, COUNT(*) AS cnt FROM t_user "
            + "WHERE deleted_at IS NULL AND created_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) "
            + "GROUP BY DATE_FORMAT(created_time, '%Y-%m-%d')")
    List<Map<String, Object>> countDailyCreated();

    /**
     * 按 id 查（取当前用户、归属校验、刷新令牌）。带 deleted_at IS NULL，注销后未过期的 JWT 立即失效。
     * ★ 故意不查 avatar（mediumtext，单张可达 2MB），调用方只要昵称/角色/状态；
     *   需要头像走 {@link #findByIdWithAvatar}。改这里必须同步下面的显式列清单，漏列不会报错、只会静默变 null。
     */
    @Select("SELECT id, username, password, nickname, role, created_time, phone, email, "
            + "deleted_at, token_valid_after, disabled "
            + "FROM t_user WHERE id = #{id} AND deleted_at IS NULL")
    User findById(Integer id);

    /** 按 id 查未注销用户，含头像。唯一调用方：个人中心 /api/user/profile；其它路径不要用。 */
    @Select("SELECT * FROM t_user WHERE id = #{id} AND deleted_at IS NULL")
    User findByIdWithAvatar(Integer id);

    /** 按 id 查，包括已注销的。只给管理员彻底删除 / 恢复账号用；判断"人还在不在"要用 findById。 */
    @Select("SELECT * FROM t_user WHERE id = #{id}")
    User findByIdIncludeDeleted(Integer id);

    /** 注销账号（软删除）：只写 deleted_at，名下问卷/答卷/通知原样保留；AND deleted_at IS NULL 保证幂等。 */
    @Update("UPDATE t_user SET deleted_at = NOW() WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(Integer id);

    /** 恢复已注销账号（管理员）：只把 deleted_at 置回 NULL，密码与资料都还在。 */
    @Update("UPDATE t_user SET deleted_at = NULL WHERE id = #{id}")
    int restoreById(Integer id);

    /** 设置禁用标记（只动这一列）。调用前 Service 已做保护判断，并同时写 token_valid_after 让已登录会话立刻失效。 */
    @Update("UPDATE t_user SET disabled = #{disabled} WHERE id = #{id}")
    int updateDisabled(@Param("id") Integer id, @Param("disabled") Integer disabled);

    /**
     * 改角色（admin / subadmin / user）。只由 AdminManagementService 提权/降级调用，
     * 角色是安全边界，绝不能从请求体直接写库。
     */
    @Update("UPDATE t_user SET role = #{role} WHERE id = #{id}")
    int updateRole(@Param("id") Integer id, @Param("role") String role);

    /** 改昵称。昵称会显示在操作日志/回收站里，Service 不允许改成全空白。 */
    @Update("UPDATE t_user SET nickname = #{nickname} WHERE id = #{id}")
    int updateNickname(@Param("id") Integer id, @Param("nickname") String nickname);

    /** 改密码。传进来的必须已经是哈希值 —— 调用方三处都先 hashPassword。 */
    @Update("UPDATE t_user SET password = #{password} WHERE id = #{id}")
    int updatePassword(@Param("id") Integer id, @Param("password") String password);

    /** 改头像（base64）。解码后上限 2MB，见 common/AvatarValidator。 */
    @Update("UPDATE t_user SET avatar = #{avatar} WHERE id = #{id}")
    int updateAvatar(@Param("id") Integer id, @Param("avatar") String avatar);

    /**
     * 所有演示用户（演示数据 reset 精确回收）。故意不过滤 deleted_at：注销的演示账号更要能清掉，否则会占着唯一用户名。
     * 与 countByUsernamePrefix 必须同口径，否则 reset 的确认数字会说谎。
     */
    @Select("SELECT * FROM t_user WHERE username LIKE CONCAT(#{prefix}, '%')")
    List<User> findByUsernamePrefix(@Param("prefix") String prefix);

    /** 演示用户数量（reset 前的确认提示）。必须与 findByUsernamePrefix 同口径（都不过滤 deleted_at），否则提示数字与实际删除数不符。 */
    @Select("SELECT COUNT(*) FROM t_user WHERE username LIKE CONCAT(#{prefix}, '%')")
    int countByUsernamePrefix(@Param("prefix") String prefix);

    /** 只更新手机号（注册时补写）。不复用 updateContact：那条会同时写 email。 */
    @Update("UPDATE t_user SET phone = #{phone} WHERE id = #{id}")
    int updatePhone(@Param("id") Integer id, @Param("phone") String phone);

    /**
     * 更新联系方式。空值必须落成 NULL 而不是空串（NULLIF 兜底）：否则"没有值"有两种表示，
     * 将来按 phone/email 判空或统计未绑定时必然漏口径。
     */
    @Update("UPDATE t_user SET phone = NULLIF(#{phone}, ''), email = NULLIF(#{email}, '') WHERE id = #{id}")
    int updateContact(@Param("id") Integer id,
                      @Param("phone") String phone,
                      @Param("email") String email);

    /**
     * 设置令牌生效起点（恢复账号 / 改密码时调用）。JWT 自包含、服务端没有黑名单，
     * 作废已签发 token 的唯一办法是记一个时间点，由过滤器拒绝 iat 早于它的票；传 null = 不限制。
     */
    @Update("UPDATE t_user SET token_valid_after = #{tokenValidAfter} WHERE id = #{id}")
    int updateTokenValidAfter(@Param("id") Integer id,
                              @Param("tokenValidAfter") java.time.LocalDateTime tokenValidAfter);
}