package com.survey.common;

/**
 * 文本长度上限 —— 全项目"某个字段最多能存多少字"的**唯一口径**。
 *
 * ── 为什么必须与列宽一致 ────────────────────────────────
 *   校验上限大于**数据库列宽**时会出现一类很难查的错误：
 *   Java 侧放行 → MySQL 拒绝 → 用户拿到 **500「系统繁忙」**，
 *   而真正的原因只是"描述多写了两百个字"。
 *   ⇒ 题干、选项、公告标题这类文本都必须校验，且上限取自本类的常量。
 *
 * ── 怎么用 ────────────────────────────────────────────
 *   · 常量值必须等于 information_schema 里的 character_maximum_length；
 *   · 校验走 {@link #requireWithin}（超限抛 BizException → 400 + 明确文案）。
 *   ★ 业务上确实需要更长的文本时，**先 ALTER 列宽、再同步改这里**；
 *     只调大这里的数字只会在运行时炸 500。
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

    /** t_user.username / t_user.nickname —— varchar(50) */
    public static final int USER_NAME = 50;

    /** t_announcement.title —— varchar(200) */
    public static final int ANNOUNCEMENT_TITLE = 200;

    /**
     * t_announcement.content —— text（上限 65535 **字节**，不是字符）。
     * ★ 这一列**必须校验**：列定义是 text NOT NULL，所以"正文超过 65535 字节
     *   （utf8mb4 下约 1.6 万汉字）"或"干脆不传 content"都会变成
     *   500「系统繁忙」，而不是 400 + 明确提示。（title 那侧同理，见上。）
     * ★ 注意单位差异：这里是**字节**上限（MySQL 的 text 按字节算），
     *   而 requireWithin 数的是 Java 字符数（utf8mb4 下一个汉字最多 4 字节）。
     *   所以取一个保守值：按"每字符最多 4 字节"折算，16383 字符以内必定安全。
     */
    public static final int ANNOUNCEMENT_CONTENT = 16000;

    private TextLimits() {
    }

    /**
     * 校验文本长度，超限抛 {@link BizException}（→ 400 + "XX不能超过 N 个字符"）。
     *
     * ★ null 视为"没有填"，直接放行 —— 是否必填属于各自的业务规则，不归这里管。
     *
     * @param value      待校验的文本
     * @param max        上限（应当取自本类的常量）
     * @param fieldLabel 字段中文名，用于拼文案，如 "题目内容" → "题目内容不能超过 500 个字符"
     */
    public static void requireWithin(String value, int max, String fieldLabel) {
        if (value != null && value.length() > max) {
            throw new BizException(fieldLabel + "不能超过 " + max + " 个字符");
        }
    }
}
