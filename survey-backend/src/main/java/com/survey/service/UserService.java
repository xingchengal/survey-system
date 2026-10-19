package com.survey.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.BizException;
import com.survey.common.PageParams;
import com.survey.common.SqlLikeEscaper;
import com.survey.common.PageResult;
import com.survey.entity.*;
import com.survey.mapper.*;
import com.survey.common.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 用户业务：注册、登录、密码哈希、资料维护、管理员的用户管理、级联删除。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   UserController      → 全部用户接口
 *   DemoDataController  → 造演示用户时调 hashPassword("123456")
 *
 * ── 涉及的表 ──────────────────────────────────────────
 *   t_user（主）；级联删除时还会动 t_survey / t_question / t_option /
 *   t_answer / t_answer_detail / t_notification
 *
 * ── ★ 密码存储（本项目安全性的核心，务必理解）──────────
 *   现在：PBKDF2WithHmacSHA256，10 万次迭代 + 16 字节随机盐，
 *         存成 pbkdf2$迭代数$盐(Base64)$哈希(Base64)。**当前配置（10 万次迭代）为 83 字符**
 *         （由 Base64 长度算得）：
 *         前缀 "pbkdf2$100000$" 14 + 盐 Base64(16B) 24 + "$" 1 + 哈希 Base64(32B) 44。
 *         ★ 列宽关系要留意：t_user.password = varchar(100) **只够当前迭代数量级** ——
 *           迭代数调大（前缀变长）就会逼近上限（迭代数到 10 倍时已是 84 字符），
 *           所以调 PBKDF2_ITERATIONS 之前**先核列宽**（当前数量级不需要改表）。
 *   历史：老的 MD5（无盐、32 位十六进制）。md5() 只保留用于兼容，不再用来写新密码。
 *   迁移：login() 验证通过后，如果发现是 MD5 格式，会顺手升级成 PBKDF2，用户完全无感。
 *   校验：verifyPassword() 用 MessageDigest.isEqual 做常量时间比较，避免时序攻击。
 */
