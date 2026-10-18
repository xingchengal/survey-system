package com.survey.service;

import com.survey.entity.LoginLog;
import com.survey.mapper.LoginLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link LoginLogService} 的纯单元测试（Mockito + MockHttpServletRequest，**不启动 Spring 容器、不连库**）。
 *
 * ── 为什么这几条值得钉住 ──────────────────────────────
 *   ① **写日志失败不能影响登录**：这是本类唯一的设计承诺（登录是主流程，日志是附录）。
 *      如果哪天有人把 recordSuccess 的 try-catch 去掉，这里会红。
 *   ② **列宽截断**：t_login_log.ip 是 varchar(50)、user_agent 是 varchar(500)。
 *      一个超长的 UA（客户端可以随便发）会让 insert 直接因"数据过长"失败 ——
 *      而那正是 ① 要吞掉的异常，于是**日志静默消失**。截断是保住这条日志的唯一办法。
 *   ③ **status 固定写 1**：表里的 status 列是给"登录失败锁定"预留的，
 *      当前实现只记成功；谁改了这个语义，就应该同时改本测试与建表语句的 COMMENT。
 *   ④ **查询上限写死在 Service**：不能让它变成一个能被请求参数放大的值（否则等于开放全表导出）。
 */
@ExtendWith(MockitoExtension.class)
class LoginLogServiceTest {

    @Mock
    private LoginLogMapper loginLogMapper;

    @InjectMocks
    private LoginLogService loginLogService;

    /** 造一个带指定 UA 与来源地址的请求（WebUtils.getClientIp 在没有 XFF/X-Real-IP 时用 remoteAddr）。 */
    private static MockHttpServletRequest requestWith(String userAgent, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userAgent != null) request.addHeader("User-Agent", userAgent);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    // ==================== 1. 正常写入的字段 ====================

    @Test
    @DisplayName("recordSuccess：账号/角色/IP/UA 都落进实体，status 固定为 1")
    void recordSuccess_shouldFillAllFields() {
        loginLogService.recordSuccess(7, "alice", "user", requestWith("Mozilla/5.0", "192.168.1.9"));

        ArgumentCaptor<LoginLog> captor = ArgumentCaptor.forClass(LoginLog.class);
        verify(loginLogMapper).insert(captor.capture());
        LoginLog saved = captor.getValue();

        assertEquals(7, saved.getUserId());
        assertEquals("alice", saved.getUsername());
        assertEquals("user", saved.getRole());
        assertEquals("192.168.1.9", saved.getIp());
        assertEquals("Mozilla/5.0", saved.getUserAgent());
        // ★ 防回归：当前实现只写成功（1）。失败记录是"登录失败锁定"那个功能的活，
        //   谁把它改成 0 或别的值，请连同建表语句的 COMMENT 一起改。
        assertEquals(1, saved.getStatus(), "当前实现只记成功登录，status 必须是 1");
    }

    @Test
    @DisplayName("recordSuccess：userId 为 null 时**不落库**（避免 user_id 非空列 insert 失败）")
    void recordSuccess_shouldSkipWhenUserIdIsNull() {
        loginLogService.recordSuccess(null, "alice", "user", requestWith("UA", "127.0.0.1"));
        verify(loginLogMapper, never()).insert(any());
    }

    // ==================== 2. 截断（列宽一致性）====================

    @Test
    @DisplayName("超长 User-Agent 按 500 截断（不截断 → insert 因数据过长失败 → 日志静默消失）")
    void recordSuccess_shouldTruncateLongUserAgent() {
        String longUa = "U".repeat(900);
        loginLogService.recordSuccess(1, "alice", "user", requestWith(longUa, "10.0.0.1"));

        ArgumentCaptor<LoginLog> captor = ArgumentCaptor.forClass(LoginLog.class);
        verify(loginLogMapper).insert(captor.capture());
        String ua = captor.getValue().getUserAgent();

        assertEquals(500, ua.length(), "t_login_log.user_agent 是 varchar(500)，必须截到列宽以内");
        assertEquals("U".repeat(500), ua, "截断必须保留前缀，而不是随便取一段");
    }

    @Test
    @DisplayName("超长 IP 也按 50 截断（与 t_operation_log.ip 同列宽；XFF 可被客户端伪造）")
    void recordSuccess_shouldTruncateLongIp() {
        // ★ 模拟"客户端伪造一个超长 X-Forwarded-For"：WebUtils.getClientIp 会取第一段并截到 50，
        //   这里再截一次是本类的最后一道防线（见类注释里"两个类的不变量"那段说明）。
        String forgedXff = "1".repeat(300);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", forgedXff);
        loginLogService.recordSuccess(1, "alice", "user", request);

        ArgumentCaptor<LoginLog> captor = ArgumentCaptor.forClass(LoginLog.class);
        verify(loginLogMapper).insert(captor.capture());
        assertTrue(captor.getValue().getIp().length() <= 50,
                "ip 必须落在 varchar(50) 内，实际=" + captor.getValue().getIp().length());
    }

