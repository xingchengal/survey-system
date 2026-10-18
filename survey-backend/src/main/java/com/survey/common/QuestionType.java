package com.survey.common;

/**
 * 题型 —— 取代散落各处的魔数 {@code 1 / 2 / 3 / 4 / 5 / 6 / 7}。
 *
 * ── 为什么必须收口 ────────────────────────────────────
 *   一旦判断散落在 AnswerService / QuestionService / DemoDataService 等处，
 *   新增题型时就要在保存、复制、导出、答题端、统计端多处同步修改 —— 极易漏。
 *   收口之后，"哪些题型有选项""哪些题型出图表"只有这里说了算。
 *
 * ── 与数据库的约定（不要改这些数字）────────────────────
 *   1 = 单选题（有 options）
 *   2 = 多选题（有 options，一选项一行）
 *   3 = 单行填空（没有 options）
 *   4 = 多行文本（没有 options）
 *   5 = 下拉单选（有 options，单选语义）
 *   6 = 评分题（1~5 分，没有 options）
 *   7 = 日期（yyyy-MM-dd，没有 options）
 *
 * ★ 新题型一律**追加**在末尾，绝不复用旧数字：
 *   历史答卷的 t_answer_detail 只存 content，题型由 t_question.type 决定，
 *   复用数字会让"老答卷按新题型解释"，统计直接错乱。
 */
public enum QuestionType {

    /** 单选题 */
    SINGLE(1),
    /** 多选题 */
    MULTI(2),
    /** 单行填空 */
    TEXT(3),
    /** 多行文本 */
    TEXTAREA(4),
    /** 下拉单选 */
    SELECT(5),
    /** 评分题（1~5） */
    RATING(6),
    /** 日期 */
    DATE(7);

    private final int code;

    QuestionType(int code) {
        this.code = code;
    }

    /** 存库用的数值 */
    public int code() {
        return code;
    }

    /**
     * 是否有选项（数据可能来自 DB 的 Integer）。
     * ★ 这是全项目"选择题"的**唯一判定口径** —— 保存、复制、导出、统计都走它。
     * ★ 下拉单选有 options（复用选项表），但它仍是"单选语义"（一题一行）。
     */
    public static boolean hasOptions(Integer type) {
        return type != null && (type == SINGLE.code || type == MULTI.code || type == SELECT.code);
    }

    /** 单行填空 */
    public static boolean isText(Integer type) {
        return type != null && type == TEXT.code;
    }

    /** 单选语义（一题只能一行答案）：单选 / 下拉 */
    public static boolean isSingle(Integer type) {
        return type != null && (type == SINGLE.code || type == SELECT.code);
    }

    /** 评分题（1~5 分） */
    public static boolean isRating(Integer type) {
        return type != null && type == RATING.code;
    }

    /** 日期题 */
    public static boolean isDate(Integer type) {
        return type != null && type == DATE.code;
    }

    /** 多行文本 */
    public static boolean isTextarea(Integer type) {
        return type != null && type == TEXTAREA.code;
    }

    /**
     * 是否用"选项分布"口径出图（饼图/条形图）。
     * ★ 评分题没有 options，但答案就是 1~5 的离散分数，按 content 分组天然是分布图，
     *   所以统计侧要和选择题一样走 chartData，而不是"填空题原文列表"。
     */
    public static boolean hasChart(Integer type) {
        return hasOptions(type) || isRating(type);
    }

    /** 是否是自由文本类（单行填空 / 多行文本）—— 统计侧作为文本列表展示 */
    public static boolean isFreeText(Integer type) {
        return isText(type) || isTextarea(type);
    }
}
