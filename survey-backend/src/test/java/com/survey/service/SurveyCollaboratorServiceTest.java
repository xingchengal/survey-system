package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.Survey;
import com.survey.entity.User;
import com.survey.mapper.SurveyCollaboratorMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 协作者管理（邀请 / 改角色 / 移除）的业务校验。
 *
 * ── 这里钉的是"能不能写"，不是"能不能看" ─────────────────
 *   "谁能管理协作者"（作者/管理员）在 Controller 用 OwnershipGuard 判；
 *   本类只管**业务前置条件**：角色合法、被邀请人存在、不能邀请自己/作者、不能重复。
 *   ★ 这些规则如果漏一条，后果都是"脏数据"或"权限扩散"，而不是编译错误 —— 必须测。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SurveyCollaboratorServiceTest {

    @Mock
    private SurveyCollaboratorMapper collaboratorMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private SurveyCollaboratorService service;

    private static final Integer SURVEY_ID = 874;
    private static final Integer OWNER = 466;
    private static final Integer OPERATOR = 466;
    private static final Integer TARGET = 500;

    private static Survey survey() {
        Survey s = new Survey();
        s.setId(SURVEY_ID);
        s.setUserId(OWNER);
        return s;
    }

    private static User user(Integer id) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        return u;
    }

    private static final String TARGET_NAME = "u500";

    @Test
    @DisplayName("邀请成功：写入 survey_id / user_id / role / created_by")
    void inviteWritesRow() {
        when(userMapper.findByUsername(TARGET_NAME)).thenReturn(user(TARGET));
        when(collaboratorMapper.findCollaboratorRole(SURVEY_ID, TARGET)).thenReturn(null);

        service.invite(survey(), TARGET_NAME, "editor", OPERATOR);

        verify(collaboratorMapper).insert(SURVEY_ID, TARGET, "editor", OPERATOR);
        // ★ 邀请必须通知到本人（否则对方根本不知道自己被拉进来了）
        verify(notificationService).send(eq(TARGET), eq("协作邀请"), anyString(), eq("collaborator"), eq("/my/surveys"));
    }

    @Test
    @DisplayName("★ 非法角色（大小写不符/未知/空）→ 400，且不写库")
    void invalidRoleIsRejected() {
        for (String bad : new String[]{"Editor", "owner", "", "  ", "admin"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> service.invite(survey(), TARGET_NAME, bad, OPERATOR), "应拒绝角色：" + bad);
            assertTrue(ex.getMessage().contains("viewer / editor / analyst"), "实际：" + ex.getMessage());
        }
        verify(collaboratorMapper, never()).insert(anyInt(), anyInt(), anyString(), anyInt());
    }

    @Test
    @DisplayName("★ 不能邀请自己 / 不能邀请作者本人")
    void cannotInviteSelfOrOwner() {
        when(userMapper.findByUsername("operator")).thenReturn(user(OPERATOR));
        assertEquals("不能把自己加为协作者", assertThrows(BizException.class,
                () -> service.invite(survey(), "operator", "editor", OPERATOR)).getMessage());

        when(userMapper.findByUsername("owner")).thenReturn(user(OWNER));
        assertEquals("作者本人拥有全部权限，不需要被邀请为协作者", assertThrows(BizException.class,
                () -> service.invite(survey(), "owner", "editor", 999)).getMessage());

        verify(collaboratorMapper, never()).insert(anyInt(), anyInt(), anyString(), anyInt());
    }

    @Test
    @DisplayName("★ 被邀请人不存在/已注销 → 400（findById 已过滤 deleted_at）")
    void targetMustExist() {
        when(userMapper.findByUsername(TARGET_NAME)).thenReturn(null);
        assertEquals("被邀请的用户不存在或已注销", assertThrows(BizException.class,
                () -> service.invite(survey(), TARGET_NAME, "viewer", OPERATOR)).getMessage());
        verify(collaboratorMapper, never()).insert(anyInt(), anyInt(), anyString(), anyInt());
    }

    @Test
    @DisplayName("★ 重复邀请 → 400（提示改用改角色），不覆盖原角色")
    void duplicateInviteIsRejected() {
        when(userMapper.findByUsername(TARGET_NAME)).thenReturn(user(TARGET));
        when(collaboratorMapper.findCollaboratorRole(SURVEY_ID, TARGET)).thenReturn("viewer");

        BizException ex = assertThrows(BizException.class,
                () -> service.invite(survey(), TARGET_NAME, "editor", OPERATOR));
        assertTrue(ex.getMessage().contains("已经是协作者"), "实际：" + ex.getMessage());
        verify(collaboratorMapper, never()).insert(anyInt(), anyInt(), anyString(), anyInt());
    }

    @Test
    @DisplayName("改角色：必须是已存在的协作者；否则 400 且不更新")
    void changeRoleRequiresExistingCollaborator() {
        when(collaboratorMapper.findCollaboratorRole(SURVEY_ID, TARGET)).thenReturn(null);
        assertEquals("该用户不是这份问卷的协作者", assertThrows(BizException.class,
                () -> service.changeRole(survey(), TARGET, "analyst")).getMessage());
        verify(collaboratorMapper, never()).updateRole(anyInt(), anyInt(), anyString());

        when(collaboratorMapper.findCollaboratorRole(SURVEY_ID, TARGET)).thenReturn("viewer");
        service.changeRole(survey(), TARGET, "analyst");
        verify(collaboratorMapper).updateRole(SURVEY_ID, TARGET, "analyst");
        verify(notificationService).send(eq(TARGET), eq("协作角色变更"), anyString(), eq("collaborator"), eq("/my/surveys"));
    }

    @Test
    @DisplayName("移除：必须是已存在的协作者；否则 400 且不删除")
    void removeRequiresExistingCollaborator() {
        when(collaboratorMapper.findCollaboratorRole(SURVEY_ID, TARGET)).thenReturn(null);
        assertEquals("该用户不是这份问卷的协作者", assertThrows(BizException.class,
                () -> service.remove(survey(), TARGET)).getMessage());
        verify(collaboratorMapper, never()).delete(anyInt(), anyInt());

        when(collaboratorMapper.findCollaboratorRole(SURVEY_ID, TARGET)).thenReturn("editor");
        service.remove(survey(), TARGET);
        verify(collaboratorMapper).delete(SURVEY_ID, TARGET);
    }

    @Test
    @DisplayName("★ 自主退出：不是协作者 → 400；是协作者 → 删除（不需要对问卷有所有权）")
    void leaveRemovesSelf() {
        when(collaboratorMapper.findCollaboratorRole(SURVEY_ID, TARGET)).thenReturn(null);
        assertEquals("你不是这份问卷的协作者", assertThrows(BizException.class,
                () -> service.leave(survey(), TARGET)).getMessage());
        verify(collaboratorMapper, never()).delete(anyInt(), anyInt());

        when(collaboratorMapper.findCollaboratorRole(SURVEY_ID, TARGET)).thenReturn("viewer");
        when(userMapper.findById(TARGET)).thenReturn(user(TARGET));
        service.leave(survey(), TARGET);
        verify(collaboratorMapper).delete(SURVEY_ID, TARGET);
        // ★ 退出也要通知作者（否则作者只看到名单少了一人）
        verify(notificationService).send(eq(OWNER), eq("协作者退出"), anyString(), eq("collaborator"), eq("/my/surveys"));
    }

    @Test
    @DisplayName("列表直接透传 Mapper（含昵称的 JOIN 在 SQL 里）")
    void listDelegatesToMapper() {
        service.list(survey());
        verify(collaboratorMapper).listBySurveyId(SURVEY_ID);
    }

    @Test
    @DisplayName("共享列表：保留作者/我的角色，但必须清空答题密码")
    void sharedListKeepsOwnerFieldsButHidesPassword() {
        Survey shared = survey();
        shared.setTitle("共享问卷");
        shared.setAccessPassword("123456");
        shared.setOwnerUsername("owner_acc");
        shared.setOwnerNickname("作者昵称");
        shared.setMyCollaboratorRole("editor");
        shared.setSharedTime(java.time.LocalDateTime.of(2026, 10, 19, 10, 0));
        when(collaboratorMapper.findSharedSurveys(TARGET)).thenReturn(List.of(shared));

        var list = service.listSharedWithMe(TARGET);

        assertEquals(1, list.size());
        Survey item = list.get(0);
        // 作者/角色是前端分组与筛选的依据，不能被脱敏误伤
        assertEquals("owner_acc", item.getOwnerUsername());
        assertEquals("作者昵称", item.getOwnerNickname());
        assertEquals("editor", item.getMyCollaboratorRole());
        assertEquals(java.time.LocalDateTime.of(2026, 10, 19, 10, 0), item.getSharedTime());
        // 密码只用于填写人进门，协作者不该看到；hasPassword 保留给前端提示
        assertNull(item.getAccessPassword());
        assertTrue(item.getHasPassword());
    }
}
