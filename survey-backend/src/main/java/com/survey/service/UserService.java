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
 * 密码存 PBKDF2WithHmacSHA256（10 万次迭代 + 16 字节随机盐），格式 pbkdf2$迭代数$盐$哈希，约 83 字符。
 * 列宽 varchar(100) 只够当前迭代数量级：调大 PBKDF2_ITERATIONS 前先核列宽。
 * 存量 MD5 登录成功后自动升级；校验用 MessageDigest.isEqual 做常量时间比较。
 */
@Service
public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    /** 导出上限：SQL 固定 LIMIT 10001，超出明确拒绝，不静默截断。 */
    public static final int EXPORT_MAX_ROWS = 10_000;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SurveyMapper surveyMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    /** 删用户级联复用 SurveyService.cascadeDeleteSurvey 的顺序，不在本类手写删除逻辑。 */
    @Autowired
    private SurveyService surveyService;

    /** 注册赠送 10 点额度；赠送走独立事务，失败不回滚注册。 */
    @Autowired
    private WalletService walletService;

    /** 仅用于兼容存量 MD5 密码；新密码一律走 hashPassword。 */
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
            // 固定文案，不把底层异常 message 回显给客户端（可能泄露算法/Provider 细节）。
            throw new IllegalStateException("MD5 摘要算法不可用", e);
        }
    }

    // ==================== 密码哈希：PBKDF2（JDK 自带，零依赖） ====================

    private static final String PBKDF2_PREFIX = "pbkdf2$";
    private static final int PBKDF2_ITERATIONS = 100000;
    private static final int PBKDF2_SALT_BYTES = 16;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 生成 PBKDF2 加盐哈希：pbkdf2$迭代数$盐$哈希，约 83 字符，varchar(100) 放得下。 */
    public static String hashPassword(String plain) {
        byte[] salt = new byte[PBKDF2_SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(plain, salt, PBKDF2_ITERATIONS);
        return PBKDF2_PREFIX + PBKDF2_ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 校验密码：pbkdf2$ 开头按 PBKDF2；32 位十六进制按存量 MD5；其余格式一律失败（交管理员重置）。
     * 登录成功后由 login() 把 MD5 自动升级为 PBKDF2。
     */
    public static boolean verifyPassword(String plain, String stored) {
        if (plain == null || stored == null) return false;
        if (!isPbkdf2(stored)) {
            // 只对 32 位十六进制走 MD5 兼容：任意非法格式都当 MD5 会形成语义不明的比较分支。
            if (!isLegacyMd5(stored)) {
                return false;
            }
            // 常量时间比较：换成 equals 会按字节泄露比较进度。
            return MessageDigest.isEqual(
                    md5(plain).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    stored.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            // 迭代次数来自被校验的数据本身，必须限幅：一行坏数据能让每次登录跑 2 亿次迭代。
            if (iterations < 1 || iterations > PBKDF2_ITERATIONS * 10) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(plain, salt, iterations);
            // 常量时间比较，避免时序攻击。
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPbkdf2(String stored) {
        return stored != null && stored.startsWith(PBKDF2_PREFIX);
    }

    /** 存量 MD5 格式：32 位十六进制；只有匹配它的记录才走兼容分支。 */
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
            // 同上：固定文案，不泄露底层异常。
            throw new IllegalStateException("PBKDF2 密钥派生不可用", e);
        }
    }

    /**
     * 注册（无手机号）：用户名非空、密码至少 6 位、用户名不重复（唯一索引兜底）。
     * role 一律强制 user；密码在这里哈希。失败抛 BizException，不要改成 return 中文串。
     *
     * @return "注册成功"（HTTP 200）
     */
    public String register(User user) {
        return register(user, null);
    }

    /**
     * 注册（带手机号）：手机号只存不用，格式由 RegisterRequest 的 @Pattern 校验。
     * 这里只 trim + 空串归 null（避免 "" 与 NULL 两种"没填"）；返回 "注册成功"。
     *
     * @param phone 手机号，可空
     */
    @Transactional
    public String register(User user, String phone) {
        // 先 trim 再查重/入库：否则 "admin " 与 "admin" 是两个看起来一样的账号。
        String username = user.getUsername() == null ? null : user.getUsername().trim();
        if (username == null || username.isEmpty()) {
            throw new BizException("账号不能为空");
        }
        if (user.getPassword() == null || user.getPassword().length() < 6) {
            throw new BizException("密码至少6位");
        }
        if (userMapper.findByUsernameIncludeDeleted(username) != null) {
            // 必须查含已注销的：注销账号仍占用户名（防抢注），过滤版会"校验通过 → 撞唯一索引 → 500"。
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
        // insert 的 SQL 不含 phone，需单独补一次 UPDATE；两步必须同事务。
        if (user.getPhone() != null && user.getId() != null) {
            userMapper.updatePhone(user.getId(), user.getPhone());
        }
        // 注册赠送走独立事务，失败只记日志，不回滚账号。
        try {
            walletService.grantSignupBonus(user.getId());
        } catch (Exception e) {
            LOG.warn("注册赠送额度失败（不影响注册）：userId={} —— {}", user.getId(), e.getMessage());
        }
        return "注册成功";
    }

    /** 账号格式：4~20 位字母数字；注册由 @Pattern 强制，登录侧受 strictUsernameCheck 开关控制。 */
    private static final java.util.regex.Pattern ACCOUNT_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9]{4,20}$");

    /** 刷新令牌：改密码/注销/退出时全量吊销；RefreshTokenService 只依赖 Mapper，避免循环依赖。 */
    @Autowired
    private RefreshTokenService refreshTokenService;

    /**
     * 登录是否严格校验账号格式（默认 true，可用 app.strict-username-check=false 关闭）。
     * 关掉是为了让存量中文/短账号还能登录；注册始终强制新规则。
     */
    @org.springframework.beans.factory.annotation.Value("${app.strict-username-check:true}")
    private boolean strictUsernameCheck;

    /**
     * 登录校验：账号不存在、密码错、格式不合法都返回 null（不区分，避免账号探测）。
     * 不做密码长度校验（那是注册规则）；存量 MD5 验证通过后就地升级为 PBKDF2。
     *
     * @return 登录用户（password 仍是哈希，Controller 置空后返回）；失败返回 null
     */
    public User login(String username, String password) {
        // 与 register 对称 trim，否则多带一个空格就登不进自己的账号。
        String name = username == null ? null : username.trim();
        if (name == null || name.isEmpty()) return null;
        if (strictUsernameCheck && !ACCOUNT_PATTERN.matcher(name).matches()) {
            // 可预期的输入，只记 warn，不写异常日志。
            LOG.warn("登录账号格式不符合规则（已按失败处理）：长度={} 仅字母数字={}",
                    name.length(), name.chars().allMatch(Character::isLetterOrDigit));
            return null;
        }
        User user = userMapper.findByUsername(name);
        if (user == null) return null;
        if (!verifyPassword(password, user.getPassword())) return null;

        // 存量 MD5：验证通过后顺手升级为 PBKDF2。
        if (!isPbkdf2(user.getPassword())) {
            userMapper.updatePassword(user.getId(), hashPassword(password));
        }
        return user;
    }

    /** 按 id 查未注销用户：过滤器/日志/异常处理都用它取当前用户。 */
    public User getById(Integer id) {
        return userMapper.findById(id);
    }

    /** 按 id 查未注销用户，含头像；只有个人中心用（avatar 是 mediumtext，单张可达 2MB）。 */
    public User getByIdWithAvatar(Integer id) {
        return userMapper.findByIdWithAvatar(id);
    }

    /**
     * 分页查在用用户（管理员）：关键词 + 角色 + 禁用状态筛选。
     * role 非法值按"全部"处理；disabled=null 表示不过滤（否则被禁用账号会消失、无法解禁）。
     */
    public PageResult<User> listActiveUsersPage(int pageNum, int pageSize, String keyword,
                                                String role, Integer disabled) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // 必须转义 LIKE 通配符：否则 % / _ 会静默绕过筛选。
        keyword = SqlLikeEscaper.escape(keyword);
        List<User> list = userMapper.findActiveUsers(keyword, normalizeRoleFilter(role), normalizeDisabledFilter(disabled));
        PageInfo<User> pageInfo = new PageInfo<>(list);
        // 回显钳制后的页码/页大小，否则请求 pageSize=100000 时与实际数据不符。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /** 分页查已注销用户（管理员恢复账号用），按 deleted_at 倒序；LIKE 同样要转义。 */
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

    /** 导出用户（不分页，最多 10000 行）：deletedView=true 走已注销口径；超出明确拒绝，不静默截断。 */
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
     * 禁用/解禁：不能操作自己；超级管理员不可被禁用/解禁；已注销账号要先恢复。
     * 同时写 token_valid_after，让已签发的 access token 立即失效（解禁方向也写）。
     *
     * @param disabled 1=禁用 0=解禁
     */
    @Transactional
    public String setDisabled(Integer id, Integer disabled, Integer currentUserId) {
        if (id == null) throw new BizException("请指定要操作的用户");
        if (disabled == null || (disabled != 0 && disabled != 1)) {
            throw new BizException("禁用标记只能是 0（解禁）或 1（禁用）");
        }
        // 用含已注销的查询，才能给出"请先恢复账号"的明确提示。
        User user = userMapper.findByIdIncludeDeleted(id);
        if (user == null) throw new BizException("用户不存在");

        boolean disabling = disabled == 1;

        if (user.getDeletedAt() != null) {
            throw new BizException("该账号已注销，请先恢复账号再设置禁用状态");
        }
        // 自己优先判：文案比"超级管理员不能被禁用"更贴近用户动作。
        if (disabling && id.equals(currentUserId)) {
            throw new BizException("不能禁用自己的账号");
        }
        // 超管不可被禁用/解禁：只要超管可用，系统就一定有人能进后台。
        if (Role.isAdmin(user.getRole())) {
            throw new BizException("超级管理员不能被禁用或解禁");
        }
        // 次级管理员只有超管能操作。
        if (Role.SUB_ADMIN.equals(user.getRole()) && !isSuperOperator(currentUserId)) {
            throw new BizException("只有超级管理员可以操作管理员账号");
        }

        int result = userMapper.updateDisabled(id, disabled);
        if (result <= 0) throw new BizException("操作失败");
        // 作废此刻已签发的票；截到秒，与 JWT 的 iat 精度一致。
        userMapper.updateTokenValidAfter(id, nowForTokenCutoff());
        LOG.info("账号禁用状态已更新：userId={} username={} disabled={} 操作人={}",
                id, user.getUsername(), disabled, currentUserId);
        return disabling ? "已禁用" : "已解禁";
    }

    /**
     * 删除用户并级联清掉名下所有数据（含回收站问卷）：问卷 → 通知 → 用户，整体回滚。
     * 不可逆且会删掉别人填过的答卷，所以拦"删自己"与超管；次级管理员只有超管能删。
     * currentUserId 只来自 X-User-Id，不是请求体可伪造的字段。
     *
     * @param id 要删除的用户 id；currentUserId 为当前管理员 id
     */
    @Transactional
    public int deleteUser(Integer id, Integer currentUserId) {
        // 必须查含已注销的，否则已注销账号永远删不掉。
        User user = userMapper.findByIdIncludeDeleted(id);
        if (user == null) return 0;

        if (id.equals(currentUserId)) {
            throw new BizException("不能删除自己的账号（如需注销，请让其他管理员操作）");
        }
        // 超管永远不可删除（即使调用者也是超管），这是"系统一定还有超管"的最强保证。
        if (Role.isAdmin(user.getRole())) {
            throw new BizException("不能删除超级管理员账号");
        }
        // 次级管理员只有超管能删。
        if (Role.SUB_ADMIN.equals(user.getRole()) && !isSuperOperator(currentUserId)) {
            throw new BizException("只有超级管理员可以删除管理员账号");
        }

        // 1. 找出该用户的所有问卷（含回收站）。
        List<Survey> surveys = surveyMapper.findByUserIdIncludeDeleted(id);

        for (Survey s : surveys) {
            // 复用 cascadeDeleteSurvey 的自底向上顺序，不在这里手写。
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

    /** 改昵称：不允许全空白（会写进操作日志），上限 50 与注册侧 @Size 对齐。 */
    public String updateNickname(Integer userId, String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new BizException("昵称不能为空");
        }
        if (nickname.trim().length() > 50) {
            throw new BizException("昵称不能超过 50 个字符");
        }
        User user = userMapper.findById(userId);
        if (user == null) throw new BizException("用户不存在");
        userMapper.updateNickname(userId, nickname.trim());
        return "修改成功";
    }

    /**
     * 改密码：要求原密码（防 token 被盗后改密锁号）；新密码 6~64 位。
     * 必须同时作废已签发的 access token 与 refresh token，只做一半等于没踢干净。
     */
    @Transactional
    public String updatePassword(Integer userId, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isEmpty()) throw new BizException("请输入原密码");
        if (newPassword == null || newPassword.length() < 6) throw new BizException("新密码至少6位");
        // 上限 64：几 MB 的新密码会让每次登录都跑一次 10 万次迭代。
        if (newPassword.length() > 64) throw new BizException("新密码不能超过 64 位");
        User user = userMapper.findById(userId);
        if (user == null) throw new BizException("用户不存在");
        if (!verifyPassword(oldPassword, user.getPassword())) throw new BizException("原密码错误");
        userMapper.updatePassword(userId, hashPassword(newPassword));
        // ① 作废已签发的 access token。
        userMapper.updateTokenValidAfter(userId, nowForTokenCutoff());
        // ② 吊销全部 refresh token（否则能续出新票、绕过 ①）。
        refreshTokenService.revokeAllByUser(userId);
        LOG.info("密码已修改，并作废该用户此前的全部登录凭证：userId={}", userId);
        return "修改成功";
    }

    /**
     * 令牌生效起点：当前时间截到秒。
     * 带纳秒会让"同一秒内重新登录"的新票 iat 早于起点，用户被自己的时间戳锁在门外。
     */
    private static java.time.LocalDateTime nowForTokenCutoff() {
        return java.time.LocalDateTime.now().withNano(0);
    }

    /** 当前操作人是否超管：以库里的角色为准（被降级后立即失效），只在目标是 staff 时查。 */
    private boolean isSuperOperator(Integer userId) {
        if (userId == null) {
            return false;
        }
        User operator = userMapper.findById(userId);
        return operator != null && Role.isAdmin(operator.getRole());
    }

    /**
     * 改头像：base64 data URL 存 mediumtext；校验收口在 AvatarValidator（格式/魔数/解码后 ≤2MB）。
     * 必须先判用户存在，否则令牌未过期时会返回假成功。
     */
    public String updateAvatar(Integer userId, String avatar) {
        if (userMapper.findById(userId) == null) throw new BizException("用户不存在");
        try {
            com.survey.common.AvatarValidator.validate(avatar);
        } catch (IllegalArgumentException e) {
            // 翻成 BizException（400），否则会被全局处理器当成 500。
            throw new BizException(e.getMessage());
        }
        userMapper.updateAvatar(userId, avatar);
        return "修改成功";
    }

    /**
     * 改联系方式：空值表示清空；格式校验在 ContactRequest 的 @Pattern，本类不再写第二套正则。
     * 这里只归一空白并落库，避免库里出现 '' 与 NULL 两种"没有值"。
     */
    public String updateContact(Integer userId, String phone, String email) {
        // 先判存在，避免幽灵用户拿到假成功。
        if (userMapper.findById(userId) == null) throw new BizException("用户不存在");
        userMapper.updateContact(userId, blankToNull(phone), blankToNull(email));
        return "修改成功";
    }

    /** 空白归一成 null：与 Mapper 的 NULLIF 两层，这一层负责多个空格。 */
    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ==================== 账号注销（软删除） ====================

    /**
     * 账号注销：软删除，只写 deleted_at，名下问卷与答卷原样保留（不销毁别人的数据）。
     * 要求密码；超管不能注销；同时吊销 refresh token，返回 "注销成功"。
     *
     * @param userId 当前登录用户；rawPassword 用于确认身份
     */
    @Transactional
    public String deleteAccount(Integer userId, String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new BizException("请输入密码以确认注销");
        }
        User user = userMapper.findById(userId);
        if (user == null) {
            // findById 过滤了 deleted_at，已注销账号也走这条。
            throw new BizException("账号不存在或已注销");
        }
        // 超管不能自我注销：系统必须永远保留这个账号。
        if (Role.isAdmin(user.getRole())) {
            throw new BizException("超级管理员不能注销账号");
        }
        // 与登录共用校验（含 MD5 兼容），不要另写一套。
        if (!verifyPassword(rawPassword, user.getPassword())) {
            throw new BizException("密码错误");
        }
        int affected = userMapper.softDeleteById(userId);
        if (affected <= 0) {
            // 理论上到不了，除非并发下另一个请求抢先注销。
            throw new BizException("账号不存在或已注销");
        }
        refreshTokenService.revokeAllByUser(userId);
        LOG.info("账号已注销（软删除）：userId={} username={}", userId, user.getUsername());
        return "注销成功";
    }

    /**
     * 恢复已注销账号：密码、资料、问卷都还在；同时写 tokenValidAfter，恢复后必须重新登录。
     * 那一行不能删：否则注销前签发的 access token 会随 deleted_at 清空而复活。
     */
    @Transactional
    public String restoreUser(Integer id, Integer currentUserId) {
        if (id == null) throw new BizException("请指定要恢复的用户");
        User user = userMapper.findByIdIncludeDeleted(id);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        // 恢复次级管理员等于恢复管理身份，只有超管能做。
        if (Role.SUB_ADMIN.equals(user.getRole()) && !isSuperOperator(currentUserId)) {
            throw new BizException("只有超级管理员可以恢复管理员账号");
        }
        if (user.getDeletedAt() == null) {
            throw new BizException("该账号并未注销，无需恢复");
        }
        userMapper.restoreById(id);
        userMapper.updateTokenValidAfter(id, nowForTokenCutoff());
        LOG.info("账号已恢复（并要求重新登录）：userId={} username={}", id, user.getUsername());
        return "恢复成功";
    }
}