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
 * ── token 里装了什么 ──────────────────────────────────
 *   三个自定义 claim：userId / username / role，加上签发时间与过期时间。
 *   密钥与有效期来自 jwt.secret / jwt.expiration（当前 = **12 小时 = 43200000 毫秒**，见 application.yaml）。
 *   ★ 有效期**只有 application.yaml 一个来源**：application-local.yml 里故意不写 jwt.expiration。
 *     同一个值写在两处时，改一处漏一处会让 token 有效期静默不符预期。
 *
 * ── 谁在用 ────────────────────────────────────────────
 *   filter/JwtAuthFilter       → parseToken 校验登录态（Authorization 头）
 *   filter/DocAccessFilter     → parseToken 校验文档 Cookie（doc_token）
 *   controller/UserController  → generateToken 登录成功后签发
 *
 * ── ★ 启动即校验密钥（validateSecret）──────────────────
 *   密钥为空或 UTF-8 字节数 < 32 → **直接启动失败**（fail fast）。
 *   宁可起不来，也不要带着弱密钥或公开密钥上线：公开的默认密钥等于任何人都能自签
 *   一枚 role=admin 的 token，绕过全部认证。
 *
 * ── 解析失败的行为 ────────────────────────────────────
 *   parseToken 捕获所有异常并返回 null（过期、签名不符、格式错误都归为"无效"），
 *   由调用方决定怎么响应：过滤器里就是 401 / 403。
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    /**
     * ★ 启动即校验密钥（见类注释里的背景说明）。
     * 用 @PostConstruct 保证在任何请求进来之前就检查完。
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

    /** 由密钥字符串构造 HMAC-SHA256 签名密钥。★ 字节数必须 ≥ 32，否则会在启动校验里被拦下。 */
    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 token（登录成功时调用）。
     *
     * @param userId   用户 id，写进 claim
     * @param username 用户名
     * @param role     "admin" 或 "user" —— ★ 权限判断就靠它，所以签名密钥必须保密
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
     * 按指定有效期签发 token。
     *
     * ── 为什么需要这个方法（以及"记住我"为什么不直接把 JWT 签成 7 天）──
     *   JWT **无法吊销**（项目没有 token 黑名单）。把它的有效期从 12 小时拉到 7 天，
     *   等于把一个**不可撤销**的凭证暴露 7 天 —— 一旦被 XSS 偷走，改密码都救不回来。
     *   所以"记住我"走**双 token**：access token（仍然 12 小时，短窗口限制损失）
     *   + refresh token（t_refresh_token，7 天、可吊销、一次性轮换，见 RefreshTokenService）。
     *   ★ 本方法留给"确实需要非默认有效期"的调用方（当前无调用点，属**有意保留的扩展点**：
     *     例如给第三方集成签发不同时长的票据）。
     *
     * ★ 硬性夹紧：最短 60 秒、最长 7 天 —— 防止调用方传 0（签出"立即过期"的票，极难排查）
     *   或传天文数字（等于让"把有效期缩短到 12 小时"这条安全约束失效）。
     *
     * @param userId    用户 id
     * @param username  用户名
     * @param role      "admin" / "user"
     * @param ttlMillis 期望有效期（毫秒），会被夹到 [60s, 7d]
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
     * 校验并解析 token。
     * ★ 校验失败一律返回 null（不抛异常），这样调用方的代码可以很简单。
     *   会校验：签名是否正确、是否已过期。
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
     * 除了验签与过期，再判一次「**这枚票是不是在某个时间点之前签发的**」。
     *
     * ── 为什么需要它（这是"JWT 无法吊销"的唯一解法）──────────
     *   JWT 是**自包含**的：服务端签出去之后没有黑名单机制，所以"改密码踢掉别的设备"、
     *   "恢复已注销账号后强制重新登录"这两件事，光靠签名与过期时间做不到。
     *   唯一可行的办法是**在用户表上记一个时间点**（`t_user.token_valid_after`），
     *   然后拒绝"签发时间（`iat`）早于它"的票 —— 本方法就是那次判定。
     *
     * ── 参数为什么是 LocalDateTime 而不是自己查库 ────────────
     *   本类是纯工具、**不依赖任何 Mapper/Service**（它被两个 Filter 与 Controller 共用）。
     *   查用户是**调用方**的事（Filter 本来就要为"账号是否还有效"查一次），
     *   把那一列当参数传进来，本工具不额外增加查询次数。
     *   ★ 现状：本重载**当前没有调用点** —— 两个过滤器统一调静态的
     *     {@link #isIssuedAtOrAfter}（职责更单一）。保留它是为"一次调用即带校验"的场景。
     *
     * ── 边界约定（★ 必须严格晚于，否则旧票会漏网）──────────
     *   · `validAfter` 为 **null** → 不限制（绝大多数用户、绝大多数时候都是这一支）；
     *   · `iat` 为 null（畸形票）→ 视为无效，宁可让人重新登录；
     *   · 判定用**严格晚于**（`iat > validAfter`，见下面的 isIssuedAtOrAfter）：**同秒的票一律拒**。
     *     ★ 为什么不能用"不早于"（`iat >= validAfter`）：`iat` 只有秒精度，而 cutoff 也是整秒，
     *     "改密码前同一秒内签发"的旧票会被放行 —— 旧票没被踢掉，安全目标落空。
     *     代价是"改完密码同一秒内重新登录"会被拒一次（无感，再点一次即可）—— 这是**有意的取舍**。
     *   · 写入值来自应用侧 `UserService.nowForTokenCutoff()`（**截到秒**），不是数据库 NOW()。
     *
     * @param token      紧凑格式 JWT
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
     * 判定"这枚票的签发时间是否**严格晚于** validAfter"。
     * ★ 方法名里的 "OrAfter" 只是字面命名，语义以本 javadoc 为准：**严格晚于**。
     *
     * ★★ 为什么用**纪元毫秒**比较（两个坑：时区与精度）：
     *   ① **时区**：`iat` 是 UTC 时间点，`token_valid_after` 是数据库里的**本地时间**
     *      （MySQL 会话时区 = SYSTEM = Asia/Shanghai）。把 `iat` 换算成
     *      `LocalDateTime.ofInstant(..., ZoneId.systemDefault())` 再比虽然理论上等价，
     *      但"两套时间表示互转"的写法一旦哪边口径变了就会静默出错、且很难看出来。
     *      改成"把 validAfter 也变成 `Date`，直接比毫秒"——**全程不出现时区**。
     *   ② **精度**：`iat` 只有**秒**，而写入方若带纳秒（如 `16:53:53.900`），
     *      同秒内新签发的票（`16:53:53.000`）会被判成"早于"而拒掉 —— 用户被自己的时间戳
     *      锁在门外（症状：改完密码立刻重新登录仍是 401）。写入侧已统一截到秒
     *      （见 UserService.nowForTokenCutoff），这里再用 `before` 比较，两侧口径一致。
     *   ⇒ 两个过滤器都调它，保证"改密码"与"恢复账号"两条链路的判定完全一致。
     *
     * @param claims     JWT 载荷（必须能取到 iat）
     * @param validAfter 令牌生效起点（本地时间）；null 表示不限制 → 返回 true
     * @return true = 票仍然有效（iat 严格晚于 validAfter）；false = 该拒（iat 缺失，或等于/早于 validAfter）
     */
    public static boolean isIssuedAtOrAfter(Claims claims, java.time.LocalDateTime validAfter) {
        if (claims == null) return false;
        Date issuedAt = claims.getIssuedAt();
        // ★★ 顺序很重要：**先判 iat 是否存在**，再看 validAfter。
        //   若把 `validAfter == null → return true` 放在最前面，那么"账号从未改过密码"
        //   （validAfter 为 null，绝大多数账号的常态）时，**没有 iat 的畸形票会被整段跳过检查**。
        //   正常签发的票一定有 iat，所以这不是一个可利用的漏洞（伪造票过不了验签）；
        //   但"少一个 claim 反而更容易通过"是错的逻辑方向，必须挡在这里。
        if (issuedAt == null) {
            return false;
        }
        if (validAfter == null) {
            // 该账号从未"重置令牌生效起点"（没改过密码、没被恢复过）⇒ 不限制签发时间
            return true;
        }
        Date cutoff = Date.from(validAfter.atZone(java.time.ZoneId.systemDefault()).toInstant());
        // ★★ 判定用**严格晚于**（`after`），不是"不早于"：
        //   因为 `iat` 只有秒精度，而 cutoff 也是整秒 ⇒ "同一秒内签发"的票与 cutoff **相等**。
        //   这里必须选择"宁可错杀"：
        //     · 用 `after`（现状）：同秒的票一律被拒 —— 对"改密码踢会话"这个目标，这是**正确**的方向
        //       （否则"改密码前签发的票仍然有效"，正是这条安全缺口的实质）；
        //     · 用 `!before`：同秒的票放行 —— "① 登录 → ② 同一秒内改密码 → ③ 拿①的旧票请求"
        //       会返回 200，**旧票没被踢掉**，安全目标落空。
        //   ⇒ 代价（如实说明）：用户在**同一秒内**"改完密码立刻重新登录"时，新票会被拒一次。
        //     这只是**无感的一秒**（再点一次登录即可），比"旧票有概率活下来"划算得多。
        return issuedAt.after(cutoff);
    }

    /** 文档通行证的有效期：2 小时（远短于登录 token，泄露窗口小得多） */
    private static final long DOC_TOKEN_EXPIRATION = 2 * 60 * 60 * 1000L;

    /** 文档通行证的 scope 值 —— DocAccessFilter 靠它区分"这是文档票"还是"这是登录票" */
    public static final String SCOPE_DOC = "doc";

    /**
     * 签发「接口文档通行证」token。
     *
     * ── 为什么要和登录 token 分开 ─────────────────────────
     *   若 doc_token 这个 Cookie 里放的是登录用的那枚 token，会有两个后果：
     *     ① Cookie 一旦泄露，攻击者拿到的是**完整登录凭证**，而不只是一张文档门票；
     *     ② Cookie 的 maxAge 与 token 的 exp 各自独立，很容易对不上。
     *   所以文档票单独签发：**有效期 2 小时** + `scope=doc`。
     *   拿到它只能看接口文档 —— 因为 DocAccessFilter 会校验 scope，
     *   而业务接口走的是 JwtAuthFilter（Authorization 头），根本不认这枚 Cookie。
     *
     * @param userId   用户 id
     * @param username 用户名
     * @param role     "admin" —— 文档只给管理员看，DocAccessFilter 会同时校验 scope 和 role
     * @return 带 scope=doc 的短时 JWT
     */
    public String generateDocToken(Integer userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role);
        claims.put("scope", SCOPE_DOC);      // ★ 与登录 token 的关键区别

        Date now = new Date();
        Date expiry = new Date(now.getTime() + DOC_TOKEN_EXPIRATION);

        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getKey())
                .compact();
    }

    // ★ 这里**不要**再加 getUserId / getRole / getUsername 这类"便捷方法"：
    //   它们全项目没有任何调用点（两个过滤器都是直接从 parseToken(...) 的 Claims 取值），
    //   而且每个都会**重复调一次 parseToken**（多一次验签）。
    //   需要取值时直接用 parseToken(token) 的返回值即可。
}
