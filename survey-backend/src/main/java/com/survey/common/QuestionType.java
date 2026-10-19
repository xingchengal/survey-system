package com.survey.common;

/**
 * 题型：1 单选 / 2 多选 / 3 单行填空 / 4 多行文本 / 5 下拉 / 6 评分 / 7 日期 / 8 矩阵 / 9 排序 / 10 NPS。
 * ★ 新题型一律追加在末尾，绝不复用旧数字：历史答卷只存 content，复用数字会让老答卷按新题型解释。
 * ★ "有选项 / 出图表 / 多行答案"只由本类判定，别在 Service 里另写一套。
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
     * 是否有选项（存在 t_option）—— 全项目唯一判定口径，保存、复制、版本快照都走它。
     * ★ 矩阵题不占 t_option：行列标题在 t_question 的两列里。
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
     * 是否用"分组计数"口径出统计卡：评分 / NPS 按 content 分布；
     * 矩阵按 (row_key, content)、排序返回 {content, count, avgRank}，统计侧靠 isMatrix / isRanking 分流。
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

    /** 是否属于进阶题型包（矩阵 / 排序 / NPS）—— 计费判定的唯一口径，只应在保存题目与测试里调用。 */
    public static boolean isAdvanced(Integer type) {
        return isMatrix(type) || isRanking(type) || isNps(type);
    }

    /**
     * 是否允许"一道题多行答案"（多选 / 矩阵 / 排序）—— 提交守卫必须放行，否则勾第二项就被判灌票。
     * 不要用 `hasOptions && !isSingle` 代替：矩阵没有 options，会被漏掉。
     */
    public static boolean isMultiRow(Integer type) {
        return type != null && (type == MULTI.code || type == MATRIX.code || type == RANKING.code);
    }

    /** 是否是自由文本类（单行填空 / 多行文本）—— 统计侧作为文本列表展示 */
    public static boolean isFreeText(Integer type) {
        return isText(type) || isTextarea(type);
    }
}
