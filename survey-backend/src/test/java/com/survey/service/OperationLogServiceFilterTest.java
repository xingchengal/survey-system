package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.OperationLog;
import com.survey.mapper.OperationLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * 操作日志的筛选参数口径（Mockito，不连库）。
 *
 * ★ G3 给「系统监控」页新增了三个筛选维度：操作人角色（级别）、时间范围、导出。
 *   这里钉住"页面上的选择有没有原样变成 SQL 参数"，重点三个容易静默失效的点：
 *   ① 两个模糊搜索参数都要转义 LIKE 通配符（`%` / `_`）；
 *   ② role 只认 admin / user，非法值必须归一为 null（= 不加这个条件）；
 *   ③ 日期经 DateRanges 变成「当天 00:00:00 ~ 23:59:59」的闭区间。
 *
 * ★ 为什么测 exportLogs 而不是 page：page 会触发 PageHelper 的 ThreadLocal，
 *   纯单测里没有 SqlSession 配合，容易把"分页器行为"混进本测试；
 *   exportLogs 是纯 mapper 调用，参数透传一目了然。两者的筛选参数由同一段代码归一。
 */
@ExtendWith(MockitoExtension.class)
class OperationLogServiceFilterTest {

    @Mock
    private OperationLogMapper operationLogMapper;

    @InjectMocks
    private OperationLogService operationLogService;

    @Test
    @DisplayName("筛选参数原样落到 SQL：两个模糊参数转义、role 保留、日期转成当天闭区间")
    void exportLogsPassesNormalizedFilters() {
        when(operationLogMapper.findForExport(any(), any(), any(), any(), any())).thenReturn(List.of());

        operationLogService.exportLogs("ad%min", "删_除", "admin", "2026-10-01", "2026-10-02");

        // % 与 _ 都必须被转义，否则用户输入一个 % 就等于"匹配所有行"
        verify(operationLogMapper).findForExport(
                eq("ad\\%min"),
                eq("删\\_除"),
                eq("admin"),
                eq(LocalDateTime.of(2026, 10, 1, 0, 0, 0)),
                eq(LocalDateTime.of(2026, 10, 2, 23, 59, 59)));
    }

    @Test
    @DisplayName("role 只认 admin / user：其它值归一为 null（= 不加这个筛选条件）")
    void illegalRoleBecomesNull() {
        when(operationLogMapper.findForExport(any(), any(), any(), any(), any())).thenReturn(List.of());

        operationLogService.exportLogs(null, null, "root", null, null);

        verify(operationLogMapper).findForExport(isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("日期为空 → 两端都是 null（不限制），不能被当成某个默认日期")
    void blankDatesMeanNoLimit() {
        when(operationLogMapper.findForExport(any(), any(), any(), any(), any())).thenReturn(List.of());

        operationLogService.exportLogs(null, null, "user", "", "   ");

        verify(operationLogMapper).findForExport(isNull(), isNull(), eq("user"), isNull(), isNull());
    }

    /** 超过导出上限必须**明确报错**，不能静默截断成 10000 行（那会让管理员以为导出全了）。 */
    @Test
    @DisplayName("导出超过 10000 行 → BizException，不静默截断")
    void exportOverLimitIsRejected() {
        List<OperationLog> big = new ArrayList<>(OperationLogService.EXPORT_MAX_ROWS + 1);
        for (int i = 0; i <= OperationLogService.EXPORT_MAX_ROWS; i++) {
            big.add(new OperationLog());
        }
        when(operationLogMapper.findForExport(any(), any(), any(), any(), any())).thenReturn(big);

        BizException e = assertThrows(BizException.class,
                () -> operationLogService.exportLogs(null, null, null, null, null));

        assertTrue(e.getMessage().contains("10000"), "文案要说明上限，实际=" + e.getMessage());
        assertEquals(10001, big.size(), "上限判定的口径：mapper 固定取 10001 条，多的一条只为判断是否超限");
    }
}
