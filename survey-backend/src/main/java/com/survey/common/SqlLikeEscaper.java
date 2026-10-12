package com.survey.common;

/**
 * LIKE 通配符转义 —— 让用户输入里的 % 和 _ 按**字面字符**处理。
 *
 * ── 为什么需要它 ──────────────────────────────────────
 *   全项目 8 个查询方法、共 15 处模糊搜索都写成 {@code LIKE CONCAT('%', #{keyword}, '%')}。
 *   参数化（{@code #{}}）确实挡住了 SQL 注入，但**通配符没有被转义**，于是：
 *     · 用户在搜索框里只输入一个 {@code %}，条件就退化成"匹配所有行" ——
 *       筛选被**静默绕过**（管理员以为筛过了，其实拿到的是全量）；
 *     · 输入 {@code a_b} 会命中 {@code axb}（{@code _} 在 LIKE 里表示"任意一个字符"）；
 *     · 而 {@code %} 开头的模式本来就用不上索引，混入用户输入的通配符会让扫描范围更加失控
 *       （{@code t_error_log.error_message} 还是 TEXT 列）。
 *
 * ── 怎么用 ────────────────────────────────────────────
 *   在**调用 Mapper 之前**对每个模糊搜索参数调一次：
 *   <pre>
 *       keyword = SqlLikeEscaper.escape(keyword);
 *       List&lt;Survey&gt; list = surveyMapper.findPageByCondition(keyword, status);
 *   </pre>
 *   SQL **不用改**：MySQL 的 LIKE 默认就以反斜杠作为转义字符，
 *   所以把 {@code \} {@code %} {@code _} 各加一个反斜杠即可。
 *
 * ── 两个注意点 ────────────────────────────────────────
 *   1. 只用于**用户输入**的模糊搜索参数。像演示数据回收那种固定字面前缀
 *      （{@code findByUsernamePrefix("demo_user")}）**不要**转义 —— 它不含通配符，
 *      转了反而查不到；
 *   2. 若哪天给 MySQL 开了 {@code NO_BACKSLASH_ESCAPES}，要在 SQL 里显式写
 *      {@code ESCAPE '\\'}，本类的输出仍然适用。
 */
public final class SqlLikeEscaper {

    private SqlLikeEscaper() {
    }

    /**
     * 转义 LIKE 的通配符。
     *
     * ★ 反斜杠必须**第一个**替换：否则后面新加进去的反斜杠会被再次转义，结果全乱。
     *
     * @param keyword 用户输入（可以为 null）
     * @return 转义后的值；null / 空串原样返回（SQL 侧的 &lt;if&gt; 会跳过空值分支）
     */
    public static String escape(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return keyword;
        }
        return keyword.replace("\\", "\\\\")
                      .replace("%", "\\%")
                      .replace("_", "\\_");
    }
}
