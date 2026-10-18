package com.survey.dto;

import com.survey.common.Grader;

import java.util.List;

/**
 * 提交答卷的返回值。
 *
 * ── 为什么要从 String 换成对象 ──────────────────────────
 *   判分上线前两个提交端点返回裸字符串 {@code "提交成功"}，前端只用到状态码。
 *   现在要把"这次的分数"带回给答题页做成绩卡，只能把返回体扩成对象。
 *   ★ 普通问卷（gradeEnabled=0）返回 {@code graded=false} 且分数全为 null ——
 *     前端据此照旧显示感谢语，行为与改造前完全一致。
 *
 * @param message    给用户看的一句话（与改造前文案保持一致："提交成功"）
 * @param graded     本次是否判分（false = 普通问卷）
 * @param totalScore 本卷总分（graded=false 时为 null）
 * @param earnedScore 本次得分（graded=false 时为 null）
 * @param passScore  及格分（问卷没设及格线时为 null）
 * @param passed     是否及格：null = 未设及格线；0/1 为判定结果
 * @param questions  逐题结果：**只有作者开启"提交后显示答案"时才有值**，否则为 null
 *                   （成绩卡默认只显示分数，不泄题）；普通问卷同样为 null
 */
public record SubmitResult(
        String message,
        boolean graded,
        Integer totalScore,
        Integer earnedScore,
        Integer passScore,
        Integer passed,
        List<Grader.QuestionResult> questions) {

    /** 普通问卷（不判分）的返回体。 */
    public static SubmitResult notGraded(String message) {
        return new SubmitResult(message, false, null, null, null, null, null);
    }

    /**
     * 由判分结果组装（{@code result} 为 null 时退化成普通问卷）。
     *
     * @param showAnswers 作者是否开启"提交后显示答案" —— 只有 true 才把逐题结果带出去
     */
    public static SubmitResult of(String message, Grader.Result result, Integer passScore,
                                  boolean showAnswers) {
        if (result == null) return notGraded(message);
        return new SubmitResult(message, true, result.totalScore(), result.earnedScore(),
                passScore, result.passed(), showAnswers ? result.questions() : null);
    }
}
