package com.survey.common;

/**
 * 分页参数保护 —— 统一收敛「页码 / 每页条数」的合法范围。
 *
 * ── 为什么需要它 ──────────────────────────────────────
 *   各列表接口的 pageNum / pageSize 直接来自 query 参数，**不能没有上限**：
 *   传 `pageSize=100000`，PageHelper 就真的去 `LIMIT 100000`，
 *   一次请求就可能把内存与网络打满，分页本身也失去意义。
 *
 *   统一夹紧：pageNum ≥ 1，pageSize ∈ [1, 100]。
 *   前端默认用的就是 10 / 20，所以正常使用完全无感。
 *
 * ★ 关联：SurveyService / UserService / ErrorLogService / OperationLogService
 *   里所有 PageHelper.startPage(...) 调用点。
 */
public final class PageParams {

    /** 每页最多 100 条（前端分页器的最大档位也是 100） */
    public static final int MAX_PAGE_SIZE = 100;

    private PageParams() {
    }

    /** 页码至少为 1（传 0 / 负数会让 LIMIT 偏移算错） */
    public static int pageNum(int value) {
        return Math.max(1, value);
    }

    /** 每页条数夹在 [1, 100] */
    public static int pageSize(int value) {
        return Math.min(Math.max(1, value), MAX_PAGE_SIZE);
    }
}
