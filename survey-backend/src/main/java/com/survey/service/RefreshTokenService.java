package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.RefreshToken;
import com.survey.entity.User;
import com.survey.mapper.RefreshTokenMapper;
import com.survey.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 刷新令牌业务 —— "记住我"的长效凭证（7 天，双 token 方案的第二枚）。
 *
 * ── 为什么需要它（而不是把 JWT 有效期拉长）──────────────
 *   见 {@link com.survey.entity.RefreshToken} 的类注释：JWT 无法吊销，
 *   把它的有效期从 12 小时拉到 7 天等于把"不可撤销的凭证"暴露 7 天。
 *   这里用"短 JWT + 可吊销的长效 refresh token"把风险收回来。
 *
 * ── 生命周期（改代码前务必理解）────────────────────────
 *   1. 登录勾选"记住我" → {@link #issue} 签发一枚随机串（**明文只返回给客户端一次**），
 *      库里只存它的 SHA-256；
 *   2. access token 过期后，客户端带 refresh token 调 /api/user/refresh →
 *      {@link #rotate} 校验（存在 / 未过期 / 未吊销）→ **旧令牌立即吊销** + 签发新的一枚
 *      （一次性轮换：被盗用会因"旧令牌已失效"而暴露）；
 *   3. 登出 → {@link #revokeByRawToken}（带 token 时精确吊销那一枚）
 *      或 {@link #revokeAllByUser}（没带 token 但有登录身份时，吊销该用户全部）。
 *
 * ── ★★ 谁会"吊销该用户全部令牌"（改这里前先看）──────────
 *   {@link #revokeAllByUser} 现在有**三个**调用点，都是"让该用户所有会话立刻失效"的语义：
 *     · \`UserController.logout\`（没带 refresh token 但有登录身份时）；
 *     · \`UserService.updatePassword\`（**改密码踢会话**）；
 *     · \`UserService.deleteAccount\`（注销账号）。
 *   ★ 但它们**只解决 refresh token**：已经签发的 access token（最长 12 小时）JWT 无法吊销，
 *     要靠 \`t_user.token_valid_after\` 作废 —— 改密码那条链路上两件事是一起做的
 *     （见 \`UserService.updatePassword\` 的注释）。**只吊销 refresh token 是不够的**：
 *     对方手里那枚旧 access token 还能继续用到自然过期。
 *
 * ── 与需求的对齐──────────────────────────────────────
 *   · 需求："校验凭证一次性使用" —— 本类是 refresh token 的轮换，滑块凭证的一次性在 CaptchaService；
 *   · 需求："记住我延长 JWT 有效期" —— 实现为"access 12h + refresh 7d"，
 *     ★ 用户明确要求把常见的 30 天改成 **7 天**，所以这里是 7 天。
 */
@Service
public class RefreshTokenService {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenService.class);

    /** refresh token 有效期：7 天（产品要求；改这里要同步前端提示与文档） */
    public static final int REFRESH_DAYS = 7;

    /** 明文令牌的随机字节数（32 字节 → Base64Url 43 字符；下面 hash 成 64 位十六进制存库） */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private RefreshTokenMapper refreshTokenMapper;
    @Autowired
    private UserMapper userMapper;

    /**
     * 签发一枚新的刷新令牌。
     *
     * @param userId 所属用户
     * @return 明文令牌（**只在这一刻存在于响应里**；库里只有它的 hash）
     */
    public String issue(Integer userId) {
        String raw = newRawToken();
        RefreshToken t = new RefreshToken();
        t.setUserId(userId);
        t.setTokenHash(sha256Hex(raw));
        t.setExpiresAt(LocalDateTime.now().plusDays(REFRESH_DAYS));
        refreshTokenMapper.insert(t);
        return raw;
    }

    /**
     * 用一枚 refresh token 换新的 access token（一次性轮换）。
     *
     * @param rawToken 客户端带来的明文令牌
     * @return 轮换后的结果：新明文令牌 + 它对应的用户（调用方据此签新的 access token）
     * @throws BizException 令牌缺失 / 不存在 / 已过期 / 已吊销 / 用户已不存在
     */
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BizException("缺少刷新令牌，请重新登录");
        }
        RefreshToken stored = refreshTokenMapper.findByHash(sha256Hex(rawToken));
        if (stored == null) {
            // 不区分"从没发过"和"已被清理" —— 对客户端都是"请重新登录"
            throw new BizException("登录状态已失效，请重新登录");
        }
        if (stored.getRevoked() != null && stored.getRevoked() != 0) {
            // ★ 这条日志值得留：一次性的令牌被用了第二次，通常意味着它被复制/盗用，
            //   或者客户端并发刷新（多标签页同时过期）—— 两者都该能查出来。
            LOG.warn("刷新令牌已被吊销却再次使用：userId={} tokenId={}", stored.getUserId(), stored.getId());
            throw new BizException("登录状态已失效，请重新登录");
        }
        if (stored.getExpiresAt() == null || stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException("登录状态已过期，请重新登录");
        }
        User user = userMapper.findById(stored.getUserId());
        if (user == null) {
            // 用户已被删除 → 令牌一并作废（否则会一直续出一个"幽灵会话"）
            refreshTokenMapper.revokeById(stored.getId());
            throw new BizException("账号不存在，请重新登录");
        }

        // 一次性轮换：旧的立即吊销，再签发新的
        refreshTokenMapper.revokeById(stored.getId());
        String newRaw = issue(user.getId());
        return new Rotation(user, newRaw);
    }

    /** 登出时吊销这一枚（找不到就静默 —— 登出必须总能成功）。 */
    public void revokeByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        RefreshToken stored = refreshTokenMapper.findByHash(sha256Hex(rawToken));
        if (stored != null) {
            refreshTokenMapper.revokeById(stored.getId());
        }
    }

    /** 吊销某用户的全部有效令牌（登出全部设备 / 改密码 时用）。 */
    public int revokeAllByUser(Integer userId) {
        if (userId == null) return 0;
        return refreshTokenMapper.revokeAllByUser(userId);
    }

    /**
     * 定期清理过期/陈旧记录（依赖启动类 @EnableScheduling）。
     * ★ 为什么必须有：这张表**只增不减**（每次登录勾"记住我"、每次轮换都会插一行），
     *   不清的话长期运行会一直涨；`expires_at` 上有索引，这条 DELETE 走范围扫描。
     * ★ 整体 try-catch：维护任务失败不该影响业务（与 ErrorLogService.purgeExpiredLogs 同款）。
     */
    @Scheduled(cron = "0 15 4 * * ?")
    public void purgeExpired() {
        try {
            int removed = refreshTokenMapper.deleteExpiredOrStale();
            if (removed > 0) {
                LOG.info("刷新令牌清理：删除过期/陈旧记录 {} 条", removed);
            }
        } catch (Exception e) {
            LOG.error("刷新令牌清理失败：{}", e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    /** 一次性轮换的返回值：新令牌 + 它属于哪个用户。 */
    public static final class Rotation {
        private final User user;
        private final String refreshToken;

        Rotation(User user, String refreshToken) {
            this.user = user;
            this.refreshToken = refreshToken;
        }

        public User getUser() { return user; }
        public String getRefreshToken() { return refreshToken; }
    }

    private String newRawToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * SHA-256 十六进制（64 字符，正好放进 varchar(128)）。
     * ★ 为什么用 SHA-256 而不是 PBKDF2（密码那种慢哈希）：
     *   refresh token 是**高熵随机串**（32 字节），不存在字典/暴力破解空间，
     *   慢哈希只会让每次刷新都多花 100ms；密码是低熵用户输入才必须慢哈希。
     */
    static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        }
    }
}
