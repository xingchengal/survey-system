package com.survey.common;

/**
 * 分页参数保护 —— 统一收敛「页码 / 每页条数」的合法范围。
 *
 * ── 为什么需要它 ──────────────────────────────────────
 *   各列表接口的 pageNum / pageSize 直接来自 query 参数，**不能没有上限**：
 *   传 `pageSize=100000`，PageHelper 就真的去 `LIMIT 100000`，
 *   一次请求就可能把内存与网络打满，分页本身也失去意义。
 *
 *   统一夹紧：pageNum ∈ [1, 10000]，pageSize ∈ [1, 100]。
 *   前端默认用的就是 10 / 20，所以正常使用完全无感。
 *
 * ── 为什么页码也要有上限（不只是"≥1"）──────────────────
 *   页码直接进 `OFFSET`，而 `OFFSET` 是**先跳过再取**：`pageNum=2_000_000_000` 时
 *   ① `(pageNum - 1) * pageSize` 在 int 上**溢出成负数**，SQL 直接报错 → 500；
 *   ② 就算不溢出，让数据库白扫上亿行也是现成的 DoS 入口。
 *   10000 页 × 100 条 = 100 万条，已经远超本项目的真实数据量，
 *   再往后翻没有业务价值 —— 所以要在这里挡住，而不是指望调用方自觉。
 *
 * ★ 关联：SurveyService / UserService / ErrorLogService / OperationLogService 的
 *   {@code PageHelper.startPage(...)} 调用点，以及 {@code AnswerService.listAnswersPage}
 *   （手写 {@code LIMIT/OFFSET} 的那条 —— 偏移量一律用 {@link #offset(int, int)} 算）。
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
     *
     * ★ 用 **long** 做乘法：两个 int 相乘时，`(2_000_000_000 - 1) * 100` 会溢出成负数，
     *   而 MySQL 的 OFFSET 不接受负数（报错 → 500）。入参先各自夹紧，再相乘，
     *   所以正常路径拿到的就是 `(pageNum-1)*pageSize`，异常路径也不会溢出。
     */
    public static long offset(int pageNum, int pageSize) {
        int num = pageNum(pageNum);
        int size = pageSize(pageSize);
        return (long) (num - 1) * size;
    }
}
