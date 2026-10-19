package com.survey.service;

import com.survey.common.BizException;
import com.survey.common.Role;
import com.survey.entity.User;
import com.survey.mapper.NotificationMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 超级管理员保护（需求 4）—— “不能删超级管理员”。
 *
 * ★ 覆盖四个入口里的三个：
 *   · 个人中心注销 → deleteAccount；
 *   · 用户管理删除 → deleteUser；
 *   · 禁用/解禁 → UserServiceDisabledTest（已有）。
 *   还有一个“降级超管”在 AdminManagementServiceTest 里。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceAdminProtectionTest {

    private static final Integer SUPER = 1;
    private static final Integer SUB = 8;

    @Mock
    private UserMapper userMapper;
    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private SurveyService surveyService;

    @InjectMocks
    private UserService userService;

    private static User user(Integer id, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setRole(role);
        u.setPassword("x");
        u.setDisabled(0);
        return u;
    }

    @Test
    @DisplayName("★ 超级管理员不能自我注销：deleteAccount 直接 400")
    void superAdminCannotDeleteOwnAccount() {
        when(userMapper.findById(SUPER)).thenReturn(user(SUPER, Role.ADMIN));

        BizException e = assertThrows(BizException.class,
                () -> userService.deleteAccount(SUPER, "whatever"));

        assertTrue(e.getMessage().contains("超级管理员"), "实际=" + e.getMessage());
        verify(userMapper, never()).softDeleteById(anyInt());
    }

    @Test
    @DisplayName("★ 超级管理员不能被任何人删除：deleteUser 直接 400，且不级联")
    void superAdminCannotBeDeleted() {
        when(userMapper.findByIdIncludeDeleted(SUPER)).thenReturn(user(SUPER, Role.ADMIN));

        BizException e = assertThrows(BizException.class,
                () -> userService.deleteUser(SUPER, 9));

        assertTrue(e.getMessage().contains("超级管理员"), "实际=" + e.getMessage());
        verify(userMapper, never()).deleteById(anyInt());
        verify(surveyService, never()).cascadeDeleteSurvey(anyInt());
    }

    @Test
    @DisplayName("★ 次级管理员不能被普通管理员删除：只有超管可以")
    void subAdminCanOnlyBeDeletedBySuper() {
        when(userMapper.findByIdIncludeDeleted(SUB)).thenReturn(user(SUB, Role.SUB_ADMIN));
        when(userMapper.findById(9)).thenReturn(user(9, Role.USER));

        BizException e = assertThrows(BizException.class,
                () -> userService.deleteUser(SUB, 9));

        assertTrue(e.getMessage().contains("只有超级管理员"), "实际=" + e.getMessage());
        verify(userMapper, never()).deleteById(anyInt());
    }

    @Test
    @DisplayName("超管删除次级管理员：允许，并走既有级联")
    void superAdminCanDeleteSubAdmin() {
        when(userMapper.findByIdIncludeDeleted(SUB)).thenReturn(user(SUB, Role.SUB_ADMIN));
        when(userMapper.findById(SUPER)).thenReturn(user(SUPER, Role.ADMIN));
        when(surveyMapper.findByUserIdIncludeDeleted(SUB)).thenReturn(List.of());
        when(userMapper.deleteById(SUB)).thenReturn(1);

        assertEquals(1, userService.deleteUser(SUB, SUPER));
        verify(notificationMapper).deleteAllByUser(SUB);
        verify(userMapper).deleteById(SUB);
    }
}
