package com.survey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserService 密码哈希逻辑的纯单元测试。
 *
 * ── 测试范围与原则 ────────────────────────────────────
 * 只测 {@code hashPassword} / {@code verifyPassword} / {@code md5} 这些 static 方法，
 * **不启动 Spring 容器、不连数据库**（UserService 的实例方法都要 UserMapper，
 * 不在本次范围内）。
 *
 * ── 关于 isPbkdf2 ─────────────────────────────────────
 * 生产代码里 isPbkdf2 是 **private static**，无法直接调用；本测试用反射只读地调用它，
 * 目的是把"PBKDF2 存储格式的判定契约"钉死。
 * ★ 不为了让测试好写而修改生产代码的访问修饰符。
 */
class UserServiceTest {

    /** 历史遗留的 MD5 存储值：明文 123456 的 MD5（32 位十六进制、无盐）。 */
    private static final String MD5_OF_123456 = "e10adc3949ba59abbe56e057f20f883e";

    /** 生产代码里 PBKDF2 迭代次数固定 10 万次；这里是格式契约的一部分，改了要同步。 */
    private static final String EXPECTED_ITERATIONS = "100000";

    /**
     * 反射调用生产代码里的 private static boolean isPbkdf2(String)。
     * 只用它读取判定结果，不修改任何生产代码。
     */
    private static boolean invokeIsPbkdf2(String stored) {
        try {
            Method m = UserService.class.getDeclaredMethod("isPbkdf2", String.class);
            m.setAccessible(true);
            return (Boolean) m.invoke(null, stored);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "反射调用 UserService.isPbkdf2(String) 失败 —— 生产代码的签名/可见性可能变了，请同步本测试", e);
        }
    }

    // ==================== 1. 哈希格式 + isPbkdf2 ====================

    @Test
    @DisplayName("hashPassword 产出 pbkdf2$迭代数$盐$哈希 四段格式，且 isPbkdf2 判定为 true")
    void hashPassword_shouldProducePbkdf2Format() {
        String stored = UserService.hashPassword("123456");

        // 防回归：格式一旦退回"裸 MD5 / 明文 / 少一段"，历史上所有已存密码都会验不过，
        // 同时也会丢掉加盐与迭代强度，这里把四段结构、迭代次数、盐长、哈希长全部钉死。
        String[] parts = stored.split("\\$");
        assertEquals(4, parts.length, "PBKDF2 存储必须是 pbkdf2$迭代数$盐$哈希 四段");
        assertEquals("pbkdf2", parts[0], "前缀必须是 pbkdf2");
        assertEquals(EXPECTED_ITERATIONS, parts[1], "迭代次数必须是 10 万（降低迭代次数等于削弱抗暴力破解）");

        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] hash = Base64.getDecoder().decode(parts[3]);
        assertEquals(16, salt.length, "盐必须是 16 字节随机数（盐太短会让彩虹表重新变得可行）");
        assertEquals(32, hash.length, "PBKDF2WithHmacSHA256 输出必须是 256 位 = 32 字节");

        // 防回归：isPbkdf2 必须认得自家产出的格式（不认 → login() 里的平滑升级逻辑会误判）
        assertTrue(invokeIsPbkdf2(stored), "isPbkdf2 对自己的输出必须返回 true");

        // 防回归：t_user.password 是 varchar(100)，格式变长会直接插入失败
        assertTrue(stored.length() <= 100, "存储长度必须放得进 varchar(100)，实际=" + stored.length());
    }

    // ==================== 2. 加盐：同一明文两次哈希必须不同 ====================

    @Test
    @DisplayName("同一明文两次 hashPassword 结果不同（盐随机），但两次都能验证通过")
    void hashPassword_shouldUseRandomSalt() {
        String first = UserService.hashPassword("123456");
        String second = UserService.hashPassword("123456");

        // 防回归（核心）：如果哪天盐改成固定值/写死常量，两次结果会完全相同 ——
        // 那样"撞库一次破解全部相同密码"的加盐收益就归零了。
        assertNotEquals(first, second, "同一明文两次哈希必须不同，否则说明盐不再是随机的");

        String salt1 = first.split("\\$")[2];
        String salt2 = second.split("\\$")[2];
        assertNotEquals(salt1, salt2, "两次的盐必须不同");

        // 防回归：盐不同不能影响"各自都能验证通过"（否则用户每次登录都会随机失败）
        assertTrue(UserService.verifyPassword("123456", first), "第一次哈希必须能验证通过");
        assertTrue(UserService.verifyPassword("123456", second), "第二次哈希必须能验证通过");
    }

    // ==================== 3. 正确/错误密码 ====================

    @Test
    @DisplayName("verifyPassword：正确密码 true，错误密码 false")
    void verifyPassword_shouldAcceptCorrectAndRejectWrong() {
        String stored = UserService.hashPassword("correct-horse");

        assertTrue(UserService.verifyPassword("correct-horse", stored), "正确密码必须通过");
        // 防回归：密码比对不能退化成"只看长度 / 只看前缀 / 恒返回 true"
        assertFalse(UserService.verifyPassword("correct-hors", stored), "少一个字符必须失败");
        assertFalse(UserService.verifyPassword("correct-horse ", stored), "多一个空格必须失败");
        assertFalse(UserService.verifyPassword("Correct-Horse", stored), "大小写不同必须失败");
        assertFalse(UserService.verifyPassword("", stored), "空明文必须失败");
        assertFalse(UserService.verifyPassword(null, stored), "null 明文必须失败");
    }

    // ==================== 4. null / 空明文的健壮性 ====================

    @Test
    @DisplayName("verifyPassword 传 null/空明文不抛异常，一律返回 false")
    void verifyPassword_shouldNotThrowOnNullOrEmptyPlain() {
        String pbkdf2Stored = UserService.hashPassword("123456");

        // 防回归：这两个入口以前很容易 NPE —— 登录接口只要收到 {"password": null}
        // 就会变成 500（栈信息泄露 + 前端拿不到可读错误），必须稳稳返回 false。
        assertDoesNotThrow(() -> UserService.verifyPassword(null, pbkdf2Stored));
        assertFalse(UserService.verifyPassword(null, pbkdf2Stored), "null 明文 + PBKDF2 存储 → false");

        assertDoesNotThrow(() -> UserService.verifyPassword(null, MD5_OF_123456));
        assertFalse(UserService.verifyPassword(null, MD5_OF_123456), "null 明文 + MD5 存储 → false，且不得 NPE");

        assertDoesNotThrow(() -> UserService.verifyPassword("", MD5_OF_123456));
        assertFalse(UserService.verifyPassword("", MD5_OF_123456), "空明文 + MD5 存储 → false");

        // 存储值为 null / 空：同样必须是 false，而不是异常
        assertFalse(UserService.verifyPassword("123456", null), "stored 为 null → false");
        assertFalse(UserService.verifyPassword("123456", ""), "stored 为空串 → false");
    }

    @Test
    @DisplayName("verifyPassword 对畸形 pbkdf2$... 存储值不抛异常，返回 false")
    void verifyPassword_shouldNotThrowOnMalformedStored() {
        // 防回归：库里若存在被截断/手改坏的记录，登录接口必须给出"密码错误"，
        // 而不是 parseInt/Base64 异常冒到 500。
        assertFalse(UserService.verifyPassword("123456", "pbkdf2$abc$c2FsdA==$aGFzaA=="),
                "迭代次数不是数字 → false，不抛异常");
        assertFalse(UserService.verifyPassword("123456", "pbkdf2$100000$不是Base64$***"),
                "盐/哈希不是合法 Base64 → false，不抛异常");
        assertFalse(UserService.verifyPassword("123456", "pbkdf2$100000$c2FsdA=="),
                "段数不足 4 → false，不抛异常");
    }

    // ==================== 5. 历史 MD5 兼容路径 ====================

    @Test
    @DisplayName("历史 MD5 存储值：verifyPassword 必须通过，且 isPbkdf2 判定为 false（触发平滑升级）")
    void verifyPassword_shouldSupportLegacyMd5() {
        // 先确认这个常量确实是明文 123456 的 MD5（md5 是 public static，可直接调）
        assertEquals(MD5_OF_123456, UserService.md5("123456"), "测试用的 MD5 常量必须与生产 md5() 一致");

        // 防回归（老账号生死线）：库里存的还是 MD5 时，用户输入正确明文必须能登进来；
        // 如果这条挂了，等于所有未迁移的老账号全部被锁死。
        assertTrue(UserService.verifyPassword("123456", MD5_OF_123456),
                "MD5 存储 + 正确密码 必须返回 true（老账号平滑升级的前提）");
        assertFalse(UserService.verifyPassword("123457", MD5_OF_123456), "MD5 存储 + 错误密码 必须 false");

        // 防回归：isPbkdf2 必须把 MD5 认成 false —— login() 靠这个判定决定"要不要就地升级成 PBKDF2"。
        // 若误判为 true，老账号永远不会升级，MD5 会一直留在库里。
        assertFalse(invokeIsPbkdf2(MD5_OF_123456), "32 位 MD5 不能被认为是 PBKDF2");

        // 防回归：MD5 存储校验完后，升级路径存的必须是 PBKDF2，并且新哈希仍能验证同一明文
        String upgraded = UserService.hashPassword("123456");
        assertTrue(invokeIsPbkdf2(upgraded), "升级后写入的必须是 PBKDF2 格式");
        assertTrue(UserService.verifyPassword("123456", upgraded), "升级后用户仍能登录");
    }

    @Test
    @DisplayName("isPbkdf2 边界：null、空串、大小写、非 pbkdf2 前缀一律 false")
    void isPbkdf2_shouldBeStrict() {
        // 防回归：null 必须返回 false 而不是 NPE（login() 里 user.getPassword() 理论上可能为 null）
        assertFalse(invokeIsPbkdf2(null), "null → false，不得 NPE");
        assertFalse(invokeIsPbkdf2(""), "空串 → false");
        assertFalse(invokeIsPbkdf2("PBKDF2$100000$a$b"), "前缀大小写敏感，PBKDF2 不算（否则会走错校验分支）");
        assertFalse(invokeIsPbkdf2("pbkdf2"), "只有前缀没有 $ 分隔符 → false");
        assertTrue(invokeIsPbkdf2("pbkdf2$100000$salt$hash"),
                "isPbkdf2 只看前缀，不看内容是否真的合法（内容合法性由 verifyPassword 的 try/catch 兜底）");
        assertTrue(invokeIsPbkdf2(UserService.hashPassword("x")), "合法 pbkdf2$ 前缀 → true");
    }
}
