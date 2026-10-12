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
 *                   （NULL = 不限制）。改密码 / 恢复账号时写入，见 updateTokenValidAfter。
 *                   ★ 写入值来自应用侧 `UserService.nowForTokenCutoff()`（**截到秒**，
 *                     与 JWT `iat` 的秒精度对齐），**不是数据库的 NOW()**。
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
 *   2. findAllNormalUsers 永远只查 role='user' —— 管理员不会出现在用户管理列表里；
 *   3. findByUsernamePrefix / countByUsernamePrefix 是给演示数据用的：
 *      演示用户名统一以 demo_user 开头，reset 时靠它精确回收，不碰真实用户。
 */
@Mapper
public interface UserMapper {

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
     * 查询所有普通用户（支持按用户名/昵称搜索）。keyword 为 null 时返回全部。
     *
     * ★ **显式排除 avatar**，不要 `SELECT *`。
     *   原因：t_user.avatar 是 mediumtext，直接存 base64 图片（真实生效上限是**解码后 2MB**，
     *   见 common/AvatarValidator）—— 把每个用户的整张头像一起返回时，1 个用户就是 180KB 级，
     *   分页一次 10 条也要 MB 级。而「用户管理」页面（UserManage.vue）根本不显示头像，纯属白传。
     *   ★ 需要头像的地方走 findById（个人中心 /profile），那条路径不受影响。
     */
    @Select("<script>" +
            "SELECT id, username, nickname, role, phone, email, created_time FROM t_user " +
            "WHERE role = 'user' AND deleted_at IS NULL " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (username LIKE CONCAT('%', #{keyword}, '%') " +
            "OR nickname LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "ORDER BY created_time DESC" +
            "</script>")
    List<User> findAllNormalUsers(@Param("keyword") String keyword);

    /**
     * 查询所有**已注销**的用户（管理端的"已注销用户"视图，配合 `/user/page-deleted` + `/user/restore/{id}`）。
     *
     * ★ 为什么必须有它：软删除之后，`findAllNormalUsers` 按设计把已注销的人**过滤掉了** ——
     *   于是管理员在用户列表里既看不到他们，也就无从恢复（`restore` 端点会变成"有端点没入口"，
     *   同类问题（`set-template` 有端点没入口）已有先例。）
     *   ⇒ 恢复能力要有，就必须先**看得见**。
     *
     * ★ 同样显式排除 avatar（mediumtext）：这张列表只在管理端展示账号/昵称，
     *   把头像一并查出来是白传（理由见 findAllNormalUsers 的注释）。
     * ★ 把 deleted_at 一起查出来：界面上要显示"注销于 X 时间"，
     *   否则管理员面对一串账号，不知道哪个是刚注销的、哪个是几个月前的。
     */
    @Select("<script>" +
            "SELECT id, username, nickname, role, phone, email, created_time, deleted_at FROM t_user " +
            "WHERE deleted_at IS NOT NULL " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (username LIKE CONCAT('%', #{keyword}, '%') " +
            "OR nickname LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "ORDER BY deleted_at DESC" +
            "</script>")
    List<User> findDeletedUsers(@Param("keyword") String keyword);

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
     * 按 id 查（取当前登录用户信息、归属校验时查昵称、刷新令牌校验）。
     * ★ 带 `deleted_at IS NULL` —— 这是**软删除生效的关键一处**：
     *   `JwtAuthFilter`（每个请求）、`RefreshTokenService.rotate`（续期）、
     *   `LogAspect` / `GlobalExceptionHandler`（取昵称）都走它，
     *   所以账号一注销，他手里那枚还没过期的 JWT 与 refresh token **立刻失效**。
     */
    @Select("SELECT * FROM t_user WHERE id = #{id} AND deleted_at IS NULL")
    User findById(Integer id);

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