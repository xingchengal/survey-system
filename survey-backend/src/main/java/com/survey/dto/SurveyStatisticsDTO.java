package com.survey.dto;

import com.survey.entity.Question;

import java.util.List;
import java.util.Map;

/**
 * 整份问卷的统计结果（统计页专用，一次取回）。
 *
 * ── 它替代了什么 ──────────────────────────────────────
 *   改造前统计页要发 `1（问卷详情）+ 1（答卷数）+ 1（题目列表）+ N（逐题统计/文本）` 次请求，
 *   而且是 **for + await 串行**：N 道题就要等 N 个来回，延迟直接叠加。
 *   有了这个接口，页面只发 **1** 次请求。
 *
 * ── 为什么用 record ───────────────────────────────────
 *   纯数据载体、无行为、无框架注解，record 最省代码也最不容易写错
 *   （项目里 `RateLimitInterceptor.Rule` 已经是 record 的先例）。
 *   ★ Jackson 2.15+ 原生支持 record 序列化，不需要额外配置。
 *
 * @param surveyId    问卷 id
 * @param title       问卷标题（统计页顶部用，省掉一次 /api/survey/{id} 请求）
 * @param answerCount 答卷份数（走缓存，不是每次 COUNT）
 * @param questions   逐题统计，顺序与问卷题目顺序一致
 */
public record SurveyStatisticsDTO(
        Integer surveyId,
        String title,
        int answerCount,
        List<QuestionStats> questions,
        ScoreSummary scoreSummary) {

    /**
     * 判分摘要（只有开启了考试模式的问卷才有值，其它问卷为 null）。
     *
     * ★ 只统计 {@code earned_score IS NOT NULL} 的答卷：普通问卷与判分上线前的历史答卷
     *   不能被算成"考了 0 分"，否则平均分会被历史数据拉垮。
     *
     * @param gradedCount 已判分的答卷份数
     * @param avgScore    平均分（保留一位小数；gradedCount=0 时为 null）
     * @param passRate    及格率百分比（保留一位小数，如 66.7；未设及格线时为 null）
     */
    public record ScoreSummary(int gradedCount, Double avgScore, Double passRate) {
    }

    /**
     * 单题的统计结果。
     *
     * ★ `chartData` 与 `textAnswers` **互斥**：选择题给前者（填空题那条为 null），
     *   填空题给后者。这样前端可以按题型直接取用，不需要自己判断"该去哪个字段"。
     *
     * @param question    题目本体（id / type / title / sortOrder；**不含 options** —— 统计页不渲染选项文本）
     * @param chartData   选择题的选项分布 `[{content, count}]`；填空题恒为 null
     * @param textAnswers 填空题的文本列表（每题最多 2000 条）；选择题恒为 null
     */
    public record QuestionStats(
            Question question,
            List<Map<String, Object>> chartData,
            List<String> textAnswers) {
    }

    /**
     * 公开结果页用的**脱敏副本**：丢掉所有填空题/多行文本的原文。
     *
     * ★ 为什么必须脱敏：填空题里经常有手机号、姓名、住址。作者开启"公开结果"时
     *   想公开的是**汇总分布**，不是每一份原始回答。选项分布本身不含个人信息，保留。
     */
    public SurveyStatisticsDTO withoutTextAnswers() {
        List<QuestionStats> sanitized = questions == null
                ? List.of()
                : questions.stream()
                        .map(q -> new QuestionStats(q.question(), q.chartData(), null))
                        .collect(java.util.stream.Collectors.toList());
        // ★ scoreSummary 原样保留：平均分/及格率是**聚合值**，不含任何个人信息，公开结果页可以展示。
        return new SurveyStatisticsDTO(surveyId, title, answerCount, sanitized, scoreSummary);
    }
}
