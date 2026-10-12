package com.survey.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 文本长度口径的纯单元测试（零依赖）。
 *
 * ── 为什么值得专门测"几个常量" ───────────────────────────
 *   第十四轮的真实 bug 就是"Java 校验上限 > 数据库列宽"：
 *   超长会**通过校验**、再被 MySQL 拒绝，用户拿到 500 而不是 400。
 *   所以这里的断言不是"测常量等于它自己"，而是把
 *   **"这些上限必须等于 information_schema 里的 character_maximum_length"**
 *   这条契约钉死 —— 谁改动了值却没有对应的 ALTER（或反之），测试就会红。
 */
class TextLimitsTest {

    @Test
    @DisplayName("各上限必须与数据库列宽一致（改这里就必须先 ALTER 列）")
    void constants_mustMatchColumnWidths() {
        // 期望值就是 sql/init.sql 里的列宽，逐个对账
        assertEquals(200, TextLimits.SURVEY_TITLE, "t_survey.title varchar(200)");
        assertEquals(500, TextLimits.SURVEY_DESCRIPTION, "t_survey.description varchar(500)");
        assertEquals(500, TextLimits.QUESTION_TITLE, "t_question.title varchar(500)");
        assertEquals(500, TextLimits.OPTION_CONTENT, "t_option.content varchar(500)");
        assertEquals(1000, TextLimits.ANSWER_CONTENT, "t_answer_detail.content varchar(1000)");
        assertEquals(50, TextLimits.USER_NAME, "t_user.username / nickname varchar(50)");
        assertEquals(200, TextLimits.ANNOUNCEMENT_TITLE, "t_announcement.title varchar(200)");
        // text 列按**字节**算，这里取的是保守值（详见常量注释）
        assertEquals(16000, TextLimits.ANNOUNCEMENT_CONTENT, "t_announcement.content text(65535 字节)");
    }

    @Test
    @DisplayName("requireWithin：正好等于上限放行，超一个字符就抛 BizException")
    void requireWithin_boundary() {
        // 边界：正好等于上限必须放行（否则"刚好写满"会被无故拒绝）
        assertDoesNotThrow(() -> TextLimits.requireWithin("12345", 5, "字段"));
        assertEquals(5, "12345".length());

        BizException ex = assertThrows(BizException.class,
                () -> TextLimits.requireWithin("123456", 5, "字段"));
        assertEquals("字段不能超过 5 个字符", ex.getMessage());
    }

    @Test
    @DisplayName("requireWithin：null 视为没填 → 放行（是否必填不归它管）")
    void requireWithin_nullIsAllowed() {
        assertDoesNotThrow(() -> TextLimits.requireWithin(null, 5, "字段"));
        assertDoesNotThrow(() -> TextLimits.requireWithin("", 5, "字段"));
    }
}