@Service
public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    /**
     * 用户导出单次最多多少行。
     * ★ 与 AnswerController 的导出上限同一个思路：导出会把全部行读进内存，
     *   不设上限时一次"顺手点一下"就足以把堆打满。SQL 侧固定 `LIMIT 10001`
     *   （多取一条只为判断是否超过本值），超过就明确拒绝，不静默截断。
     */
    public static final int EXPORT_MAX_ROWS = 10_000;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SurveyMapper surveyMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    /**
     * ★ 删用户时"连带删他名下的问卷"统一复用
     *   {@link SurveyService#cascadeDeleteSurvey(Integer)}，不要在本类手写删除顺序 ——
     *   表结构一变就会只改一处、留下孤儿数据。
     *   因此这里不需要 Question / Option / Answer / AnswerDetail 四个 Mapper。
     *   SurveyService 不反向依赖 UserService，所以这样调用不存在循环依赖。
     */
    @Autowired
    private SurveyService surveyService;

    /**
     * H5 额度钱包：注册赠送 10 点。
     * ★ 只用于"注册后赠送"这一处；赠送走独立事务（REQUIRES_NEW），
     *   失败也不能让注册回滚（账号已经建好了）。
     */
    @Autowired
    private WalletService walletService;

    /**
     * ⚠️ 仅用于兼容历史数据 —— 库里已存在的 MD5 密码就是这个格式，删掉它会让这批老账号登不进去。
     * 新密码一律走 {@link #hashPassword(String)}。MD5 无盐、速度快，已不适合存密码。
     */
    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // ★ 不要把 e 直接包进 RuntimeException 抛出去：它的 getMessage() 是底层异常的 toString
            //   （可能带算法名、Provider 实现等环境细节），会被全局处理器原样回传到客户端。
            //   这里用固定文案，cause 仍挂着（日志里有完整堆栈）；异常类型是 IllegalStateException，
            //   由全局处理器映射成 500，对客户端只回一句"系统繁忙"。
            //   这条分支属于**正常不可能发生**的系统级故障。
            throw new IllegalStateException("MD5 摘要算法不可用", e);
        }
    }

    // ==================== 密码哈希：PBKDF2（JDK 自带，零依赖） ====================

    private static final String PBKDF2_PREFIX = "pbkdf2$";
    private static final int PBKDF2_ITERATIONS = 100000;
    private static final int PBKDF2_SALT_BYTES = 16;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 生成 PBKDF2 加盐哈希。存储格式：pbkdf2$迭代次数$盐(Base64)$哈希(Base64)
     * 长度约 83 字符，t_user.password（varchar(100)）放得下，不需要改表。
     */
    public static String hashPassword(String plain) {
        byte[] salt = new byte[PBKDF2_SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(plain, salt, PBKDF2_ITERATIONS);
        return PBKDF2_PREFIX + PBKDF2_ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 校验密码。存储格式有两种：
     * - 以 "pbkdf2$" 开头 → 按 PBKDF2 校验
     * - 形如 32 位十六进制 → 按老式 MD5 校验；登录成功后会由 login() 自动升级为 PBKDF2
     * - 其余任何格式 → 一律判失败（交由管理员重置），不会进任何"语义不明"的比较分支
     */
    public static boolean verifyPassword(String plain, String stored) {
        if (plain == null || stored == null) return false;
        if (!isPbkdf2(stored)) {
            // ★ 必须先确认"这确实是一条老式 MD5 记录"（32 位十六进制）才进兼容分支：
            //   若只要不以 pbkdf2$ 开头就按 MD5 比较，等于把"任意非法格式"都当成 MD5 数据处理 ——
            //   某一行的 password 被外部脚本或手工 UPDATE 写成 32 个字符的明文（或其他系统导过来的
            //   哈希）时，它既不被当成 PBKDF2、也不会被拒绝，而是进入一个语义不明的比较分支，
            //   成为长期隐患。格式不符的一律判失败（交由管理员重置）。
            if (!isLegacyMd5(stored)) {
                return false;
            }
            // ★ 必须用 MessageDigest.isEqual 做常量时间比较（与下面 PBKDF2 分支同一口径）：
            //   换成 `stored.equals(md5(plain))` 就是非常量时间比较，会按字节泄露比较进度，
            //   也与类注释"用 isEqual 做常量时间比较"的承诺不符。
            //   这条分支只在"老 MD5 账号首次登录"时走到、比较的又是哈希值，实际可利用性很低，
            //   但常量时间比较成本为零。
            return MessageDigest.isEqual(
                    md5(plain).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    stored.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            // ★ 迭代次数来自**被校验的数据本身**，必须夹在合理区间内：
            //   否则一行被改坏/被写入的 `pbkdf2$200000000$...` 会让**每次登录**都同步跑 2 亿次迭代 ——
            //   单个请求就能长时间占住 Tomcat 线程（一条记录 = 一个持久的 CPU 炸弹）。
            if (iterations < 1 || iterations > PBKDF2_ITERATIONS * 10) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(plain, salt, iterations);
            // 常量时间比较，避免时序攻击
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPbkdf2(String stored) {
        return stored != null && stored.startsWith(PBKDF2_PREFIX);
    }

    /** 无盐 MD5 口令的格式：32 位十六进制。★ 只有匹配它的记录才走 MD5 兼容分支（见 verifyPassword）。 */
    private static final java.util.regex.Pattern LEGACY_MD5_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{32}$");

    private static boolean isLegacyMd5(String stored) {
        return stored != null && LEGACY_MD5_PATTERN.matcher(stored).matches();
    }

    private static byte[] pbkdf2(String plain, byte[] salt, int iterations) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(plain.toCharArray(), salt, iterations, PBKDF2_KEY_BITS);
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // ★ 同上：不把底层异常 message 回显给客户端（否则会泄露算法名、Provider 等环境细节）
            throw new IllegalStateException("PBKDF2 密钥派生不可用", e);
        }
    }

    /**
     * 注册新用户。
     * ★ 三条约束：用户名非空、密码至少 6 位、用户名不重复（数据库上还有唯一索引兜底）。
     * ★ 无论请求体里传什么 role，一律强制成 "user" —— 防止有人直接注册一个管理员账号。
     * ★ 密码在这里就哈希掉，明文不会进数据库。
     *
     * ★ 失败一律 throw BizException → 全局处理器返回 400 + {success:false,message}，
     *   前端只看 HTTP 状态码并读 message。**不要改成 return 中文串** ——
     *   那会变成"HTTP 200 + 文案"，前端只能靠比对中文判断成败。
     *   成功返回一句提示（200 的响应体）。
     *
     * @return "注册成功"
     */
    public String register(User user) {
        return register(user, null);
    }

    /**
     * 注册（带手机号）—— 需求要求注册页采集手机号（可选）。
     *
     * ★ 手机号**只存储、不参与登录**（需求明确："本次迭代不实现手机号登录、短信验证码相关功能"），
     *   格式已由 RegisterRequest 的 @Pattern 校验过，这里只做 trim + 空串归一成 null
     *   （否则库里会同时出现 "" 与 NULL 两种"没填"）。
     *
     * @param user  账号与密码（username/password/nickname）
     * @param phone 手机号，可空
     * @return "注册成功"
     */
    @Transactional
    public String register(User user, String phone) {
        // ★ 用户名必须先 trim 再查重与入库：
        //   否则用户在输入框里多打一个尾空格，就会得到 "admin " 和 "admin" 两个"看起来一样"的
        //   账号（登录时也各自算不同账号）—— 既容易自己登不进去，也容易被用来冒充别人昵称。
        //   注册与登录两侧都统一 trim。
        String username = user.getUsername() == null ? null : user.getUsername().trim();
        if (username == null || username.isEmpty()) {
            throw new BizException("账号不能为空");
        }
        if (user.getPassword() == null || user.getPassword().length() < 6) {
            throw new BizException("密码至少6位");
        }
        if (userMapper.findByUsernameIncludeDeleted(username) != null) {
            // ★ 必须用**包括已注销**的那个查询：已注销账号仍然占着用户名（有意防抢注）。
            //   若用过滤版，会出现"校验说可以用 → insert 撞唯一索引 → 500「系统繁忙」"，
            //   而真正的原因（那名字被一个已注销账号占着）谁都看不出来。
            throw new BizException("该账号已被注册");
        }
        user.setUsername(username);
        user.setPassword(hashPassword(user.getPassword()));
        if (user.getNickname() == null || user.getNickname().trim().isEmpty()) {
            user.setNickname(username);
        }
        if (phone != null && !phone.trim().isEmpty()) {
            user.setPhone(phone.trim());
        }
        user.setRole(Role.USER);
        userMapper.insert(user);
        // ★ userMapper.insert 的 SQL 只写 username/password/nickname/role（不含 phone）——
        //   所以手机号要单独补一次 UPDATE，否则"注册时填的手机号"会静默丢掉。
        //   ★ 两步写必须同事务：插了用户却没写进手机号，用户会以为"我明明填了"。
        if (user.getPhone() != null && user.getId() != null) {
            userMapper.updatePhone(user.getId(), user.getPhone());
        }
        // ★ H5：注册赠送 10 点额度（独立事务，幂等键 signup:{userId}）。
        //   赠送失败只记日志，绝不让注册回滚 —— 钱包是附加权益，账号才是主流程。
        try {
            walletService.grantSignupBonus(user.getId());
        } catch (Exception e) {
            LOG.warn("注册赠送额度失败（不影响注册）：userId={} —— {}", user.getId(), e.getMessage());
        }
        return "注册成功";
    }

    /**
     * 账号格式：只允许大小写字母与数字，4~20 位（需求："账号仅支持字母和数字，4~20个字符"）。
     * ★ 注册侧由 {@code RegisterRequest} 的 @Pattern 强制；登录侧用同一个正则，但**受开关控制**
     *   —— 原因见下面 login() 的注释。
     */
    private static final java.util.regex.Pattern ACCOUNT_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9]{4,20}$");

    /**
     * 刷新令牌业务。用法**三处**：`updatePassword`（改密码踢全部会话）、`deleteAccount`（注销）、
     * `UserController.logout`（没带 refresh token 但已登录时，全吊销）。
     * ★ 不构成循环依赖：`RefreshTokenService` 只依赖 `RefreshTokenMapper` 与 `UserMapper`，
     *   不依赖任何 Service —— 不要给它加 Service 依赖，否则与本类会形成循环依赖。
     */
    @Autowired
    private RefreshTokenService refreshTokenService;

    /**
     * 登录时是否严格校验账号格式。
     *
     * ★★ 为什么需要这个开关（唯一带"逃生门"的设计，务必理解）：
     *   需求要求"登录、注册都需要校验用户名字符类型"。但库里**存有历史账号** ——
     *   例如一个中文账号（"用户"，2 位、含汉字）。一旦把正则硬编码进登录路径，
     *   那些账号会**立刻登不进去**，而且没有补救手段（除非改库）。
     *   所以：默认 **true**（满足需求），可用配置一键关掉：
     *     · application.yaml:  app.strict-username-check: false
     *     · 或启动参数:        --app.strict-username-check=false
     *   关掉后登录只做非空校验，旧账号照常可用；**注册仍然强制新规则**（新账号不会有坏名字）。
     */
    @org.springframework.beans.factory.annotation.Value("${app.strict-username-check:true}")
    private boolean strictUsernameCheck;

    /**
     * 登录校验。
     *
     * ★ 账号不存在或密码错误都返回 null（不区分，避免泄露"这个账号存在"）。
     * ★ 登录**不做密码最小长度校验**（"至少 6 位"是注册规则，不该由登录接口说出来）；
     *   上限由 DTO 的 @Size(max=64) 兜住。
     * ★ 账号字符校验受 {@link #strictUsernameCheck} 控制：格式不合法时**也返回 null**
     *   （而不是抛 400）—— 这样"账号带汉字"与"密码错"对客户端是**同一个响应**，
     *   不会因响应差异暴露"这个账号名不符合规则"这类信息。
     * ★ 平滑升级：老账号的密码是 MD5 存的，验证通过后在这里顺手改成 PBKDF2。
     *   所以这类账号只要成功登录一次，库里那条记录就自动完成迁移了。
     *
     * @return 登录成功的用户（含 id/username/role，password 字段是哈希值，Controller 会置空后再返回）；
     *         失败返回 null
     */
    public User login(String username, String password) {
        // ★ 与 register 对称地 trim（注册时 trim 了，登录不 trim 的话，
        //   用户在输入框里多带一个空格就登不进自己的账号）。
        String name = username == null ? null : username.trim();
        if (name == null || name.isEmpty()) return null;
        if (strictUsernameCheck && !ACCOUNT_PATTERN.matcher(name).matches()) {
            // 只记一条 warn（便于排查"为什么某个账号登不进去"），**不写异常日志**：
            // 这是可预期的输入，不是程序故障。
            LOG.warn("登录账号格式不符合规则（已按失败处理）：长度={} 仅字母数字={}",
                    name.length(), name.chars().allMatch(Character::isLetterOrDigit));
            return null;
        }
        User user = userMapper.findByUsername(name);
        if (user == null) return null;
        if (!verifyPassword(password, user.getPassword())) return null;

        // ★ 平滑升级：旧数据是 MD5 存的，登录验证通过后顺手换成 PBKDF2（用户完全无感）
        if (!isPbkdf2(user.getPassword())) {
            userMapper.updatePassword(user.getId(), hashPassword(password));
        }
        return user;
    }

    /** 按 id 查**未注销**的用户（JwtAuthFilter / DocAccessFilter 每个请求都用它取当前登录用户，
     *  日志切面与异常处理器也用它取昵称）。 */
    public User getById(Integer id) {
        return userMapper.findById(id);
    }

    /**
     * 按 id 查**未注销**的用户，**含头像** —— 只有个人中心 `GET /api/user/profile` 用。
     *
     * ★ 与 {@link #getById} 的区别只有一列：`avatar`（mediumtext，单张解码后可达 2MB）。
     *   归属校验/日志/过滤器只要昵称与状态，让它们每个请求都背一张头像是纯浪费
     *   （见 UserMapper.findById 的注释）。
     * ★ 新增调用方前先问一句：**这里真的要把头像发给前端吗？**
     *   目前唯一的答案是"个人中心要显示头像"。
     */
    public User getByIdWithAvatar(Integer id) {
        return userMapper.findByIdWithAvatar(id);
    }

    // ★ mapper 的 findActiveUsers(keyword, role) 传 null 即全量查询：listActiveUsersPage 是它的唯一分页入口。

    /**
     * 分页查「在用」用户（管理员用户管理页，支持关键字搜索 + 角色筛选）。
     *
     * ★ role 口径：只认 `admin` / `user` 两个值，**空/null = 全部角色（含管理员）**。
     *   非法值（例如 `role=root`）按"全部"处理而不是报 400 —— 它是筛选条件，
     *   不是业务入参；把它当错误只会让前端拼错参数时整页打不开。
     * ★ disabled 口径：1 = 只看已禁用、0 = 只看正常、null = 全部（**默认不过滤**，
     *   否则被禁用的账号会从列表里消失、再也无法解禁）。
     * ★ 禁用的账号照常返回（disabled 是行上的一个字段）：禁用的意义就是"能看见、能解禁"。
     */
    public PageResult<User> listActiveUsersPage(int pageNum, int pageSize, String keyword,
                                                String role, Integer disabled) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // ★ 关键字必须转义 LIKE 通配符（见 common/SqlLikeEscaper）：
        //   否则用户在搜索框里输入一个 % 就等于"匹配所有行"（筛选被静默绕过），
        //   输入 _ 会被当成"任意一个字符"。
        keyword = SqlLikeEscaper.escape(keyword);
        List<User> list = userMapper.findActiveUsers(keyword, normalizeRoleFilter(role), normalizeDisabledFilter(disabled));
        PageInfo<User> pageInfo = new PageInfo<>(list);
        // ★ 必须回显**钳制后**的页码/页大小：分页保护（PageParams）只在 startPage 那一层生效，
        //   若把调用方传进来的原值写进响应，请求 pageSize=100000 时实际只返回 100 条、
        //   响应体却回显 100000 —— 分页组件据此算总页数/显示"每页条数"都会错；pageSize=0 或负数时同理。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /**
     * 分页查**已注销**的用户（管理员，支持关键字搜索 + 角色筛选）—— 配合「恢复账号」。
     * ★ 与 {@link #listActiveUsersPage} 是**两份**查询，而不是一个 boolean 开关：
     *   两者的 WHERE 条件与排序都不同（这份按 `deleted_at DESC` —— 最近注销的在前，
     *   管理员最可能要找的就是刚误点注销的那个人），用开关拼 SQL 只会让 MyBatis 的
     *   `<if>` 越来越难读，而这里的重复只有几行。
     * ★ 同样必须转义 LIKE 通配符：否则 % / _ 会静默绕过关键字筛选。
     */
    public PageResult<User> listDeletedUsersPage(int pageNum, int pageSize, String keyword, String role) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        keyword = SqlLikeEscaper.escape(keyword);
        List<User> list = userMapper.findDeletedUsers(keyword, normalizeRoleFilter(role));
        PageInfo<User> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /** 角色筛选参数归一：只放行 admin / subadmin / user，其余（含空串与非法值）一律当作"全部"。 */
    private static String normalizeRoleFilter(String role) {
        if (role == null) return null;
        String value = role.trim();
        return Role.ADMIN.equals(value) || Role.SUB_ADMIN.equals(value) || Role.USER.equals(value)
                ? value : null;
    }

    /** 禁用筛选参数归一：只放行 0 / 1，其余（含 null 与非法值）当作"不过滤"。 */
    private static Integer normalizeDisabledFilter(Integer disabled) {
        return disabled != null && (disabled == 0 || disabled == 1) ? disabled : null;
    }

    /**
     * 导出用的用户列表（**不分页**，最多 10000 行）。
     *
     * ★ deletedView=true 走「已注销」口径，否则走「在用」口径 —— 与页面上的两个视图一一对应。
     * ★ 超过 10000 行**明确报名拒绝**，不静默截断：静默截断会让管理员以为"导出全了"。
     *   （上限判定的口径：mapper 固定 `LIMIT 10001`，多取的一条只为判断是否超限。）
     */
    public List<User> exportUsers(String keyword, String role, boolean deletedView, Integer disabled) {
        keyword = SqlLikeEscaper.escape(keyword);
        String normalizedRole = normalizeRoleFilter(role);
        List<User> users = deletedView
                ? userMapper.findDeletedUsersForExport(keyword, normalizedRole)
                : userMapper.findActiveUsersForExport(keyword, normalizedRole, normalizeDisabledFilter(disabled));
        if (users.size() > EXPORT_MAX_ROWS) {
            throw new BizException("导出结果超过 " + EXPORT_MAX_ROWS
                    + " 位用户，请缩小筛选范围后再导出（导出的每一行都要进内存，不设上限会拖垮服务）");
        }
        return users;
    }

    /**
     * 禁用 / 解禁一个账号（管理员）。
     *
     * ── 保护（缺一条就会造成不可自救的状态）────────────────
     *   1. **不能禁用自己**：disabled 一旦生效，连自己的管理入口都没了；
     *   2. **不能禁用最后一个可用管理员**：否则没人能登录后台（恢复只能改数据库）；
     *      解禁方向不需要这道保护；
     *   3. **已注销的账号要先恢复**：禁用一个"本来就登不进来"的账号没有意义，
     *      而且会让列表出现"已注销 + 已禁用"这种看不明白的状态。
     *
     * ── 为什么同时写 token_valid_after ─────────────────────
     *   JWT 无法吊销，`disabled` 只在新请求进 JwtAuthFilter 时才会被读到 —— 但那只解决
     *   "之后的请求"。为了让**手里那枚票立刻作废**（语义上"禁用即刻生效"），
     *   这里把"令牌生效起点"推到此刻，与注销/改密码同一套机制。
     *   ★ 解禁方向**也**写：解禁前那段时间账号处于"被处置"状态，
     *     之前泄露/遗留的票不该在解禁后自动复活。
     *
     * @param disabled 1 = 禁用，0 = 解禁
     */
    @Transactional
    public String setDisabled(Integer id, Integer disabled, Integer currentUserId) {
        if (id == null) throw new BizException("请指定要操作的用户");
        if (disabled == null || (disabled != 0 && disabled != 1)) {
            throw new BizException("禁用标记只能是 0（解禁）或 1（禁用）");
        }
        // ★ 用 findByIdIncludeDeleted：已注销账号要给出"请先恢复"的明确提示，
        //   而不是让他看到一句"用户不存在"（那个人就在「已注销」列表里）。
        User user = userMapper.findByIdIncludeDeleted(id);
        if (user == null) throw new BizException("用户不存在");

        boolean disabling = disabled == 1;

        if (user.getDeletedAt() != null) {
            throw new BizException("该账号已注销，请先恢复账号再设置禁用状态");
        }
        // ★ 自己优先判：文案是“不能禁用自己”，比“超级管理员不能被禁用”更贴近用户动作。
        if (disabling && id.equals(currentUserId)) {
            throw new BizException("不能禁用自己的账号");
        }
        // ★ 超级管理员不可被禁用/解禁：这条比“最后一个管理员”更强 ——
        //   只要超管账号存在且可用，系统就一定有人能进后台。
        if (Role.isAdmin(user.getRole())) {
            throw new BizException("超级管理员不能被禁用或解禁");
        }
        // ★ 次级管理员只有超管能操作（user:manage 可以管普通用户，但不能管同僚/上级）。
        if (Role.SUB_ADMIN.equals(user.getRole()) && !isSuperOperator(currentUserId)) {
            throw new BizException("只有超级管理员可以操作管理员账号");
        }

        int result = userMapper.updateDisabled(id, disabled);
        if (result <= 0) throw new BizException("操作失败");
        // ★ 作废此刻之前签发的票（理由见方法注释）。截到秒，与 JWT 的 iat 精度一致。
        userMapper.updateTokenValidAfter(id, nowForTokenCutoff());
        LOG.info("账号禁用状态已更新：userId={} username={} disabled={} 操作人={}",
                id, user.getUsername(), disabled, currentUserId);
        return disabling ? "已禁用" : "已解禁";
    }

    /**
     * 删除用户，并级联清掉它名下的所有数据。
     *
     * ★ 事务：要删 **7** 张表（t_answer_detail / t_answer / t_option / t_question / t_survey /
     *   t_notification / t_user），中途失败必须整体回滚，绝不能留下"用户没了、问卷还在"的孤儿数据。
     * ★ 删除顺序必须自底向上（先明细、再主表），否则会违反外键/留下悬挂引用：
     *     答卷明细 → 答卷 → 选项 → 题目 → 问卷 → 通知 → 用户
     * ★ 注意用的是 findByUserIdIncludeDeleted：**回收站里的问卷也要一起删**，
     *   否则删完用户，回收站里还挂着他的问卷。
     *
     * ★★ **删除用户必须有保护**。原因：
     *   ① 这是**不可逆**操作，而且会**级联删掉该用户名下的全部问卷、以及那些问卷收到的全部答卷** ——
     *      误删一个管理员，等于连带删掉他建过的所有问卷与别人填过的所有答卷；
     *   ② 管理员删自己 → 轻则把自己锁在外面（token 还在，但用户已不存在），
     *      重则（库里只剩他一个管理员时）系统从此没有任何管理员 ——
     *      用户管理、公告、日志、模板、演示数据这些入口再也没人能打开，只能直接改库恢复。
     *      所以**拦住"删自己"这一条，实际上就等于保住了"系统至少留一个管理员"**。
     *   ③ 另有一道 countAdmins() 检查，见下面代码处的"准确说明" ——
     *      它在当前入口下其实不可达，保留是为了不把这件事只建立在一道检查上。
     *   ★ 传入的是"当前登录用户 id"（来自 X-User-Id），不是请求体里任何可伪造的字段。
     *   ★ 管理员删除自己会被上面那道检查拦成 400「不能删除自己的账号（如需注销，请让其他管理员操作）」。
     *
     * @param id            要删除的用户 id
     * @param currentUserId 当前登录的管理员 id
     */
    @Transactional
    public int deleteUser(Integer id, Integer currentUserId) {
        // ★ 必须用**包括已注销**的那个查询：管理员要能把一个已注销账号也彻底删掉 ——
        //   若用过滤版，他会拿到"用户不存在"，而列表里又看不到它，等于这个账号删不掉了。
        User user = userMapper.findByIdIncludeDeleted(id);
        if (user == null) return 0;

        if (id.equals(currentUserId)) {
            throw new BizException("不能删除自己的账号（如需注销，请让其他管理员操作）");
        }
        // ★★ 超级管理员**永远不可删除**（即使调用者也是超管）：
        //   这是“系统一定还有超管”的最强保证，不再依赖 countAdmins() 的时机判断。
        if (Role.isAdmin(user.getRole())) {
            throw new BizException("不能删除超级管理员账号");
        }
        // ★ 次级管理员只能由超管删除；user:manage 可以管普通用户，但不能管同僚/上级。
        if (Role.SUB_ADMIN.equals(user.getRole()) && !isSuperOperator(currentUserId)) {
            throw new BizException("只有超级管理员可以删除管理员账号");
        }

        // 1. 找出该用户的所有问卷（含回收站）
        List<Survey> surveys = surveyMapper.findByUserIdIncludeDeleted(id);

        for (Survey s : surveys) {
            // ★ 必须复用 SurveyService.cascadeDeleteSurvey()，不要在这里手写
            //   "明细→答卷→选项→题目→问卷"的顺序：否则表结构一变就会只改一处、留下孤儿数据。
            surveyService.cascadeDeleteSurvey(s.getId());
        }

        // 2. 删除该用户的通知
        notificationMapper.deleteAllByUser(id);

        // 3. 删除用户本身
        return userMapper.deleteById(id);
    }

    /** 普通用户总数（管理员看板的「总用户」卡片）。 */
    public int countNormalUsers() {
        return userMapper.countNormalUsers();
    }

    /** 改昵称。★ 昵称也用于操作日志里显示"谁干的"，所以不允许全空白。失败抛 BizException（400）。 */
    public String updateNickname(Integer userId, String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new BizException("昵称不能为空");
        }
        // ★ 上限必须与注册侧 @Size(max = 50) 对齐：
        //   昵称会写进操作日志、异常日志与站内通知，无上限就是一个没有约束的写入口。
        if (nickname.trim().length() > 50) {
            throw new BizException("昵称不能超过 50 个字符");
        }
        User user = userMapper.findById(userId);
        if (user == null) throw new BizException("用户不存在");
        userMapper.updateNickname(userId, nickname.trim());
        return "修改成功";
    }

    /**
     * 改密码：先验证原密码，通过后存新的 PBKDF2 哈希。
     * ★ 要求原密码，是为了防止"token 被偷后攻击者直接改密码锁死账号"。
     * ★ "原密码错误"抛 BizException → 400 + message，前端只认状态码；**不要改成 return 中文串**，
     *   那会变成 HTTP 200 + 文案，前端一旦忘记比对文案就毫无提示。
     *
     * ── ★★ 改密码必须真的踢掉所有旧会话：两件事一起做才完整，缺一个都留着缺口 ──
     *     ① `tokenValidAfter = NOW()`：让**已经签发的 access token**（最长还能用 12 小时）失效；
     *     ② `revokeAllByUser`：让**refresh token** 失效（否则它能换出一枚新的、又活 12 小时）。
     *   ★ 只做 ② 不做 ① 的后果很典型：改完密码"看起来"踢了人，但对方手里那枚 access token
     *     还能继续用到自然过期 —— 少写 ① 就等于这个功能没做。
     *   ★ 顺带的用户体验：**当前这台设备也会被踢**（它用的也是改密码之前签发的票）。
     *     前端 `savePassword` 已经做成"提示 + 清本地登录态 + 回登录页"，所以这不是意外行为；
     *     真要保留当前设备就得给前端换新票，那是另一个功能。
     */
    @Transactional
    public String updatePassword(Integer userId, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isEmpty()) throw new BizException("请输入原密码");
        if (newPassword == null || newPassword.length() < 6) throw new BizException("新密码至少6位");
        // ★ 必须设**上限**。注册侧是 RegisterRequest 的 @Size(min = 6, max = 20)；
        //   这里放宽到 64 是**有意**的 —— 历史长口令的老账号也要能改密码。
        //   不设上限的后果：几 MB 的新密码直接进 PBKDF2（10 万次迭代的 HMAC），
        //   而且**此后每次登录**都要重算一遍 —— 等于给全站埋一个持久的 CPU 开销。
        if (newPassword.length() > 64) throw new BizException("新密码不能超过 64 位");
        User user = userMapper.findById(userId);
        if (user == null) throw new BizException("用户不存在");
        if (!verifyPassword(oldPassword, user.getPassword())) throw new BizException("原密码错误");
        userMapper.updatePassword(userId, hashPassword(newPassword));
        // ① 作废"此刻之前签发的 access token"（见方法注释里的 ①②分工）
        userMapper.updateTokenValidAfter(userId, nowForTokenCutoff());
        // ② 吊销全部 refresh token（否则它能续出一枚新的、绕过 ①）
        refreshTokenService.revokeAllByUser(userId);
        LOG.info("密码已修改，并作废该用户此前的全部登录凭证：userId={}", userId);
        return "修改成功";
    }

    /**
     * 「令牌生效起点」的统一取值：**当前时间截到秒**。
     *
     * ★★ 为什么必须截到秒：JWT 的 `iat` 是 **Unix 秒**（序列化时只保留秒），
     *   而 `LocalDateTime.now()` 带纳秒。若这里返回带纳秒的时间，
     *   "改完密码**同一秒内**重新登录"就会这样：
     *     新票 `iat = 16:53:53.000`  **早于** `token_valid_after = 16:53:53.9xx` → 被判无效。
     *   现象是"改完密码立刻登录还是 401"——**用户被自己的时间戳锁在外面**
     *   （新 token 前几个请求 401，过了那一秒又正常）。
     *   ⇒ 两边都对齐到**秒**，同秒内的新票就能通过 `iat >= validAfter`。
     * ★ 另一个方向（给"早一秒以内"的旧票留容差）**故意不做**：
     *   那会让确实早签发的旧票有概率被放行，而这里的安全目标恰恰是"一个旧票都不放过"。
     *   宁可让极少数同秒提交的用户重登一次（无感），也不留一个可以碰运气绕过的窗口。
     */
    private static java.time.LocalDateTime nowForTokenCutoff() {
        return java.time.LocalDateTime.now().withNano(0);
    }

    /**
     * 当前操作人是不是超级管理员。
     * ★ 只在“目标是 staff”时才查这一条：普通用户操作不增加查询。
     * ★ 不用请求头里的 role 作为唯一依据：这里以库里的账号状态为准（被降级后立即失效）。
     */
    private boolean isSuperOperator(Integer userId) {
        if (userId == null) {
            return false;
        }
        User operator = userMapper.findById(userId);
        return operator != null && Role.isAdmin(operator.getRole());
    }

    /**
     * 改头像。★ 头像是 base64 **data URL** 字符串直接存库（t_user.avatar 是 mediumtext）。
     * ★ 数值口径：真实生效的**图片大小上限是解码后 2MB**
     *   （{@link com.survey.common.AvatarValidator#MAX_BYTES}）；3MB 只是 base64 **字符串长度**的
     *   第一道闸（MAX_CHARS，用来挡超大请求体），不代表"图片可以到 3MB" ——
     *   提示文案必须按 2MB 写，写大了就是给用户一个做不到的承诺。
     *
     * ★★ 校验收口在 {@link com.survey.common.AvatarValidator}（纯静态、可单测），
     *   **不要在本类另写一套判断**：需求 二.2 要求"限制图片文件大小与格式，**后端增加文件校验**"，
     *   只判"非空 + 字符串长度"不算格式校验 —— 任何一段文本都能当头像存进去，
     *   而前端 `el-avatar` 会把它当 URL 去加载。
     *   判定顺序：data URL 形式 → 类型白名单（png/jpeg/gif）→ base64 可解码且解码后 ≤2MB
     *   → **魔数与声明一致**（只改扩展名骗不过去）。
     *   为什么细节都在那个类里而不是这里：这段判定是纯函数，抽出去才能被纯单测覆盖
     *   （与 {@link com.survey.common.TextLimits}、{@link com.survey.common.SqlLikeEscaper} 同一个做法）。
     */
    public String updateAvatar(Integer userId, String avatar) {
        // ★ 必须先判用户存在（与 updateNickname / updatePassword 对齐）：
        //   否则"用户已被删除但 token 还没过期"（最长 12 小时）时，
        //   UPDATE 影响 0 行却照样返回"修改成功"，前端拿到一个假成功。
        if (userMapper.findById(userId) == null) throw new BizException("用户不存在");
        try {
            com.survey.common.AvatarValidator.validate(avatar);
        } catch (IllegalArgumentException e) {
            // ★ 校验类抛的是 IllegalArgumentException（message 就是给用户看的原因），
            //   在这里翻成业务失败 —— 保持"业务失败一律 BizException → 400"的全项目口径。
            //   直接把 IAE 抛出去会被 GlobalExceptionHandler 当系统故障处理成 500。
            throw new BizException(e.getMessage());
        }
        userMapper.updateAvatar(userId, avatar);
        return "修改成功";
    }

    /**
     * 改联系方式（手机号 / 邮箱），空值表示"清空该项"。
     *
     * ★★ **格式校验在 `dto/ContactRequest` 的 `@Pattern` 上**（在进入方法体**之前**拦下，
     *   失败同样是 400 + message）—— **不要在本方法里再加一份正则**：
     *   两套规则并存时，只要有人改了其中一处，就会出现"前端按新规则提示、后端按旧规则拒绝"
     *   这种谁都没错的怪现象。
     *   ★ 注意 `@Pattern` 的正则里显式放行了空串（`^$|...`）：本方法"空值 = 清空"的语义靠它保住。
     *
     * ★ 留在 Service 的两件事（它们不是"格式"问题）：
     *   ① 用户是否存在 —— 需要查库，注解做不到；
     *   ② **空白的归一**：前端传 `''` 还是 `null` 都表示"清空"，这里统一成 null，
     *      再由 Mapper 的 NULLIF 落到库里（理由见 UserMapper.updateContact 的注释：
     *      "没有值"在库里只能有一种表示）。
     */
    public String updateContact(Integer userId, String phone, String email) {
        // ★ 同上：先判用户存在，避免"幽灵用户拿到假成功"
        if (userMapper.findById(userId) == null) throw new BizException("用户不存在");
        userMapper.updateContact(userId, blankToNull(phone), blankToNull(email));
        return "修改成功";
    }

    /**
     * 把"空白"归一成 null（null / "" / 纯空格 都是"没有值"）。
     * ★ 与 Mapper 的 NULLIF 是两层：这一层处理"多个空格"，SQL 那一层只认空串。
     *   前端 `v-model` 绑的输入框用户很容易多打一个空格，只靠 NULLIF 会存进 `' '`。
     */
    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ==================== 账号注销（软删除，需求 三.5） ====================

    /**
     * 「账号注销」—— 软删除：只给 `t_user.deleted_at` 打一个时间戳，**不删任何数据**。
     *
     * ── 为什么是软删除（而不是像管理员删用户那样物理删除）──────
     *   用户对自己的账号说"注销"，语义上要的是"我不用了"，
     *   而不是"请把我建过的问卷、以及别人在这些问卷上填过的答卷全部销毁" ——
     *   后者会连带毁掉**别人的数据**（答卷是填卷人的，不是账号持有人的）。
     *   所以：账号停用、登录失效、发布者信息不再展示，**问卷与答卷原样保留**。
     *
     * ── ★ 四道效果，全部由"SQL 里的 deleted_at IS NULL"自动达成 ──
     *   1. **登不进去**：`findByUsername` 过滤 → `login` 拿到 null → 与"密码错"**同一句**
     *      「账号或密码错误」（不给"你已注销"这种可探测的文案，与既有防账号探测口径一致）；
     *   2. **手里的旧 token 立刻失效**：`JwtAuthFilter` 每个请求都 `findById`（过滤版）查一次 ——
     *      ★ 这个过滤**不能去掉**，否则被注销（或被删）用户的 token 在签发后的 12 小时内仍然有效；
     *   3. **refresh token 也失效**：这里主动 `revokeAllByUser` 一次，
     *      而即便漏了，`RefreshTokenService.rotate` 里的 `findById` 也已经是过滤版（双层保险）；
     *   4. **管理端看不到**：`findAllNormalUsers` / `countNormalUsers` 都过滤。
     *
     * ── ★ 要不要密码？要 ────────────────────────────────
     *   这是**不可逆的账号级操作**（虽然管理员能恢复，但用户自己恢复不了）。
     *   只凭一枚可能被盗的 token 就能注销账号，风险高于"多输一次密码"的麻烦 ——
     *   与 `updatePassword` 要求原密码是同一个理由。
     *
     * ── 与事务的关系 ────────────────────────────────────
     *   标了 @Transactional：`softDeleteById` 与 `revokeAllByUser` 要一起成功或一起失败。
     *   不过即便回滚，"refresh token 被吊销"也只是让用户重新登录一次，不会造成数据损坏
     *   （写反了才危险：只注销、不吊销，会让旧凭证还能续期 —— 但那种情况已被第 3 条的兜底挡住）。
     *
     * @param userId      当前登录用户（来自 X-User-Id，不是请求体）
     * @param rawPassword 用于确认身份的密码
     * @return "注销成功"
     */
    @Transactional
    public String deleteAccount(Integer userId, String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new BizException("请输入密码以确认注销");
        }
        User user = userMapper.findById(userId);
        if (user == null) {
            // 包含"已经被注销过"的情况（findById 过滤了 deleted_at）—— 对调用方都是"这个账号不能用了"
            throw new BizException("账号不存在或已注销");
        }
        // ★ 超级管理员不能自我注销（需求 4）：系统必须永远保留这个账号；
        //   次级管理员与普通用户仍可按既有流程注销。
        if (Role.isAdmin(user.getRole())) {
            throw new BizException("超级管理员不能注销账号");
        }
        // ★ 与登录用同一套校验（含 MD5 老数据的兼容）：不能在这里另写一套比较逻辑
        if (!verifyPassword(rawPassword, user.getPassword())) {
            throw new BizException("密码错误");
        }
        int affected = userMapper.softDeleteById(userId);
        if (affected <= 0) {
            // 理论上到不了（上面刚查过且同一事务），除非并发下另一个请求抢先注销了
            throw new BizException("账号不存在或已注销");
        }
        refreshTokenService.revokeAllByUser(userId);
        LOG.info("账号已注销（软删除）：userId={} username={}", userId, user.getUsername());
        return "注销成功";
    }

    /**
     * 恢复一个已注销的账号（**管理员**操作，需求 三.5 的配套）。
     *
     * ── 为什么要给管理员这条后路 ──────────────────────────
     *   软删除的"不可逆"是**对用户自己**而言的。误点注销、或账号被盗后被恶意注销，
     *   如果没有恢复入口，就只能去改数据库 —— 而本项目的取向一贯是"能通过界面做的，
     *   就不要让人去动库"（同类例子：管理员删用户的保护逻辑）。
     *
     * ── 恢复后会发生什么 ──
     *   · 密码、昵称、头像、联系方式、他名下的问卷与答卷**全都还在**（软删除只动了一列）；
     *   · 注销时被吊销的 **refresh token 不会复活**（库里那几行 revoked=1 是永久的）；
     *   · ★★ **恢复后必须重新登录**：方法体会写 `tokenValidAfter = NOW()`，
     *     于是"注销前签发、且尚未过期"的那枚 access token 会被 `JwtAuthFilter` 拒绝。
     *     ★ 这一行**不能删**：删了那枚票会因为 `deleted_at` 被清空而"复活"
     *     （JWT 本身无法吊销，只能靠时间点作废）。
     */
    @Transactional
    public String restoreUser(Integer id, Integer currentUserId) {
        if (id == null) throw new BizException("请指定要恢复的用户");
        User user = userMapper.findByIdIncludeDeleted(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        // ★ 恢复一个“次级管理员”等于恢复他的管理身份，只能由超管做；
        //   普通用户的恢复不受影响（user:manage 即可）。
        if (Role.SUB_ADMIN.equals(user.getRole()) && !isSuperOperator(currentUserId)) {
            throw new BizException("只有超级管理员可以恢复管理员账号");
        }
        if (user.getDeletedAt() == null) {
            throw new BizException("该账号并未注销，无需恢复");
        }
        userMapper.restoreById(id);
        // ★★ 关键一步：把"令牌生效起点"推到此刻 —— 注销前签发的票一律作废。
        //   ★ 用 nowForTokenCutoff()（截到秒）而不是 LocalDateTime.now()：
        //     否则"恢复后同一秒内登录"会被自己的纳秒判死（与改密码那条同一个坑）。
        userMapper.updateTokenValidAfter(id, nowForTokenCutoff());
        LOG.info("账号已恢复（并要求重新登录）：userId={} username={}", id, user.getUsername());
        return "恢复成功";
    }
}