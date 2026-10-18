package com.survey.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端「时间范围」筛选的解析口径（纯单测）。
 *
 * ★ 这是 G3 新加的筛选能力，问卷列表 / 用户导出 / 操作日志三处共用同一套解析。
 *   三条最容易写错、也最容易造成"筛选看起来生效其实漏数据"的地方，全部钉死：
 *   ① 结束日期必须取 **23:59:59** —— 若取 00:00:00，当天的记录会全部漏掉；
 *   ② 空值必须是 **null（= 不限制）**，不能被当成"今天"或"某个默认日期"；
 *   ③ 非法格式必须抛 **BizException（→ 400）**，不能是 DateTimeParseException（→ 500）。
 */
class DateRangesTest {

    @Test
    @DisplayName("开始日期取当天 00:00:00")
    void startOfDayIsMidnight() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0, 0), DateRanges.startOfDay("2026-10-01"));
    }

    /**
     * ★ 核心断言：结束日期取当天 **23:59:59**。
     *   如果哪天有人把它改成 atStartOfDay()，"筛选 10-01 到 10-01"会一条数据都查不到
     *   （而界面上看是"筛选成功了，只是没有数据"—— 这种错最难被发现）。
     */
    @Test
    @DisplayName("结束日期取当天 23:59:59（取 00:00 会把当天数据全部漏掉）")
    void endOfDayIsLastSecond() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 23, 59, 59), DateRanges.endOfDay("2026-10-01"));
    }

    @Test
    @DisplayName("null / 空白 = 不限制（返回 null，SQL 侧跳过该条件）")
    void blankMeansNoLimit() {
        assertNull(DateRanges.startOfDay(null));
        assertNull(DateRanges.endOfDay(null));
        assertNull(DateRanges.startOfDay(""));
        assertNull(DateRanges.endOfDay("   "));
    }

    @Test
    @DisplayName("首尾空白能被容忍（前端/手输偶尔带空格）")
    void trimsWhitespace() {
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0, 0), DateRanges.startOfDay(" 2026-10-01 "));
    }

    /**
     * ★ 带时间的值**有意**不支持：本项目的时间筛选只有"日期"精度。
     *   允许 `2026-10-01 12:00:00` 会让"时分秒"被静默忽略（或产生歧义），
     *   所以这里明确拒绝并给出可读文案，而不是接受后行为不可预期。
     */
    @Test
    @DisplayName("非法格式（含带时间的值）→ BizException，文案写明正确格式")
    void illegalFormatThrowsBizException() {
        BizException start = assertThrows(BizException.class, () -> DateRanges.startOfDay("2026/10/01"));
        assertTrue(start.getMessage().contains("yyyy-MM-dd"), "文案要给正确格式，实际=" + start.getMessage());
        assertTrue(start.getMessage().contains("开始日期"), "文案要指明是哪一端，实际=" + start.getMessage());

        BizException end = assertThrows(BizException.class,
                () -> DateRanges.endOfDay("2026-10-01 12:00:00"));
        assertTrue(end.getMessage().contains("结束日期"), "实际=" + end.getMessage());

        // 不存在的日期（2 月 30 日）同样按非法输入处理，而不是悄悄滚到 3 月
        assertThrows(BizException.class, () -> DateRanges.startOfDay("2026-02-30"));
    }
}
