package com.survey.common;

/**
 * LIKE 通配符转义 —— 让用户输入里的 % 和 _ 按**字面字符**处理。
 * 参数化（{@code #{}}）挡住了 SQL 注入，但通配符没转义：只输一个 % 就退化成"匹配所有行"，筛选被静默绕过。
 * ★ 只用于用户输入的模糊搜索参数；固定字面前缀（如 findByUsernamePrefix("demo_user")）不要转义。
 * SQL 不用改：MySQL 的 LIKE 默认以反斜杠为转义字符。
 */
public final class SqlLikeEscaper {

    private SqlLikeEscaper() {
    }

    /**
     * 转义 LIKE 的通配符（反斜杠必须**第一个**替换，否则新加的反斜杠会被二次转义）。
     * @param keyword 用户输入（可为 null）
     * @return 转义后的值；null / 空串原样返回
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
