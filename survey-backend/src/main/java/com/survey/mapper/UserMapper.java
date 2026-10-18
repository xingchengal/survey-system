package com.survey.mapper;

import com.survey.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用户表（t_user）的数据访问层。
 *
 * ── 表结构要点 ────────────────────────────────────────
 *   password : PBKDF2 哈希（约 83 字符，当前 10 万次迭代下的计算值）
 *              或历史遗留的 MD5（32 位十六进制）；列宽 varchar(100)
 *              ★ 迭代数调大后前缀会变长，改 PBKDF2_ITERATIONS 前先核列宽
 *                （长度拆解见 UserService 类注释）
 *   role     : 'admin' 或 'user'
 *   avatar   : mediumtext，直接存 base64 图片 —— **真实生效上限是解码后 2MB**
 *              （见 common/AvatarValidator；3MB 只是 base64 字符串长度的第一道廉价闸）
 *   phone / email : varchar(20) / varchar(100)，**空值统一存 NULL**（见 updateContact 的 NULLIF）
 *   deleted_at    : datetime NULL —— 账号注销（软删除）。语义 **NULL = 正常；非 NULL = 已注销**
 *   token_valid_after : datetime NULL —— **令牌生效起点**：JWT 的 `iat` **不晚于**它即视为失效
 *                   （NULL = 不限制）。改密码 / 恢复账号 / 禁用 / 解禁时写入，见 updateTokenValidAfter。
 *                   ★ 写入值来自应用侧 `UserService.nowForTokenCutoff()`（**截到秒**，
 *                     与 JWT `iat` 的秒精度对齐），**不是数据库的 NOW()**。
 *   disabled      : tinyint NOT NULL —— 管理端「禁用」标记（0/1）。与 deleted_at **不是一回事**：
 *                   注销是用户自己的决定，禁用是管理员的处置；两者都"登不进去"但数据都保留。
 *                   ★ 它**不参与任何查询过滤**（禁用的账号必须能在管理端列表里被看到、被解禁）
 *   ★ 已废弃的列：`profile_public`（原「资料公开开关」，需求方后来明确"不需要这个需求"，
 *     应用代码已全部剔除）。**列留在库里没删** —— 删列是不可逆操作，等确认后再单独做。
 *   唯一索引 : username —— 重名 insert 会被数据库挡住（并发注册的最后一道防线）
 *
 * ── ★★ 软删除的硬规矩（这张表最容易翻车的地方）────────────
 *   **每一个"查用户"的 SQL 都必须带 `deleted_at IS NULL`**，漏一处 = 已注销账号还能用。
 *   本接口里**故意不过滤**的只有这几处，各有理由：
 *     · `findByIdIncludeDeleted`（管理员彻底删除 / 恢复已注销账号，必须找得到目标）
 *     · `findByUsernameIncludeDeleted`（注册查重：已注销账号的用户名**不允许被抢注**，
 *       否则会出现"校验说可以用 → insert 撞唯一索引 → 500"）
 *     · `findByUsernamePrefix` / `countByUsernamePrefix`（演示数据回收：连已注销的也要能清掉，
 *       而且这两条**必须同口径**，否则 reset 的确认数字会说谎）
 *   写操作（`updateXxx` / `softDeleteById` / `restoreById` / `deleteById`）**都不带过滤**，
 *   这是有意的：它们按主键写，过滤与否不影响结果；但调用方必须先过一次"查得到"的判断。
 *   ⇒ 新增任何 `SELECT ... FROM t_user` 时，问自己一句："**已注销的人**要不要被查出来？"
 *
 * ── 三个注意点 ────────────────────────────────────────
 *   1. 所有查询都会带出 password 字段，安全性靠实体上的
 *      @JsonProperty(WRITE_ONLY) 保证它"只进不出"；
 *   2. `findActiveUsers` / `findDeletedUsers` 都支持 `role` 筛选；**不传 role 时返回全部角色**
 *      （含管理员）—— 「用户管理」页要能按角色筛人，管理员也必须能在列表里被看到
 *      （否则"禁用某个管理员"这件事没有任何入口）。★ 历史口径是"只查 role='user'"，
 *      已按 G3 需求改为含管理员；删除保护仍在 `UserService.deleteUser` / `setDisabled`。
 *   3. findByUsernamePrefix / countByUsernamePrefix 是给演示数据用的：
 *      演示用户名统一以 demo_user 开头，reset 时靠它精确回收，不碰真实用户。
 */
