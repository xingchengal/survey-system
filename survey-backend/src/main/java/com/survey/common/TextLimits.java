package com.survey.common;

/**
 * 文本长度上限 —— 全项目"某个字段最多能存多少字"的唯一口径。
 * ★ 常量必须等于数据库列宽：Java 放行、MySQL 拒绝时，用户拿到的是 500 而不是"多写了两百字"。
 * ★ 需要更长的文本时先 ALTER 列宽、再改这里；只调大数字会在运行时炸 500。
 */
public final class TextLimits {

    /** t_survey.title —— varchar(200) */
    public static final int SURVEY_TITLE = 200;

    /** t_survey.description —— varchar(500) */
    public static final int SURVEY_DESCRIPTION = 500;

    /** t_question.title —— varchar(500) */
    public static final int QUESTION_TITLE = 500;

    /** t_option.content —— varchar(500) */
    public static final int OPTION_CONTENT = 500;

    /** t_answer_detail.content —— varchar(1000) */
    public static final int ANSWER_CONTENT = 1000;

    /** t_question.matrix_rows / matrix_cols —— varchar(2000)，存 JSON 数组文本 */
    public static final int MATRIX_AXIS = 2000;

    /** t_answer_detail.row_key —— varchar(500)（矩阵行标题原文，与选项同宽） */
    public static final int MATRIX_ROW_KEY = 500;

    /** t_user.username / t_user.nickname —— varchar(50) */
    public static final int USER_NAME = 50;

    /**
     * t_template_category.name —— varchar(50)，与 t_survey.template_category 同宽：
     * 否则"清单里能存、模板上存不下"会变成 500。
     */
    public static final int TEMPLATE_CATEGORY = 50;

    /** t_announcement.title —— varchar(200) */
    public static final int ANNOUNCEMENT_TITLE = 200;

    /**
     * t_announcement.content —— text（上限 65535 字节，不是字符）；不校验会变成 500 而不是 400。
     * 16000 是保守值：requireWithin 数的是 Java 字符，utf8mb4 下每字符最多 4 字节。
     */
    public static final int ANNOUNCEMENT_CONTENT = 16000;

    private TextLimits() {
    }

    /**
     * 校验文本长度，超限抛 BizException（→ 400 + "XX不能超过 N 个字符"）；null 视为没填，直接放行。
     * @param max        上限（应取自本类常量）
     * @param fieldLabel 字段中文名，如 "题目内容"
     */
    public static void requireWithin(String value, int max, String fieldLabel) {
        if (value != null && value.length() > max) {
            throw new BizException(fieldLabel + "不能超过 " + max + " 个字符");
        }
    }
}
