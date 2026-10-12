package com.survey.common;

/**
 * 问卷状态 —— 取代散落各处的魔数 {@code 0 / 1 / 2}。
 *
 * ── 为什么必须收口 ────────────────────────────────────
 *   `status == 1`（已发布）、`status == 0`（草稿）、`status == 2`（已结束）
 *   这些字面量一旦散落在多个文件里重复出现，含义就只能**靠注释维持**；
 *   想加一个"已暂停"状态时得把复制、发布、查询、导出、统计逐个翻一遍 —— 很容易漏。
 *   收口之后，读代码看到 {@code SurveyStatus.isPublished(x)} 就知道在判什么。
 *
 * ── 与数据库的约定（不要改这些数字）────────────────────
 *   0 = 草稿   （新建、AI 生成、从模板创建后的初始状态）
 *   1 = 已发布 （匿名答题页可访问）
 *   2 = 已结束
 *   ★ **status 不是"还能不能收答卷"的判据**：过了截止时间的问卷由定时任务置为 2（供列表/看板展示），
 *     而"能不能收"由 AnswerService.submit 就地比 end_time 判定 —— 若只看 status，
 *     定时任务还没扫到的那个窗口里，过期问卷会照收答卷。
 *   ★ 数据库列类型是 Integer，**允许 NULL**（历史数据），所以所有判断都按
 *     "NULL 视为草稿"处理 —— 这也是为什么这里提供的是静态判断方法而不是直接比较枚举。
 */
public enum SurveyStatus {

    /** 草稿：只有作者和管理员可见，不收答卷 */
    DRAFT(0),
    /** 已发布：可访问；能不能提交答卷还取决于是否已过截止时间（见类注释） */
    PUBLISHED(1),
    /** 已结束：不再收答卷；按截止时间由定时任务置位，仅作状态展示 */
    ENDED(2);

    private final int code;

    SurveyStatus(int code) {
        this.code = code;
    }

    /** 存库用的数值 */
    public int code() {
        return code;
    }

    /** 是否草稿（★ null 也算草稿 —— 历史数据里有 status 为 NULL 的问卷） */
    public static boolean isDraft(Integer status) {
        return status == null || status == DRAFT.code;
    }

    /** 是否已发布 */
    public static boolean isPublished(Integer status) {
        return status != null && status == PUBLISHED.code;
    }

    /** 是否已结束 */
    public static boolean isEnded(Integer status) {
        return status != null && status == ENDED.code;
    }
}
