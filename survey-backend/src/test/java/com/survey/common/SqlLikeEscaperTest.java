package com.survey.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LIKE 通配符转义的纯单元测试（零依赖）。
 *
 * ── 为什么值得测 ──────────────────────────────────────
 *   转义写错了不会报错，只会**静默地**让搜索行为不对：
 *   · 少转义一个 {@code %} → 用户搜一个百分号就变成"匹配所有行"（筛选失效）；
 *   · 反斜杠替换顺序写反 → 转义字符自身被重复转义，搜什么都搜不到。
 *   这两种都是"看起来能用、实际不对"，必须靠测试钉住。
 */
class SqlLikeEscaperTest {

    @Test
    @DisplayName("三个通配符/转义字符都被加上反斜杠")
    void escape_shouldEscapeWildcards() {
        assertEquals("100\\%", SqlLikeEscaper.escape("100%"), "% 必须转义");
        assertEquals("a\\_b", SqlLikeEscaper.escape("a_b"), "_ 必须转义");
        assertEquals("c\\\\d", SqlLikeEscaper.escape("c\\d"), "反斜杠自身必须转义");
    }

    @Test
    @DisplayName("★ 反斜杠必须最先替换：否则新加的反斜杠会被再转义一次")
    void escape_backslashMustBeReplacedFirst() {
        // 输入：一个反斜杠 + 一个百分号
        // 正确结果：两个反斜杠（原反斜杠被转义） + 一个反斜杠 + 百分号（通配符被转义）
        assertEquals("\\\\\\%", SqlLikeEscaper.escape("\\%"));
    }

    @Test
    @DisplayName("正常关键词原样返回（不能把普通搜索也改了）")
    void escape_shouldKeepNormalKeyword() {
        assertEquals("满意度", SqlLikeEscaper.escape("满意度"));
        assertEquals("user123", SqlLikeEscaper.escape("user123"));
        assertEquals("a b", SqlLikeEscaper.escape("a b"));
    }

    @Test
    @DisplayName("null / 空串原样返回（SQL 侧的 <if> 会跳过空值分支）")
    void escape_shouldPassThroughNullAndEmpty() {
        assertNull(SqlLikeEscaper.escape(null));
        assertEquals("", SqlLikeEscaper.escape(""));
    }

    @Test
    @DisplayName("多个通配符全部转义")
    void escape_shouldEscapeAll() {
        assertEquals("\\%\\_\\%", SqlLikeEscaper.escape("%_%"));
    }
}
