package com.survey.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具：签发 token、校验并解析 token。
 *
 * 密钥与有效期只认 application.yaml 一处（jwt.secret / jwt.expiration），写两处改漏一处会让有效期静默不符预期。
 * ★ 启动即校验密钥：空或不足 32 字节直接启动失败 —— 公开的默认密钥等于任何人都能自签 role=admin。
 * parseToken 校验失败一律返回 null（不抛异常），由调用方决定 401 / 403。
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    /**
     * 启动即校验密钥：@PostConstruct 保证在任何请求进来之前检查完。
     * 宁可起不来，也不要带着弱密钥或公开密钥上线。
     */
    @PostConstruct
    public void validateSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret 未配置！本地开发请确认 "
                            + "survey-backend/src/main/resources/application-local.yml 存在"
                            + "（或者显式指定 --spring.profiles.active=local）；"
                            + "生产请设置环境变量 JWT_SECRET（至少 32 个字符）。");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "jwt.secret 长度不足 32 个字符（HMAC-SHA256 要求密钥至少 256 位）。");
        }
    }

    /** 由密钥字符串构造 HMAC-SHA256 签名密钥；UTF-8 字节数必须 ≥ 32，否则启动校验会拦下。 */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 token（登录成功时调用）。
     *
     * @param role "admin" 或 "user" —— 权限判断就靠它，所以签名密钥必须保密
     * @return 紧凑格式的 JWT 字符串
     */
    public String generateToken(Integer userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);

        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getKey())
                .compact();
    }

    /**
     * 按指定有效期签发 token，ttl 硬性夹紧到 [60 秒, 7 天]。
     * 「记住我」不走这里：JWT 无法吊销，7 天不可撤销凭证一旦被 XSS 偷走，改密码也救不回来，
     * 那里改用可轮换的 refresh token（见 RefreshTokenService）。
     *
     * @param ttlMillis 期望有效期（毫秒），会被夹到 [60s, 7d]，防止签出立即过期或天数字
     */
    public String generateToken(Integer userId, String username, String role, long ttlMillis) {
        long min = 60_000L;
        long max = 7L * 24 * 60 * 60 * 1000;   // 7 天
        long ttl = Math.max(min, Math.min(max, ttlMillis));

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);

        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttl);

        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getKey())
                .compact();
    }

    /**
     * 校验并解析 token：签名错误、过期、格式错误都返回 null（不抛异常），调用方代码可以很简单。
     *
     * @return 载荷 Claims；无效/过期返回 null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 校验并解析 token，并要求签发时间严格晚于 validAfter（改密码 / 恢复账号时踢掉旧票）。
     * validAfter 为 null 表示不限制；iat 缺失视为无效，宁可让人重新登录。
     *
     * @param validAfter 令牌生效起点；null 表示不限制
     * @return 载荷 Claims；验签/过期/**早于 validAfter** 都是 null
     */
    public Claims parseToken(String token, java.time.LocalDateTime validAfter) {
        Claims claims = parseToken(token);
        if (claims == null || validAfter == null) {
            return claims;
        }
        return isIssuedAtOrAfter(claims, validAfter) ? claims : null;
    }

    /**
     * 这枚票的签发时间是否**严格晚于** validAfter（方法名只管字面，语义以此为准）。
     * ★ 必须严格晚于而非「不早于」：iat 只有秒精度，同秒旧票会被放行，改密码就踢不掉会话。
     * 比较全程用纪元毫秒，避免 iat（UTC）与 validAfter（本地时间）两套时间表示互转出错。
     *
     * @return true = 票仍有效（iat 严格晚于 validAfter）；false = 该拒（iat 缺失，或等于/早于 validAfter）
     */
    public static boolean isIssuedAtOrAfter(Claims claims, java.time.LocalDateTime validAfter) {
        if (claims == null) return false;
        Date issuedAt = claims.getIssuedAt();
        // 顺序重要：先判 iat 是否存在，再看 validAfter —— 否则没有 iat 的畸形票会被跳过检查。
        if (issuedAt == null) {
            return false;
        }
        if (validAfter == null) {
            // 该账号从未重置令牌生效起点（没改过密码、没被恢复过）⇒ 不限制签发时间
            return true;
        }
        Date cutoff = Date.from(validAfter.atZone(java.time.ZoneId.systemDefault()).toInstant());
        // 严格晚于：iat 只有秒精度，与整秒的 cutoff 相等时必须拒，否则改密码前签发的旧票仍有效。
        return issuedAt.after(cutoff);
    }

    /** 文档通行证的有效期：2 小时（远短于登录 token，泄露窗口小得多） */
    private static final long DOC_TOKEN_EXPIRATION = 2 * 60 * 60 * 1000L;

    /** 文档通行证的 scope 值 —— DocAccessFilter 靠它区分"这是文档票"还是"这是登录票" */
    public static final String SCOPE_DOC = "doc";

    /**
     * 签发「接口文档通行证」：有效期 2 小时 + {@code scope=doc}，DocAccessFilter 会校验 scope 与 role。
     * 不复用登录 token：Cookie 泄露时不该拿到完整登录凭证；业务接口走 Authorization 头，不认这枚 Cookie。
     *
     * @return 带 scope=doc 的短时 JWT（由 DocAccessFilter 限制为管理员）
     */
    public String generateDocToken(Integer userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);
        claims.put("scope", SCOPE_DOC);      // 与登录 token 的关键区别

        Date now = new Date();
        Date expiry = new Date(now.getTime() + DOC_TOKEN_EXPIRATION);

        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getKey())
                .compact();
    }

    // 不要再加 getUserId / getRole / getUsername 这类便捷方法：每个都会重复调一次 parseToken（多一次验签）。
}
