package com.survey.dto;

import com.survey.entity.Question;

import java.util.List;
import java.util.Map;

/**
 * 整份问卷的统计结果 —— 统计页 1 次请求取回，不再逐题串行请求。
 * surveyId/title 问卷头部；answerCount 答卷份数（走缓存，不是每次 COUNT）；checkinEnabled 是否启用签到。
 * questions 逐题统计，顺序同问卷题目；scoreSummary 判分摘要，非考试模式为 null。
 * ★ chartData 与 textAnswers 互斥：选择题给前者、填空题给后者，前端不必自己判断该读哪个字段。
 */
public record SurveyStatisticsDTO(
        Integer surveyId,
        String title,
        int answerCount,
        List<QuestionStats> questions,
        ScoreSummary scoreSummary,
        boolean checkinEnabled) {

    /**
     * 判分摘要（只有开启考试模式的问卷才有值，其它为 null）。
     * 只统计 earned_score 非空的答卷：未判分不能算成 0 分，否则平均分被拉垮。
     * gradedCount 已判分份数；avgScore 平均分（保留一位小数，0 份时为 null）；passRate 及格率百分比（未设及格线时为 null）。
     */
    public record ScoreSummary(int gradedCount, Double avgScore, Double passRate) {
    }

    /**
     * 单题统计：question 不含 options —— 统计页不渲染选项文本。
     * chartData 为选择题的选项分布 [{content, count}]；textAnswers 为填空题原文（每题最多 2000 条）。
     */
    public record QuestionStats(
            Question question,
            List<Map<String, Object>> chartData,
            List<String> textAnswers) {
    }

    /**
     * 公开结果页用的脱敏副本：丢掉全部填空 / 多行文本原文，选项分布与聚合分保留。
     * ★ 填空题常含手机号、姓名、住址：公开的是汇总分布，不是每一份原始回答。
     */
    public SurveyStatisticsDTO withoutTextAnswers() {
        List<QuestionStats> sanitized = questions == null
                ? List.of()
                : questions.stream()
                        .map(q -> new QuestionStats(q.question(), q.chartData(), null))
                        .collect(java.util.stream.Collectors.toList());
        return new SurveyStatisticsDTO(surveyId, title, answerCount, sanitized, scoreSummary, checkinEnabled);
    }
}
