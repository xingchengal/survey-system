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
 * 刷新令牌（"记住我"的长效凭证，7 天）：双 token 方案的第二枚。
 * ★ 库里只存 SHA-256，明文只在签发响应里出现一次；JWT 无法吊销，靠这枚可吊销的令牌收回风险。
 * ★ 一次性轮换：每次刷新都吊销旧令牌再签新的，旧令牌被盗用会因"已失效"暴露。
 * 改密码 / 注销账号必须调 revokeAllByUser，让该用户所有会话立刻失效。
 * 只吊销 refresh token 不够：已签发的 access token（最长 12h）要靠 t_user.token_valid_after 作废。
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
            // 一次性令牌被用第二次，通常意味着被复制/盗用或客户端并发刷新，这条日志要留。
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
     * 定期清理过期 / 陈旧记录（依赖启动类 @EnableScheduling）。
     * 表只增不减（每次登录、每次轮换都插行），不清会一直涨；expires_at 有索引，DELETE 走范围扫描。
     * 整体 try-catch：维护任务失败不该影响业务。
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
     * 不用 PBKDF2：refresh token 是 32 字节高熵随机串，无字典/暴力空间，慢哈希只会让每次刷新多花 100ms。
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
