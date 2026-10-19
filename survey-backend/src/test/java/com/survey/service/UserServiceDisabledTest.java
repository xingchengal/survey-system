package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.User;
import com.survey.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端「禁用 / 解禁」的保护规则（Mockito，不连库）。
 *
 * ★ 这个能力是 G3 新加的：`t_user.disabled`（见 sql/migration-user-disabled.sql）。
 *   它把账号**锁在门外**，所以每一条保护都必须用测试钉住 ——
 *   保护一旦失效，后果是"没人能登录后台"，只能改数据库才能恢复。
 *
 * 钉住的四件事：
 *   ① 不能禁用自己（否则管理员把自己锁死）；
 *   ② 不能禁用**最后一个可用管理员**（countActiveAdmins()，不是 countAdmins()）；
 *   ③ 已注销的账号要先恢复（禁用/解禁一个"已经登不进来"的账号没有意义）；
 *   ④ 禁用与解禁**都要**写 `token_valid_after` —— 这是"立刻生效"而不是"12 小时后生效"的关键。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceDisabledTest {

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserService userService;

    private static User user(int id, String username, String role, LocalDateTime deletedAt, Integer disabled) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRole(role);
        u.setDeletedAt(deletedAt);
        u.setDisabled(disabled);
        return u;
    }

    /** 禁用自己：直接在 guard 处拒绝，**不写库**（连更新都不该发生）。 */
    @Test
    @DisplayName("不能禁用自己的账号：拒绝且不写库")
    void cannotDisableSelf() {
        when(userMapper.findByIdIncludeDeleted(7)).thenReturn(user(7, "admin1", "admin", null, 0));

        BizException e = assertThrows(BizException.class, () -> userService.setDisabled(7, 1, 7));

        assertTrue(e.getMessage().contains("自己"), "文案要说清是「不能禁用自己」，实际=" + e.getMessage());
        verify(userMapper, never()).updateDisabled(anyInt(), anyInt());
        verify(userMapper, never()).updateTokenValidAfter(anyInt(), any());
    }

    /**
     * 超级管理员不能被禁用（比“最后一个可用管理员”更强：任何超管账号都不能被锁）。
     */
    @Test
    @DisplayName("超级管理员不能被禁用：拒绝且不写库")
    void cannotDisableSuperAdmin() {
        when(userMapper.findByIdIncludeDeleted(8)).thenReturn(user(8, "admin2", "admin", null, 0));

        BizException e = assertThrows(BizException.class, () -> userService.setDisabled(8, 1, 7));

        assertTrue(e.getMessage().contains("超级管理员"), "实际=" + e.getMessage());
        verify(userMapper, never()).updateDisabled(anyInt(), anyInt());
        verify(userMapper, never()).countActiveAdmins();
    }

    /** 已注销的账号：提示先恢复，而不是"用户不存在"（那个人就在「已注销」列表里）。 */
    @Test
    @DisplayName("已注销账号不能直接禁用：提示先恢复账号")
    void cannotDisableDeletedAccount() {
        when(userMapper.findByIdIncludeDeleted(9))
                .thenReturn(user(9, "gone", "user", LocalDateTime.now().minusDays(1), 0));

        BizException e = assertThrows(BizException.class, () -> userService.setDisabled(9, 1, 7));

        assertTrue(e.getMessage().contains("注销"), "实际=" + e.getMessage());
        verify(userMapper, never()).updateDisabled(anyInt(), anyInt());
    }

    /** disabled 只接受 0/1：其它值（包括 null）是调用方的错，必须在写库前拒绝。 */
    @Test
    @DisplayName("disabled 只接受 0/1：传 2 或 null 都拒绝，且不查库")
    void rejectsIllegalDisabledValue() {
        assertThrows(BizException.class, () -> userService.setDisabled(9, 2, 7));
        assertThrows(BizException.class, () -> userService.setDisabled(9, null, 7));
        // 非法值在校验阶段就被挡住，不应该发生任何查询
        verify(userMapper, never()).findByIdIncludeDeleted(anyInt());
    }

    /**
     * 正常禁用：写 disabled=1，**并**把 token_valid_after 推到此刻。
     * ★ 第二条断言是"立刻生效"的保证：JWT 无法吊销，不写这一列的话，
     *   被禁用的人手里那枚票还能继续用最长 12 小时。
     */
    @Test
    @DisplayName("正常禁用：写 disabled=1 且作废此前签发的 token")
    void disableWritesFlagAndTokenCutoff() {
        when(userMapper.findByIdIncludeDeleted(8)).thenReturn(user(8, "user8", "user", null, 0));
        when(userMapper.updateDisabled(8, 1)).thenReturn(1);
        when(userMapper.updateTokenValidAfter(eq(8), any())).thenReturn(1);

        assertEquals("已禁用", userService.setDisabled(8, 1, 7));

        verify(userMapper).updateDisabled(8, 1);
        verify(userMapper).updateTokenValidAfter(eq(8), any());
        // 普通用户禁用不涉及"最后一个管理员"的判断
        verify(userMapper, never()).countActiveAdmins();
    }

    /**
     * 解禁普通用户：不做"管理员数量"检查（解禁只会让系统更可用），但**同样**作废旧票。
     * ★ 为什么解禁也要作废：禁用期间账号处于"被处置"状态，
     *   之前泄露/遗留的票不该在解禁后自动复活。
     */
    @Test
    @DisplayName("解禁普通用户：不检查管理员数量，但仍作废旧 token")
    void enableSkipsAdminCountButStillInvalidatesTokens() {
        when(userMapper.findByIdIncludeDeleted(8)).thenReturn(user(8, "user8", "user", null, 1));
        when(userMapper.updateDisabled(8, 0)).thenReturn(1);
        when(userMapper.updateTokenValidAfter(eq(8), any())).thenReturn(1);

        assertEquals("已解禁", userService.setDisabled(8, 0, 7));

        verify(userMapper).updateDisabled(8, 0);
        verify(userMapper).updateTokenValidAfter(eq(8), any());
        verify(userMapper, never()).countActiveAdmins();
    }

    @Test
    @DisplayName("★ 次级管理员只能由超管操作：普通管理员（含 self）不能禁用/解禁他")
    void subAdminCanOnlyBeHandledBySuperAdmin() {
        when(userMapper.findByIdIncludeDeleted(8)).thenReturn(user(8, "sub1", "subadmin", null, 0));
        // 操作人 7 是超级管理员 → 放行
        when(userMapper.findById(7)).thenReturn(user(7, "admin1", "admin", null, 0));
        when(userMapper.updateDisabled(8, 1)).thenReturn(1);
        when(userMapper.updateTokenValidAfter(eq(8), any())).thenReturn(1);
        assertEquals("已禁用", userService.setDisabled(8, 1, 7));

        // 操作人 9 是普通用户（实际接口不会让他进来，但 Service 也要自卫）→ 拒绝
        reset(userMapper);
        when(userMapper.findByIdIncludeDeleted(8)).thenReturn(user(8, "sub1", "subadmin", null, 0));
        when(userMapper.findById(9)).thenReturn(user(9, "user9", "user", null, 0));
        BizException e = assertThrows(BizException.class, () -> userService.setDisabled(8, 1, 9));
        assertTrue(e.getMessage().contains("只有超级管理员"), "实际=" + e.getMessage());
        verify(userMapper, never()).updateDisabled(8, 1);
    }
}
