package com.survey.common;

import com.survey.entity.AnswerDetail;
import com.survey.entity.Option;
import com.survey.entity.Question;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把"一道题的多行明细"渲染成**一行人类可读的文本**。
 *
 * ── 为什么单独抽出来 ────────────────────────────────────
 *   同一套口径现在有两个调用方：
 *     · `AnswerController` 的 Excel 导出（每个单元格一道题）；
 *     · 「我的成绩 → 查看详情」（逐题显示"我的答案"）。
 *   两处各写一遍必然漂移（多选只剩最后一项、矩阵丢行都是这么来的）。
 *   所以**渲染口径只留这一份**。
 *
 * ── 各题型的格式 ────────────────────────────────────────
 *   多选          ：`A、B、C`（按存储顺序）
 *   矩阵          ：`服务态度：满意；响应速度：一般`（按题目里配置的行顺序，脏行追加在后）
 *   排序          ：`1. A；2. B；3. C`（按名次升序）
 *   单选/填空/评分/NPS/日期：原文
 *
 * ★ 这里**不做任何转义**：Excel 单元格与页面文本都按原样展示。
 */
public final class AnswerDisplay {

    private AnswerDisplay() {
    }

    /**
     * @param q       题目（需要 type / matrixRows；没有也能渲染，只是矩阵会按存储顺序输出）
     * @param details 该题的全部明细行（可能为 null / 空）
     * @return 空明细返回空串；否则返回可读文本
     */
    public static String format(Question q, List<AnswerDetail> details) {
        if (details == null || details.isEmpty()) {
            return "";
        }
        if (q != null && QuestionType.isRanking(q.getType())) {
            List<AnswerDetail> sorted = new ArrayList<>(details);
            // null 名次放最后（历史脏数据），不参与排序断言的稳定性
            sorted.sort((a, b) -> Integer.compare(
                    a.getRankNo() == null ? Integer.MAX_VALUE : a.getRankNo(),
                    b.getRankNo() == null ? Integer.MAX_VALUE : b.getRankNo()));
            StringBuilder sb = new StringBuilder();
            for (AnswerDetail d : sorted) {
                if (sb.length() > 0) sb.append("；");
                sb.append(d.getRankNo() == null ? "?" : d.getRankNo())
                        .append(". ").append(d.getContent() == null ? "" : d.getContent());
            }
            return sb.toString();
        }
        if (q != null && QuestionType.isMatrix(q.getType())) {
            Map<String, String> byRow = new LinkedHashMap<>();
            for (AnswerDetail d : details) {
                if (d.getRowKey() != null) {
                    byRow.putIfAbsent(d.getRowKey(),
                            d.getContent() == null ? "" : d.getContent());
                }
            }
            List<String> configuredRows = MatrixConfig.parse(q.getMatrixRows(), "矩阵行标题");
            StringBuilder sb = new StringBuilder();
            for (String row : configuredRows) {
                if (!byRow.containsKey(row)) continue;
                if (sb.length() > 0) sb.append("；");
                sb.append(row).append("：").append(byRow.get(row));
            }
            // ★ 不在配置里的行（历史脏数据/作者后来删了行）必须追加，不能静默丢掉
            for (Map.Entry<String, String> e : byRow.entrySet()) {
                if (configuredRows.contains(e.getKey())) continue;
                if (sb.length() > 0) sb.append("；");
                sb.append(e.getKey()).append("：").append(e.getValue());
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (AnswerDetail d : details) {
            if (sb.length() > 0) sb.append("、");
            sb.append(d.getContent() == null ? "" : d.getContent());
        }
        return sb.toString();
    }

    /** 某题的正确选项原文（按选项顺序）。没有标正确答案时返回空列表。 */
    public static List<String> correctAnswers(List<Option> options) {
        List<String> list = new ArrayList<>();
        if (options == null) return list;
        for (Option o : options) {
            if (o != null && o.getIsCorrect() != null && o.getIsCorrect() == 1
                    && o.getContent() != null) {
                list.add(o.getContent());
            }
        }
        return list;
    }
}
