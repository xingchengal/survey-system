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
 *   8 = 矩阵题（行/列标题存 t_question.matrix_rows / matrix_cols，**不占 options**；
 *       答案一行一个矩阵行，content=列标题、row_key=行标题）
 *   9 = 排序题（有 options；答案一选项一行，rank_no=名次，content=选项原文）
 *  10 = NPS（0~10 分，没有 options，content 存 "0"~"10"）
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
    DATE(7),
    /** 矩阵（行 × 列） */
    MATRIX(8),
    /** 排序（把选项拖成 1..N） */
    RANKING(9),
    /** NPS（0~10 分） */
    NPS(10);

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
     * ★ 这是全项目"选项存在 t_option"的**唯一判定口径** —— 保存、复制、版本快照都走它。
     * ★ 下拉有 options（复用选项表），但它仍是"单选语义"（一题一行）。
     * ★ 排序题也有 options：候选答案就是被排序的对象（答案一选项一行 + rank_no）。
     * ★ 矩阵题**不在其中**：它的行列标题在 t_question 的两列里，不占 t_option。
     */
    public static boolean hasOptions(Integer type) {
        return type != null && (type == SINGLE.code || type == MULTI.code
                || type == SELECT.code || type == RANKING.code);
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
     * 是否用"分组计数"口径出统计卡（chartData）。
     * ★ 评分题没有 options，但答案就是 1~5 的离散分数，按 content 分组天然是分布图；
     *   NPS 同理（0~10 的分布就是它的图）。
     * ★ 矩阵/排序也走 chartData，但**不是**普通的 content 分布：
     *     · 矩阵按 `(row_key, content)` 分组（每一行各看各的列分布）；
     *     · 排序返回 `{content, count, avgRank}`（每个选项的平均名次）。
     *   统计侧靠 {@link #isMatrix}/{@link #isRanking} 分流，见 AnswerService.getSurveyStatistics。
     */
    public static boolean hasChart(Integer type) {
        return hasOptions(type) || isRating(type) || isNps(type) || isMatrix(type);
    }

    /** 矩阵题（行 × 列） */
    public static boolean isMatrix(Integer type) {
        return type != null && type == MATRIX.code;
    }

    /** 排序题 */
    public static boolean isRanking(Integer type) {
        return type != null && type == RANKING.code;
    }

    /** NPS（0~10） */
    public static boolean isNps(Integer type) {
        return type != null && type == NPS.code;
    }

    /**
     * 是否属于「进阶题型包」（矩阵 / 排序 / NPS）—— 计费判定的唯一口径。
     * ★ 计费在保存题目时按"问卷是否含进阶题型"一次性扣（见 WalletFeature.ADVANCED_TYPES），
     *   所以这个判断只允许出现在 {@code QuestionService.doSaveQuestions} 与测试里。
     */
    public static boolean isAdvanced(Integer type) {
        return isMatrix(type) || isRanking(type) || isNps(type);
    }

    /**
     * 是否允许"一道题多行答案"。
     * ★ 这三类都是"一选项/一行一条"：多选（勾几个几条）、矩阵（一行一条）、
     *   排序（每个名次一条）。提交守卫必须放行它们，否则勾第二个选项/填第二行就被判灌票。
     * ★ 不要用 `hasOptions && !isSingle` 代替：矩阵没有 options，会被漏掉。
     */
    public static boolean isMultiRow(Integer type) {
        return type != null && (type == MULTI.code || type == MATRIX.code || type == RANKING.code);
    }

    /** 是否是自由文本类（单行填空 / 多行文本）—— 统计侧作为文本列表展示 */
    public static boolean isFreeText(Integer type) {
        return isText(type) || isTextarea(type);
    }
}