@Mapper
public interface UserMapper {

    /**
     * 所有有效管理员的 id（恢复上架申请要通知他们）。
     * ★ 带 deleted_at IS NULL：已注销的管理员不该收到通知。
     */
    @Select("SELECT id FROM t_user WHERE role = 'admin' AND deleted_at IS NULL")
    java.util.List<Integer> findAdminIds();

    /**
     * 按用户名查（登录、注册查重都用它）。
     * ★ 带 `deleted_at IS NULL`：**已注销账号登不进去**，而且失败路径与"密码错"完全一致
     *   （`UserService.login` 拿到 null 就返回 null → Controller 抛同一句「账号或密码错误」），
     *   所以注销过的账号名不会变成一个"可探测"的信号。
     */
    @Select("SELECT * FROM t_user WHERE username = #{username} AND deleted_at IS NULL")
    User findByUsername(String username);

    /**
     * 按用户名查，**包括已注销的**。唯一调用方：注册查重。
     * ★ 为什么必须包括：`username` 上有唯一索引，已注销账号**仍然占着那个名字**
     *   （防他人抢注，见 `sql/migration-personal-center.sql` 第三节的说明）。
     *   如果注册查重也用过滤版，就会出现"校验说可以用 → insert 撞唯一索引 → 500"，
     *   用户看到的是一句"系统繁忙"，而原因（那名字被一个已注销账号占着）谁也不知道。
     */
    @Select("SELECT * FROM t_user WHERE username = #{username}")
    User findByUsernameIncludeDeleted(String username);

