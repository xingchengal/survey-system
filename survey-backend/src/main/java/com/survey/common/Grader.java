package com.survey.common;

import com.survey.entity.AnswerDetail;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 考试判分的**唯一口径** —— 与 {@link QuestionType} / {@link TextLimits} 同级。
 *
 * ── 谁在用它 ────────────────────────────────────────────
 *   · {@code AnswerService.submit}：提交时算分，结果随 t_answer 一起落库；
 *   · {@code SurveyService.normalizeSurveySettings}：保存设置时算"本卷总分"，用于校验及格分；
 *   · {@code QuestionService.doSaveQuestions}：保存题目时校验"能不能设分值"。
 *   判分规则**只允许写在这里**，别在 Controller 或前端各写一份 —— 考试分数算错一次就无法解释。
 *
 * ── 规则（默认方案，2026-10-19 用户确认）──────────────
 *   单选(1) / 下拉(5) : 提交的那一项 == 唯一正确答案（按 content 去空白后比较）→ 得该题分值；
 *   多选(2)           : 提交集合与正确答案集合**完全一致** → 得分；错选/漏选/多选一律 0 分。
 *     ★ 有意**不做**部分得分：按比例给分会让"全选"策略稳定拿分，对考生不公平，口径也难解释。
 *   其它题型           : 不参与（保存时已拒绝给它们设分值，这里再防御性跳过）。
 *
 * ── 两个必须知道的限制 ──────────────────────────────────
 *   ① {@code t_answer_detail} 没有 option_id，只有选项原文 content —— 所以只能按文字比对。
 *      考试问卷发布后改选项文字会影响**之后**提交的判分（已存的 earned_score 不变）。
 *   ② 分值不重算：提交时把 total/earned 一起写进 t_answer，作者事后改分值不影响历史答卷。
 *      考试成绩不能因为"今天改了分值"而变化。
 */
public final class Grader {

    /** 单题分值上限。200 题 × 100 分 = 2 万分，足够任何校内考试，也挡住了"塞个 int 最大值"的脏数据。 */
    public static final int MAX_QUESTION_SCORE = 100;

    private Grader() {
    }

    /**
     * 判分结果。
     *
     * @param totalScore  本卷总分（所有 &gt;0 的题目分值之和）
     * @param earnedScore 本次得分
     * @param passed      是否及格：null = 未设及格线；0 = 不及格；1 = 及格
     */
    public record Result(int totalScore, int earnedScore, Integer passed) {
    }

    /**
     * 这道题的题型能不能设分值 / 参与判分。
     * ★ 只有 单选 / 多选 / 下拉 —— 判断题用"单选 2 选项"承载，不单独占一个题型（见方案文档）。
     */
    public static boolean isGradable(Integer type) {
        return type != null && (type == QuestionType.SINGLE.code()
                || type == QuestionType.MULTI.code()
                || type == QuestionType.SELECT.code());
    }

    /**
     * 本卷总分 = 所有**可判分题型**里 {@code score > 0} 的分值之和。
     * ★ 只算单选/多选/下拉：不可判分的题型即使被设了分值（保存时本应拒绝）也不能进总分，
     *   否则"总分 100、实际只有 90 分可判"会让每个人看起来都差 10 分。
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
     * 算分。
     *
     * @param survey           问卷（取 gradeEnabled / passScore；为 null 时按"不判分"处理）
     * @param questions        本问卷全部题目
     * @param optionsByQuestion 题目 id → 选项（**只需传可判分题的选项**；矩阵/排序不需要）
     * @param details          本次提交的明细
     * @return null = 这份问卷没开考试模式（调用方不要把分数写库）；否则返回结果
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
            // 防御：正常路径下 SurveyService 开启考试模式时就要求总分 > 0（QuestionService 保存时也会拦），
            // 这里兜住"历史数据里开关开着但题目分值被清空"的脏状态 —— 不抛异常，按 0/0 记录，
            // 至少不会让提交直接 500。
            return new Result(0, 0, null);
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
        }

        Integer passed = null;
        if (survey.getPassScore() != null) {
            // 等于及格分算及格（>= 而不是 >）
            passed = earned >= survey.getPassScore() ? 1 : 0;
        }
        return new Result(total, earned, passed);
    }
}
