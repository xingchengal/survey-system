package com.survey.common;

import com.survey.entity.AnswerDetail;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 考试判分的**唯一口径**：提交时算分并随 t_answer 落库，保存设置时也用它算本卷总分。
 * 单选 / 下拉按选项原文比对；多选要求集合完全一致（不设部分分，否则"全选"策略稳拿分）。
 * ★ 判分规则只允许写在这里，别在 Controller 或前端各写一份 —— 分数算错一次就无法解释。
 * ★ t_answer_detail 无 option_id，只能按原文比对；事后改选项文字或分值不影响已落库的历史答卷。
 */
public final class Grader {

    /** 单题分值上限：200 题 × 100 分足够校内考试，也挡住"塞个 int 最大值"的脏数据。 */
    public static final int MAX_QUESTION_SCORE = 100;

    private Grader() {
    }

    /**
     * 单题判分结果（成绩卡"逐题对错"的数据源）。
     *
     * @param correctAnswers 正确答案原文，按选项顺序；是否返回给答题人由 DTO 层控制
     */
    public record QuestionResult(Integer questionId, boolean correct, List<String> correctAnswers) {
    }

    /**
     * 判分结果。
     * @param totalScore  本卷总分（所有 &gt;0 的可判分题目分值之和）；earnedScore 为本次得分
     * @param passed      是否及格：null = 未设及格线；0 = 不及格；1 = 及格
     * @param questions   逐题结果（只含设了分值的题，顺序与题目一致；不判分时为空列表）
     */
    public record Result(int totalScore, int earnedScore, Integer passed, List<QuestionResult> questions) {
    }

    /** 该题型能不能设分值 / 参与判分：只有单选 / 多选 / 下拉（判断题用"单选 2 选项"承载）。 */
    public static boolean isGradable(Integer type) {
        return type != null && (type == QuestionType.SINGLE.code()
                || type == QuestionType.MULTI.code()
                || type == QuestionType.SELECT.code());
    }

    /**
     * 本卷总分 = 所有可判分题型里 {@code score > 0} 的分值之和。
     * 不可判分题型即使被误设分值也不能进总分，否则"总分 100、实际 90 可判"会让每个人看起来都差 10 分。
     */
    public static int totalScore(List<Question> questions) {
        if (questions == null) return 0;
        int total = 0;
        for (Question q : questions) {
            if (q == null || q.getScore() == null || q.getScore() <= 0) continue;
            if (!isGradable(q.getType())) continue;
            total += q.getScore();
        }
        return total;
    }

    /**
     * 算分；survey 为 null 或未开考试模式时返回 null（调用方不要把分数写库）。
     * @param optionsByQuestion 题目 id → 选项（只需传可判分题的选项；矩阵 / 排序不需要）
     */
    public static Result grade(Survey survey,
                               List<Question> questions,
                               Map<Integer, List<Option>> optionsByQuestion,
                               List<AnswerDetail> details) {
        if (survey == null || survey.getGradeEnabled() == null || survey.getGradeEnabled() != 1) {
            return null;
        }
        int total = totalScore(questions);
        if (total <= 0) {
            // 防御：开关开着但题目分值被清空的脏数据 —— 按 0/0 记录，不让提交直接 500
            return new Result(0, 0, null, List.of());
        }

        // 提交内容按题目归集：用 Set 去重（多选重复项不应被当成"多选了几个"）
        Map<Integer, Set<String>> submitted = new HashMap<>();
        if (details != null) {
            for (AnswerDetail d : details) {
                if (d == null || d.getQuestionId() == null) continue;
                String content = d.getContent();
                if (content == null || content.isBlank()) continue;   // 空白 = 未作答
                submitted.computeIfAbsent(d.getQuestionId(), k -> new LinkedHashSet<>())
                        .add(content.trim());
            }
        }

        int earned = 0;
        List<QuestionResult> perQuestion = new ArrayList<>();
        for (Question q : questions) {
            if (q == null || q.getScore() == null || q.getScore() <= 0) continue;
            if (!isGradable(q.getType())) continue;   // 防御：保存时已拒绝

            List<Option> options = optionsByQuestion == null
                    ? List.of()
                    : optionsByQuestion.getOrDefault(q.getId(), List.of());
            Set<String> correct = new LinkedHashSet<>();
            for (Option o : options) {
                if (o == null || o.getContent() == null) continue;
                if (o.getIsCorrect() != null && o.getIsCorrect() == 1) {
                    correct.add(o.getContent().trim());
                }
            }
            if (correct.isEmpty()) continue;   // 没有标正确答案的题不得分（保存时已拒绝）

            Set<String> got = submitted.getOrDefault(q.getId(), Set.of());
            boolean right;
            if (QuestionType.isSingle(q.getType())) {
                // 单选/下拉：必须恰好一项且等于正确答案
                right = got.size() == 1 && correct.size() == 1 && got.containsAll(correct);
            } else {
                // 多选：集合完全一致（全对才得分）
                right = !got.isEmpty() && got.size() == correct.size() && got.containsAll(correct);
            }
            if (right) earned += q.getScore();
            // 逐题结果含正确答案：成绩卡用它；是否返回给答题人由 DTO 层控制
            perQuestion.add(new QuestionResult(q.getId(), right, new ArrayList<>(correct)));
        }

        Integer passed = null;
        if (survey.getPassScore() != null) {
            // 等于及格分算及格（>= 而不是 >）
            passed = earned >= survey.getPassScore() ? 1 : 0;
        }
        return new Result(total, earned, passed, perQuestion);
    }
}
