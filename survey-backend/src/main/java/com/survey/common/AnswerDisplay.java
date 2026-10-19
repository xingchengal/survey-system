package com.survey.common;

import com.survey.entity.AnswerDetail;
import com.survey.entity.Option;
import com.survey.entity.Question;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把"一道题的多行明细"渲染成**一行人类可读的文本** —— 全项目唯一渲染口径（Excel 导出与成绩详情共用）。
 * 多选 `A、B、C`；矩阵 `行：值；行：值`；排序 `1. A；2. B`；其余题型取原文。
 * ★ 不做任何转义：Excel 单元格与页面文本都按原样展示。
 */
public final class AnswerDisplay {

    private AnswerDisplay() {
    }

    /**
     * @param q       题目（需要 type / matrixRows；缺失时矩阵按存储顺序输出）
     * @param details 该题的全部明细行（可能为 null / 空）
     * @return 空明细返回空串
     */
    public static String format(Question q, List<AnswerDetail> details) {
        if (details == null || details.isEmpty()) {
            return "";
        }
        if (q != null && QuestionType.isRanking(q.getType())) {
            List<AnswerDetail> sorted = new ArrayList<>(details);
            // null 名次（历史脏数据）放最后
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
            // ★ 配置外的行（历史脏数据 / 作者删了行）也要追加，不能静默丢掉
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