    @Test
    @DisplayName("request 为 null / 无 UA：IP 与 UA 存 null 而不是空串（'没有值'只有一种表示）")
    void recordSuccess_shouldStoreNullForMissingIpAndUa() {
        loginLogService.recordSuccess(1, "alice", "user", null);

        ArgumentCaptor<LoginLog> captor = ArgumentCaptor.forClass(LoginLog.class);
        verify(loginLogMapper).insert(captor.capture());
        assertNull(captor.getValue().getIp(), "没有请求上下文时应存 null，而不是空串");
        assertNull(captor.getValue().getUserAgent());

        // 有请求但客户端没发 UA 头：同样存 null
        reset(loginLogMapper);
        loginLogService.recordSuccess(1, "alice", "user", requestWith(null, "10.0.0.2"));
        ArgumentCaptor<LoginLog> captor2 = ArgumentCaptor.forClass(LoginLog.class);
        verify(loginLogMapper).insert(captor2.capture());
        assertNull(captor2.getValue().getUserAgent());
        assertEquals("10.0.0.2", captor2.getValue().getIp());
    }

    // ==================== 3. 失败不得影响登录 ====================

    @Test
    @DisplayName("落库抛异常时 recordSuccess **不得**向上抛（登录是主流程，日志只是附录）")
    void recordSuccess_shouldSwallowMapperException() {
        when(loginLogMapper.insert(any())).thenThrow(new RuntimeException("Data too long for column 'user_agent'"));

        // ★ 关键断言：不抛。改造前若无这层 try-catch，一次超长 UA 就能让**用户登不进来**。
        assertDoesNotThrow(() ->
                loginLogService.recordSuccess(1, "alice", "user", requestWith("UA", "127.0.0.1")));
        verify(loginLogMapper).insert(any());
    }

    // ==================== 4. 查询：只能按 userId、上限写死 ====================

    @Test
    @DisplayName("findRecent：按 userId 查，返回 Mapper 的结果；limit 是 Service 里写死的小上限")
    void findRecent_shouldQueryByUserIdWithFixedLimit() {
        LoginLog one = new LoginLog();
        one.setUserId(7);
        when(loginLogMapper.findRecentByUser(eq(7), anyInt())).thenReturn(List.of(one));

        List<LoginLog> result = loginLogService.findRecent(7);

        assertEquals(1, result.size());
        assertEquals(7, result.get(0).getUserId());

        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(loginLogMapper).findRecentByUser(eq(7), limitCaptor.capture());
        int limit = limitCaptor.getValue();
        // ★ 防回归：这个数一旦变成"从请求参数取"，就等于开放了"把某个用户的全部登录记录导出来"。
        assertTrue(limit > 0 && limit <= 100,
                "最近登录的查询上限必须是 Service 内写死的小值，实际=" + limit);
    }

    // ==================== 5. 保留策略（C2） ====================

    /**
     * 反射设置私有 `@Value` 字段。
     * ★ 纯单测里没有 Spring 容器，`@Value("${log.retention-days:90}")` 不会被注入（字段恒为 0）——
     *   所以每条与保留策略相关的用例都必须先设值，否则测出来的只是"默认关闭"这一条分支。
     */
    private void setRetentionDays(int days) {
        try {
            var field = LoginLogService.class.getDeclaredField("retentionDays");
            field.setAccessible(true);
            field.setInt(loginLogService, days);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "反射设置 LoginLogService.retentionDays 失败 —— 字段名/类型可能变了，请同步本测试", e);
        }
    }

    @Test
    @DisplayName("保留策略：retentionDays > 0 时按天数清理过期登录记录")
    void purgeExpired_shouldDeleteByRetentionDays() {
        setRetentionDays(90);
        when(loginLogMapper.deleteOlderThan(90)).thenReturn(7);

        loginLogService.purgeExpired();

        verify(loginLogMapper).deleteOlderThan(90);
    }

    /**
     * ★ 配成 0 / 负数 = **关闭**自动清理（保留全部历史）。
     *   这是与 ErrorLogService 共用的语义（同一个 `log.retention-days` 键），
     *   如果有人把 `<= 0` 的判断删掉，运维设成 0 会变成"一启动就清空全表"。
     */
    @Test
    @DisplayName("保留策略：retentionDays <= 0 时关闭清理（一条都不删）")
    void purgeExpired_shouldBeDisabledWhenRetentionIsNonPositive() {
        setRetentionDays(0);

        loginLogService.purgeExpired();

        verifyNoInteractions(loginLogMapper);
    }

    /** 清理任务自己失败绝不能把异常抛给调度器（否则每分钟刷一次堆栈），也不能影响别的定时任务。 */
    @Test
    @DisplayName("保留策略：清理抛异常时吞掉，不向上抛")
    void purgeExpired_shouldSwallowExceptions() {
        setRetentionDays(30);
        when(loginLogMapper.deleteOlderThan(30)).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> loginLogService.purgeExpired());
        verify(loginLogMapper).deleteOlderThan(30);
    }
}