    /** 插入用户。★ 调用前 Service 已把密码哈希好、role 强制成 user。 */
    @Insert("INSERT INTO t_user(username, password, nickname, role) VALUES(#{username}, #{password}, #{nickname}, #{role})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /**
     * 查询「在用」用户（未注销），支持按用户名/昵称搜索 + 按角色筛选。keyword 为 null 时返回全部。
     *
     * ★ **含管理员**（不传 role 时）：用户管理页要能按角色筛人，管理员也必须能在列表里被看到 ——
     *   否则"禁用/删除某个管理员"没有任何入口。历史口径是写死 `role='user'`，
     *   那会让页面上的「角色」列永远显示"普通用户"，角色筛选也无从谈起。
     * ★ 禁用的账号**照常返回**（disabled 只是带出来的一个字段）：禁用的意义就是"要能在列表里看到并解禁"。
     *   ★ `disabled` 参数是**筛选**而非默认过滤：传 1 只看已禁用、传 0 只看正常、不传看全部。
     *     它**绝不能**变成"默认只查 disabled=0" —— 那样被禁用的账号会从列表里消失、再也无法解禁。
     * ★ keyword 由调用方先过 SqlLikeEscaper；role 只接受 admin/user（Service 侧归一）。
     *
     * ★ **显式排除 avatar**，不要 `SELECT *`。
     *   原因：t_user.avatar 是 mediumtext，直接存 base64 图片（真实生效上限是**解码后 2MB**，
     *   见 common/AvatarValidator）—— 把每个用户的整张头像一起返回时，1 个用户就是 180KB 级，
     *   分页一次 10 条也要 MB 级。而「用户管理」页面（UserManage.vue）根本不显示头像，纯属白传。
     *   ★ 需要头像的地方走 findById（个人中心 /profile），那条路径不受影响。
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
     * 导出用：与 {@link #findActiveUsers} 同一口径，但**不分页**、固定截断在 10001 条。
     *
     * ★ 为什么不复用 findActiveUsers：PageHelper 只能通过 startPage 影响"下一次查询"，
     *   而导出要求全量。给它加一个可空 LIMIT 参数又会让分页那条 SQL 变成动态拼接 ——
     *   两条查询的用途（分页展示 / 一次性导出）本来就不同，分开更不容易改坏。
     * ★ 为什么是 10001 而不是 10000：多取一条只为判断"是否超限"。
     *   调用方（UserService.exportUsers）见到 10001 条就明确拒绝，而不是静默截断
     *   —— 静默截断会让管理员以为"导出全了"。
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
     * 查询所有**已注销**的用户（管理端的"已注销用户"视图，配合 `/user/page-deleted` + `/user/restore/{id}`）。
     *
     * ★ 为什么必须有它：软删除之后，`findActiveUsers` 按设计把已注销的人**过滤掉了** ——
     *   于是管理员在用户列表里既看不到他们，也就无从恢复（`restore` 端点会变成"有端点没入口"，
     *   同类问题（`set-template` 有端点没入口）已有先例。）
     *   ⇒ 恢复能力要有，就必须先**看得见**。
     *
     * ★ 同样显式排除 avatar（mediumtext）：这张列表只在管理端展示账号/昵称，
     *   把头像一并查出来是白传（理由见 findActiveUsers 的注释）。
     * ★ 把 deleted_at 一起查出来：界面上要显示"注销于 X 时间"，
     *   否则管理员面对一串账号，不知道哪个是刚注销的、哪个是几个月前的。
     * ★ 也支持 role 筛选，且**含管理员**（已注销的管理员同样要能被恢复）。
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

    /**
     * 导出用：已注销用户的全量查询（与 {@link #findDeletedUsers} 同口径，固定截断 10001 条）。
     * ★ 存在的理由与 findActiveUsersForExport 相同（PageHelper 只管"下一次查询"）。
     */
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

    /**
     * 物理删除用户。★ 调用前 UserService.deleteUser() 已经把他的问卷/答卷/通知都清干净了。
     * ★ 不过滤 deleted_at：它按主键删，调用方（deleteUser）已经用 findByIdIncludeDeleted 确认过存在。
     */
    @Delete("DELETE FROM t_user WHERE id = #{id}")
    int deleteById(Integer id);

    /** 普通用户数量（不含管理员），管理员看板用。★ 已注销的不计入（否则看板里的"总用户"会把注销的人也数进去） */
    @Select("SELECT COUNT(*) FROM t_user WHERE role = 'user' AND deleted_at IS NULL")
    int countNormalUsers();

    /**
     * 管理员数量 —— 用于"必须保留至少一个管理员"的删除保护。
     * ★ 为什么要有它：删除用户是**不可逆**的（还会级联删掉他名下全部问卷与答卷）。
     *   如果把库里最后一个管理员删掉，用户管理、公告、日志、模板、演示数据这些入口
     *   就再也没有人能打开，只能直接改数据库恢复。
     * ★ 只数**未注销**的管理员：已注销的管理员登不进来、也不该被算作"系统还有管理员"。
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE role = 'admin' AND deleted_at IS NULL")
    int countAdmins();

    /**
     * **可用**管理员数量（未注销 **且未禁用**）—— 用于"不能禁用最后一个管理员"的保护。
     *
     * ★ 为什么不能复用 {@link #countAdmins}：那个口径把"已禁用的管理员"也算作管理员。
     *   若拿它做禁用保护，库里只剩 1 个管理员且已被禁用时仍会放行，
     *   结果就是**没有任何人能登录管理后台**（只能改数据库恢复）。
     *   ⇒ "还有几个管理员能真的操作"与"有几个管理员账号"是两个问题，两个计数各司其职。
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE role = 'admin' AND deleted_at IS NULL AND disabled = 0")
    int countActiveAdmins();

    /**
     * 按 id 查（取当前登录用户信息、归属校验时查昵称、刷新令牌校验）。
     * ★ 带 `deleted_at IS NULL` —— 这是**软删除生效的关键一处**：
     *   `JwtAuthFilter`（每个请求）、`RefreshTokenService.rotate`（续期）、
     *   `LogAspect` / `GlobalExceptionHandler`（取昵称）都走它，
     *   所以账号一注销，他手里那枚还没过期的 JWT 与 refresh token **立刻失效**。
     *
     * ★★ **故意不查 `avatar`**（2026-10-18，C1）。
     *   `t_user.avatar` 是 mediumtext、直接存 base64 图片，**单张解码后可达 2MB**
     *   （见 common/AvatarValidator）；而上面这些调用方**全都只取昵称/角色/状态**，
     *   白读一张头像就是每个请求多背一次 MB 级 IO。
     *   ⇒ 只有个人中心（`/api/user/profile`）需要头像，它走 {@link #findByIdWithAvatar}。
     *   ★ 改这里时**必须同步下面那张显式列清单**：MyBatis 开了 `map-underscore-to-camel-case`，
     *     漏掉一列**不会报错**，那个字段会静默变成 null（例如漏了 `disabled`，
     *     "被禁用账号仍能登录"就会静默复活）。新增 t_user 列时请一并加进来。
     */
    @Select("SELECT id, username, password, nickname, role, created_time, phone, email, "
            + "deleted_at, token_valid_after, disabled "
            + "FROM t_user WHERE id = #{id} AND deleted_at IS NULL")
    User findById(Integer id);

    /**
     * 按 id 查**未注销**用户，**含头像**。唯一调用方：个人中心 `GET /api/user/profile`。
     *
     * ★ 为什么不直接复用 {@link #findById}：那一条刻意排除了 mediumtext 的 avatar
     *   （理由见那边的注释）。个人中心是**唯一**需要把头像发给前端的地方，
     *   所以为它单独留一条 `SELECT *` —— 一次请求多读一张图，可以接受。
     * ★ 其它任何地方都不要用它：归属校验、日志、过滤器都不需要头像。
     */
    @Select("SELECT * FROM t_user WHERE id = #{id} AND deleted_at IS NULL")
    User findByIdWithAvatar(Integer id);

    /**
     * 按 id 查，**包括已注销的**。两个调用场景（都必须看到已注销账号）：
     *   ① 管理员**彻底删除**一个已注销账号（否则会得到"用户不存在"，删不掉）；
     *   ② 管理员把该 id 的账号**恢复**（先要能查到它才能恢复）。
     * ★ 其它任何地方都不该用它 —— 需要"这个人还在不在"时用 {@link #findById}。
     */
    @Select("SELECT * FROM t_user WHERE id = #{id}")
    User findByIdIncludeDeleted(Integer id);

    /**
     * 注销账号（软删除，需求 三.5）：打上时间戳，**不删任何数据**。
     * ★ 只写 deleted_at 一列：他名下的问卷、答卷、通知**原样保留**
     *   （用户的决定：注销不清数据，问卷继续可用，只是不再展示发布者信息）。
     * ★ 幂等：`AND deleted_at IS NULL` 保证重复调用不会把时间戳改掉
     *   （否则"注销时间"会变成"最后一次误点的时刻"）。
     */
    @Update("UPDATE t_user SET deleted_at = NOW() WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(Integer id);

    /** 恢复已注销账号（管理员）：把 deleted_at 清零。★ 同样只动这一列，密码与资料都还在。 */
    @Update("UPDATE t_user SET deleted_at = NULL WHERE id = #{id}")
    int restoreById(Integer id);

    /**
     * 设置禁用标记（管理员）：disabled ∈ {0,1}，**只动这一列**。
     * ★ 调用前 UserService.setDisabled 已经做过全部保护判断（不能禁自己、不能禁用最后一个可用管理员、
     *   已注销账号要先恢复），并负责同时写 `token_valid_after` —— 那一列才是"已经登录的会话立刻失效"的关键。
     * ★ 不过滤 deleted_at：它按主键写，调用方已用 findByIdIncludeDeleted 确认过账号存在。
     */
    @Update("UPDATE t_user SET disabled = #{disabled} WHERE id = #{id}")
    int updateDisabled(@Param("id") Integer id, @Param("disabled") Integer disabled);

    /** 改昵称。★ 昵称会显示在操作日志/回收站里，所以 Service 不允许改成全空白。 */
    @Update("UPDATE t_user SET nickname = #{nickname} WHERE id = #{id}")
    int updateNickname(@Param("id") Integer id, @Param("nickname") String nickname);

    /**
     * 改密码。★ 传进来的必须已经是哈希值 ——
     * 调用方是 UserService（注册/改密/登录时把旧 MD5 升级成 PBKDF2），三处都先 hashPassword。
     */
    @Update("UPDATE t_user SET password = #{password} WHERE id = #{id}")
    int updatePassword(@Param("id") Integer id, @Param("password") String password);

    /** 改头像（传 base64 字符串）。真实生效上限见 common/AvatarValidator：**解码后 2MB**（3MB 只是字符串长度的第一道闸）。 */
    @Update("UPDATE t_user SET avatar = #{avatar} WHERE id = #{id}")
    int updateAvatar(@Param("id") Integer id, @Param("avatar") String avatar);

    /**
     * 查询所有演示用户（演示数据 reset 时用来精确回收）。
     * ★ **故意不过滤 deleted_at**：某个演示账号被注销了，reset 时更要能清掉 ——
     *   否则它会被永久留在库里，又因为"用户名唯一"挡住下次用同名重建演示数据。
     */
    @Select("SELECT * FROM t_user WHERE username LIKE CONCAT(#{prefix}, '%')")
    List<User> findByUsernamePrefix(@Param("prefix") String prefix);

    /**
     * 统计演示用户数量（reset 前的确认提示）。
     * ★ 与 `findByUsernamePrefix` **必须同口径**（都不过滤 deleted_at），
     *   否则 reset 的确认数字会说谎（"提示 5 个、实际删了 6 个"）。
     */
    @Select("SELECT COUNT(*) FROM t_user WHERE username LIKE CONCAT(#{prefix}, '%')")
    int countByUsernamePrefix(@Param("prefix") String prefix);

    /**
     * 只更新手机号（注册时补写用）。
     * ★ 为什么不复用下面的 updateContact：那个 SQL 同时写 phone 与 email，
     *   注册场景只填了手机号，走它会把 email 一并写成 NULL/空串 —— 虽然新建用户本来就是空，
     *   但"只动我该动的列"更不容易在后续改动里出意外。
     */
    @Update("UPDATE t_user SET phone = #{phone} WHERE id = #{id}")
    int updatePhone(@Param("id") Integer id, @Param("phone") String phone);

    /**
     * 更新联系方式（手机号/邮箱）。★ 格式与长度由 dto/ContactRequest 的注解校验，这里只管落库。
     *
     * ★★ **空值必须落成 NULL，不能落成空串**：库里"没有值"只能有一种表示。
     *   否则将来按 phone/email 判空、或做"未绑定邮箱的用户"这类统计时，
     *   必然要写 `(email IS NULL OR email = '')` —— 漏一处口径就分裂成两组
     *   （拿来做"找回密码"的候选集时尤其危险）。
     *   ★ 这里用 SQL 的 NULLIF 兜底，而不是只靠 Service 传 null：Service 已归一过一次，
     *     但那是另一个类的不变量；SQL 层再兜一次，将来谁新增一个直连 Mapper 的入口也不会写进空串。
     */
    @Update("UPDATE t_user SET phone = NULLIF(#{phone}, ''), email = NULLIF(#{email}, '') WHERE id = #{id}")
    int updateContact(@Param("id") Integer id,
                      @Param("phone") String phone,
                      @Param("email") String email);

    /**
     * 设置「令牌生效起点」（账号恢复 / 改密码时调用）。
     *
     * ── 为什么需要它（JWT 无法吊销，这是唯一能"让它失效"的办法）──
     *   access token 是自包含的，服务端没有黑名单；要作废已经发出去的票，
     *   唯一的办法是**记一个时间点，然后拒绝"签发时间早于它"的票**。
     *   `JwtAuthFilter` / `DocAccessFilter` 都按这个口径校验（比对 JWT 的 `iat`）。
     *
     * ── 两个调用点，语义都是"此刻之前签发的票全部作废"────────
     *   · `UserService.restoreUser`（恢复已注销账号）：否则注销前那枚还没过期的
     *     access token 会随着 `deleted_at` 被清空而"复活"；
     *   · `UserService.updatePassword`（改密码）：这是从产品角度最自然的一句承诺 ——
     *     "改完密码，别的设备都得重新登录"。
     *
     * ★ 传 null 表示"不限制"（本方法也支持，但当前没有调用点用它清空；
     *   清空只会在"管理员恢复账号"的反向操作里才有意义 —— 那种需求不存在）。
     */
    @Update("UPDATE t_user SET token_valid_after = #{tokenValidAfter} WHERE id = #{id}")
    int updateTokenValidAfter(@Param("id") Integer id,
                              @Param("tokenValidAfter") java.time.LocalDateTime tokenValidAfter);
}