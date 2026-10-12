package com.survey.common;

/**
 * 题型 —— 取代散落各处的魔数 {@code 1 / 2 / 3}。
 *
 * ── 为什么必须收口 ────────────────────────────────────
 *   `type == 1 || type == 2`（是不是选择题）、`type == 3`（是不是填空）
 *   这类判断一旦散落在 SurveyService / QuestionService / DemoDataService 等处，
 *   就只能靠注释说明"1 单选、2 多选、3 填空"；新增题型（评分题、量表题、矩阵题）时，
 *   要在复制、保存、导出、答题端多处同步修改 —— 极易漏。
 *   收口之后，"哪些题型有选项"只有 {@link #hasOptions(Integer)} 一个地方说了算。
 *
 * ── 与数据库的约定（不要改这些数字）────────────────────
 *   1 = 单选题（有 options）
 *   2 = 多选题（有 options）
 *   3 = 单行填空（没有 options）
 */
public enum QuestionType {

    /** 单选题 */
    SINGLE(1),
    /** 多选题 */
    MULTI(2),
    /** 单行填空 */
    TEXT(3);

    private final int code;

    QuestionType(int code) {
        this.code = code;
    }

    /** 存库用的数值 */
    public int code() {
        return code;
    }

    /**
     * 按数值判断是否有选项（数据可能来自 DB 的 Integer）。
     * ★ 这是全项目"选择题"的**唯一判定口径** —— 保存、复制、导出、统计都走它，
     *   以后新增题型（如评分题）只要在这里加一个分支即可。
     * ★ 只保留这一个**静态**版本：题型值来自 DB 的 Integer，
     *   再来一个无参实例方法会让"持有 Integer 的地方误写 q.getType().hasOptions()"成为可能。
     */
    public static boolean hasOptions(Integer type) {
        return type != null && (type == SINGLE.code || type == MULTI.code);
    }

    /** 是否填空题 */
    public static boolean isText(Integer type) {
        return type != null && type == TEXT.code;
    }

    /** 是否单选题 */
    public static boolean isSingle(Integer type) {
        return type != null && type == SINGLE.code;
    }
}
