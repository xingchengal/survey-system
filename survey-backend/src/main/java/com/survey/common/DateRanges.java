package com.survey.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 管理端「时间范围」筛选的统一解析口径。
 *
 * ── 为什么要有这个类 ────────────────────────────────────
 *   问卷列表（{@code /api/survey/page}）、用户导出（{@code /api/user/export}）、
 *   操作日志（{@code /api/log/page}）三处都要接受"开始日期 / 结束日期"。
 *   如果各写一遍 `LocalDate.parse`，很快就会出现三种不一致：
 *     · 一处把结束日期当成 `00:00:00`（当天的数据全被漏掉）；
 *     · 一处用 `DATE(created_time)` 包函数（索引失效，全表扫）；
 *     · 一处对非法格式抛 500 而不是 400。
 *   ⇒ 口径收在这里：**入参是 `yyyy-MM-dd`，出参是当天的起止 LocalDateTime**。
 *
 * ── 三条约定 ──────────────────────────────────────────
 *   1. 空值（null / 空白）返回 null = "不限制这一端"，调用方据此决定要不要拼 SQL 条件；
 *   2. 结束日期取当天 **23:59:59**（不是 00:00:00，也不是次日 00:00:00）：
 *      表里是 datetime，用 `<= 当天 23:59:59` 才能把当天整天的记录都收进来；
 *      用"次日 00:00:00 的 < 比较"也可以，但项目其它地方（报表/导出）都是闭区间，
 *      两种写法并存迟早有人写反；
 *   3. 格式非法一律抛 {@link BizException}（→ 400 + 可读文案），**不是** 500：
 *      用户手输/拼错日期是可预期的输入，不是程序故障。
 *
 * ★ 传进来的是**日期**而不是时间：前端用 el-date-picker 的 `value-format="YYYY-MM-DD"`，
 *   传 `2026-10-01 12:30:00` 这种带时间的值会解析失败 —— 这是有意的，
 *   时间精度筛选在当前需求里没有出现，收窄格式可以避免"时区/时分秒被忽略"的静默歧义。
 */
public final class DateRanges {

    /** 结束日期的时分秒：取当天最后一秒，保证闭区间能覆盖整天。 */
    private static final String END_OF_DAY = "23:59:59";

    private DateRanges() {
    }

    /**
     * 解析"开始日期"，返回当天 00:00:00。
     *
     * @param date `yyyy-MM-dd` 格式的日期；null / 空白表示不限制
     * @return 解析成功返回 {@code date 00:00:00}；入参为空返回 null
     * @throws BizException 格式非法（不是 yyyy-MM-dd）
     */
    public static LocalDateTime startOfDay(String date) {
        LocalDate parsed = parse(date, "开始日期");
        return parsed == null ? null : parsed.atStartOfDay();
    }

    /**
     * 解析"结束日期"，返回当天 23:59:59。
     *
     * @param date `yyyy-MM-dd` 格式的日期；null / 空白表示不限制
     * @return 解析成功返回 {@code date 23:59:59}；入参为空返回 null
     * @throws BizException 格式非法（不是 yyyy-MM-dd）
     */
    public static LocalDateTime endOfDay(String date) {
        LocalDate parsed = parse(date, "结束日期");
        if (parsed == null) return null;
        return LocalDateTime.of(parsed, java.time.LocalTime.of(23, 59, 59));
    }

    private static LocalDate parse(String date, String fieldName) {
        if (date == null || date.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            // ★ 文案里写明正确格式：用户看到的应该是"该怎么填"，而不是一句"参数错误"。
            throw new BizException(fieldName + "格式不正确，应为 yyyy-MM-dd（例如 2026-10-01）");
        }
    }
}
