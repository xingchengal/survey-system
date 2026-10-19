package com.survey.common;

/**
 * 分页参数保护 —— 统一夹紧「页码 / 每页条数」：pageNum ∈ [1, 10000]，pageSize ∈ [1, 100]。
 * ★ pageNum 也要上限：页码直接进 OFFSET，int 溢出会变负数（SQL 报错 → 500），
 *   不溢出时深翻页也是现成的 DoS 入口。
 * ★ 手写 LIMIT/OFFSET 的调用点（如 AnswerService.listAnswersPage）一律用 {@link #offset(int, int)} 算偏移。
 */
public final class PageParams {

    /** 每页最多 100 条（前端分页器的最大档位也是 100） */
    public static final int MAX_PAGE_SIZE = 100;

    /** 页码上限（防深翻页与 int 溢出，见类注释） */
    public static final int MAX_PAGE_NUM = 10_000;

    private PageParams() {
    }

    /** 页码夹在 [1, 10000]（传 0 / 负数会让 LIMIT 偏移算错；传天文数字会溢出/深翻页） */
    public static int pageNum(int value) {
        return Math.min(Math.max(1, value), MAX_PAGE_NUM);
    }

    /** 每页条数夹在 [1, 100] */
    public static int pageSize(int value) {
        return Math.min(Math.max(1, value), MAX_PAGE_SIZE);
    }

    /**
     * 计算 OFFSET：`(pageNum - 1) × pageSize`。
     * ★ 必须用 long 做乘法：两个 int 相乘会溢出成负数，MySQL 的 OFFSET 不接受负数（→ 500）。
     */
    public static long offset(int pageNum, int pageSize) {
        int num = pageNum(pageNum);
        int size = pageSize(pageSize);
        return (long) (num - 1) * size;
    }
}
