package com.survey.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 管理端「时间范围」筛选的统一解析口径：入参 `yyyy-MM-dd`，出参当天的起止 LocalDateTime。
 * 结束日期取当天 **23:59:59**（闭区间），不是 00:00:00，也不是次日 00:00:00。
 * 空值返回 null = 不限制；格式非法抛 {@link BizException}（400），不是 500。
 * ★ 只收日期不收时间：带时分秒的值解析失败是有意的，避免"时分秒被静默忽略"。
 */
public final class DateRanges {

    /** 结束日期的时分秒：当天最后一秒，闭区间覆盖整天。 */
    private static final String END_OF_DAY = "23:59:59";

    private DateRanges() {
    }

    /**
     * @param date `yyyy-MM-dd` 格式的日期；null / 空白表示不限制
     * @return 当天 00:00:00；入参为空返回 null
     * @throws BizException 格式非法（不是 yyyy-MM-dd）
     */
    public static LocalDateTime startOfDay(String date) {
        LocalDate parsed = parse(date, "开始日期");
        return parsed == null ? null : parsed.atStartOfDay();
    }

    /**
     * @param date `yyyy-MM-dd` 格式的日期；null / 空白表示不限制
     * @return 当天 23:59:59；入参为空返回 null
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
            // ★ 文案写明正确格式：用户要看到"该怎么填"，不是一句"参数错误"
            throw new BizException(fieldName + "格式不正确，应为 yyyy-MM-dd（例如 2026-10-01）");
        }
    }
}
