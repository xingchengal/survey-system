package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.User;
import com.survey.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserService} 的**资料维护类实例方法**（Mockito，不连库）。
 *
 * ★ 为什么单独开一个类：原来的 `UserServiceTest` 只测 `hashPassword` / `verifyPassword` / `md5`
 *   这几个**静态方法**（纯函数、好测），而这个类里的 9 个实例方法**一个都没有测试** ——
 *   它们是"用户改资料"的全部入口，也是 jacoco 报告里 UserService 只有 39.5% 行的主因。
 *
 * 这里钉的是**四类最容易写错的东西**：
 *   ① 归一化：昵称必须 trim；联系方式传 `''` 或 null 都要落成 **null**（库里"没有值"只有一种表示）；
 *   ② 上限：昵称 50 / 新密码 6~64 —— 与注册侧的 `@Size` 对齐，写大了就是"校验通过、库里存不下"；
 *   ③ 幽灵用户：**先判用户存在**再写库，否则"账号已删但 token 没过期"会拿到假成功；
 *   ④ 改密码/注销要**同时作废两类凭证**（access token 的时间点 + refresh token 全部吊销）——
 *      只做一半时"改完密码对方还能继续用 12 小时"。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceProfileTest {

    private static final Integer UID = 42;

    @Mock
    private UserMapper userMapper;
    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private UserService userService;

    /** 造一个带指定明文密码哈希的用户 */
    private static User userWithPassword(String plain) {
        User u = new User();
        u.setId(UID);
        u.setUsername("alice");
        u.setPassword(UserService.hashPassword(plain));
        u.setNickname("Alice");
        return u;
    }

    // ==================== 改昵称 ====================

    @Test
    @DisplayName("改昵称：空白 → 400，且不查库不写库")
    void updateNickname_shouldRejectBlank() {
        assertThrows(BizException.class, () -> userService.updateNickname(UID, null));
        assertThrows(BizException.class, () -> userService.updateNickname(UID, "   "));
        verify(userMapper, never()).findById(anyInt());
        verify(userMapper, never()).updateNickname(anyInt(), anyString());
    }

    @Test
    @DisplayName("改昵称：超过 50 字符 → 400（上限必须与注册侧 @Size(max=50) 对齐）")
    void updateNickname_shouldRejectTooLong() {
        String tooLong = "昵".repeat(51);
        BizException e = assertThrows(BizException.class, () -> userService.updateNickname(UID, tooLong));
        assertTrue(e.getMessage().contains("50"), "实际=" + e.getMessage());
        verify(userMapper, never()).updateNickname(anyInt(), anyString());
    }

    @Test
    @DisplayName("改昵称：用户不存在 → 400（不能返回假成功）")
    void updateNickname_shouldRejectGhostUser() {
        when(userMapper.findById(UID)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> userService.updateNickname(UID, "新昵称"));
        assertTrue(e.getMessage().contains("不存在"), "实际=" + e.getMessage());
        verify(userMapper, never()).updateNickname(anyInt(), anyString());
    }

    @Test
    @DisplayName("改昵称：正常 → 落库的是 trim 后的值")
    void updateNickname_shouldTrimBeforeSave() {
        when(userMapper.findById(UID)).thenReturn(userWithPassword("x"));
        assertEquals("修改成功", userService.updateNickname(UID, "  新昵称  "));
        verify(userMapper).updateNickname(UID, "新昵称");
    }

    // ==================== 改密码 ====================

    @Test
    @DisplayName("改密码：原密码空 / 新密码过短 / 过长 / 原密码错 → 各自 400，且都不写库")
    void updatePassword_shouldRejectInvalidInputs() {
        assertThrows(BizException.class, () -> userService.updatePassword(UID, "", "123456"));
        assertThrows(BizException.class, () -> userService.updatePassword(UID, "old", "12345"));
        assertThrows(BizException.class, () -> userService.updatePassword(UID, "old", "1".repeat(65)));

        when(userMapper.findById(UID)).thenReturn(userWithPassword("correct"));
        BizException e = assertThrows(BizException.class,
                () -> userService.updatePassword(UID, "wrong", "123456"));
        assertTrue(e.getMessage().contains("原密码"), "实际=" + e.getMessage());

        verify(userMapper, never()).updatePassword(anyInt(), anyString());
        verify(refreshTokenService, never()).revokeAllByUser(anyInt());
    }

    /**
     * ★ 本类最重要的一条：改密码必须**同时**做两件事。
     *   只写 token_valid_after 而漏了 revokeAllByUser → refresh token 还能换出一枚新的 access token，
     *   等于"改密码"没把对方踢掉；反过来只吊销 refresh token 则对方手里那枚 access token 还能用 12 小时。
     */
    @Test
    @DisplayName("改密码：正常 → 写新哈希 + 推 token_valid_after + 吊销全部 refresh token")
    void updatePassword_shouldRotateAndInvalidateAllCredentials() {
        when(userMapper.findById(UID)).thenReturn(userWithPassword("old-pass"));
        when(userMapper.updatePassword(eq(UID), anyString())).thenReturn(1);
        when(userMapper.updateTokenValidAfter(eq(UID), any())).thenReturn(1);

        assertEquals("修改成功", userService.updatePassword(UID, "old-pass", "new-pass-123"));

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(userMapper).updatePassword(eq(UID), hash.capture());
        // 落库的必须是哈希，不能是明文
        assertNotEquals("new-pass-123", hash.getValue());
        assertTrue(UserService.verifyPassword("new-pass-123", hash.getValue()), "新哈希必须能验证通过");

        verify(userMapper).updateTokenValidAfter(eq(UID), any());
        verify(refreshTokenService).revokeAllByUser(UID);
    }

    /** ★ 令牌生效起点必须**截到秒**：带纳秒会让"改完密码同一秒内登录"的新票被判死。 */
    @Test
    @DisplayName("改密码：token_valid_after 必须截到秒（否则同秒内新登录会被自己判死）")
    void updatePassword_tokenCutoffMustBeSecondPrecision() {
        when(userMapper.findById(UID)).thenReturn(userWithPassword("old-pass"));
        when(userMapper.updatePassword(eq(UID), anyString())).thenReturn(1);
        when(userMapper.updateTokenValidAfter(eq(UID), any())).thenReturn(1);

        userService.updatePassword(UID, "old-pass", "new-pass-123");

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userMapper).updateTokenValidAfter(eq(UID), cutoff.capture());
        assertEquals(0, cutoff.getValue().getNano(), "必须是整秒，实际=" + cutoff.getValue());
    }

    // ==================== 头像 / 联系方式 ====================

    @Test
    @DisplayName("改头像：用户不存在 → 400；非法图片 → IllegalArgumentException 翻成 BizException（不是 500）")
    void updateAvatar_shouldMapValidationToBizException() {
        when(userMapper.findById(UID)).thenReturn(null);
        assertThrows(BizException.class, () -> userService.updateAvatar(UID, "data:image/png;base64,AAA"));

        when(userMapper.findById(UID)).thenReturn(userWithPassword("x"));
        BizException e = assertThrows(BizException.class, () -> userService.updateAvatar(UID, "不是 data URL"));
        assertNotNull(e.getMessage());
        verify(userMapper, never()).updateAvatar(anyInt(), anyString());
    }

    @Test
    @DisplayName("改联系方式：空白串必须归一成 null（库里\"没有值\"只能有一种表示）")
    void updateContact_shouldNormalizeBlankToNull() {
        when(userMapper.findById(UID)).thenReturn(userWithPassword("x"));

        userService.updateContact(UID, "   ", "");

        verify(userMapper).updateContact(UID, null, null);
    }

    @Test
    @DisplayName("改联系方式：用户不存在 → 400，不写库")
    void updateContact_shouldRejectGhostUser() {
        when(userMapper.findById(UID)).thenReturn(null);
        assertThrows(BizException.class, () -> userService.updateContact(UID, "13800000000", null));
        verify(userMapper, never()).updateContact(anyInt(), any(), any());
    }

    // ==================== 注销 / 恢复 ====================

    @Test
    @DisplayName("注销账号：无密码 / 密码错 / 用户不存在 → 400，且不写库")
    void deleteAccount_shouldRejectInvalid() {
        assertThrows(BizException.class, () -> userService.deleteAccount(UID, ""));
        assertThrows(BizException.class, () -> userService.deleteAccount(UID, null));

        when(userMapper.findById(UID)).thenReturn(null);
        assertThrows(BizException.class, () -> userService.deleteAccount(UID, "pwd"));

        when(userMapper.findById(UID)).thenReturn(userWithPassword("correct"));
        assertThrows(BizException.class, () -> userService.deleteAccount(UID, "wrong"));

        verify(userMapper, never()).softDeleteById(anyInt());
    }

    @Test
    @DisplayName("注销账号：正常 → 软删除 + 吊销全部 refresh token（两件事都要做）")
    void deleteAccount_shouldSoftDeleteAndRevokeTokens() {
        when(userMapper.findById(UID)).thenReturn(userWithPassword("correct"));
        when(userMapper.softDeleteById(UID)).thenReturn(1);

        assertEquals("注销成功", userService.deleteAccount(UID, "correct"));

        verify(userMapper).softDeleteById(UID);
        verify(refreshTokenService).revokeAllByUser(UID);
    }

    @Test
    @DisplayName("恢复账号：未注销 → 400；正常 → 清 deleted_at 且推 token_valid_after（旧票不复活）")
    void restoreUser_shouldPushTokenCutoff() {
        User active = userWithPassword("x");
        active.setDeletedAt(null);
        when(userMapper.findByIdIncludeDeleted(UID)).thenReturn(active);
        BizException e = assertThrows(BizException.class, () -> userService.restoreUser(UID));
        assertTrue(e.getMessage().contains("并未注销"), "实际=" + e.getMessage());

        User deleted = userWithPassword("x");
        deleted.setDeletedAt(LocalDateTime.now().minusDays(1));
        when(userMapper.findByIdIncludeDeleted(UID)).thenReturn(deleted);
        when(userMapper.restoreById(UID)).thenReturn(1);
        when(userMapper.updateTokenValidAfter(eq(UID), any())).thenReturn(1);

        assertEquals("恢复成功", userService.restoreUser(UID));
        verify(userMapper).restoreById(UID);
        // ★ 这一行是"恢复后必须重新登录"的唯一保证，删了旧 token 就会随 deleted_at 清空而复活
        verify(userMapper).updateTokenValidAfter(eq(UID), any());
    }

    // ==================== 导出（G3 的筛选参数归一 + 上限） ====================

    /** 导出会把全部行读进内存，所以超上限必须**明确报错**，不能静默截断成 10000 行。 */
    @Test
    @DisplayName("导出用户：超过 10000 行 → 400，不静默截断")
    void exportUsers_shouldRejectOverLimit() {
        List<User> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= UserService.EXPORT_MAX_ROWS; i++) tooMany.add(new User());
        when(userMapper.findActiveUsersForExport(null, null, null)).thenReturn(tooMany);

        BizException e = assertThrows(BizException.class,
                () -> userService.exportUsers(null, null, false, null));
        assertTrue(e.getMessage().contains("10000"), "实际=" + e.getMessage());
    }

    /**
     * ★ 参数归一的两条边界（G3）：role 只认 admin/user、disabled 只认 0/1，非法值一律当"不过滤"。
     *   写成"非法值抛 400"会让前端拼错一个参数就整页打不开（它是筛选条件，不是业务入参）。
     */
    @Test
    @DisplayName("导出用户：role 只认 admin/user、disabled 只认 0/1，非法值归一为 null")
    void exportUsers_shouldNormalizeFilters() {
        when(userMapper.findActiveUsersForExport(null, null, null)).thenReturn(List.of());

        userService.exportUsers(null, "root", false, 9);

        verify(userMapper).findActiveUsersForExport(null, null, null);
    }

    @Test
    @DisplayName("导出用户：合法筛选原样透传（role=admin / disabled=1），已注销视图走另一条 SQL")
    void exportUsers_shouldPassValidFiltersThrough() {
        when(userMapper.findActiveUsersForExport("a\\%b", "admin", 1)).thenReturn(List.of());

        userService.exportUsers("a%b", "admin", false, 1);

        // keyword 必须被 LIKE 转义（否则用户输入一个 % 就等于匹配所有行）
        verify(userMapper).findActiveUsersForExport("a\\%b", "admin", 1);
        verify(userMapper, never()).findDeletedUsersForExport(any(), any());
    }
}
