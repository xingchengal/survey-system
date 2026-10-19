package com.survey.dto;

import com.survey.common.Grader;

import java.util.List;

/**
 * 提交答卷的判分结果返回体：成绩卡要的分数与逐题结果。
 * graded=false（普通问卷）时，totalScore / earnedScore / passScore / passed / questions 全为 null。
 * 不做部分得分：单选 / 下拉按选项原文比对，多选要求集合完全一致。
 * ★ questions 只在作者开启「提交后显示答案」时有值，否则为 null（默认不泄题）。
 *
 * @param message     给用户看的一句话（"提交成功"）
 * @param graded      本次是否判分（false = 普通问卷）
 * @param totalScore  本卷总分（所有设了分值的可判分题之和）
 * @param earnedScore 本次得分
 * @param passScore   及格分（问卷未设及格线时为 null）
 * @param passed      是否及格：null = 未设及格线，0/1 为判定结果
 * @param questions   逐题对错，见上
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
     * @param showAnswers 作者是否开启"提交后显示答案"：只有 true 才带出逐题结果
     */
    public static SubmitResult of(String message, Grader.Result result, Integer passScore,
                                  boolean showAnswers) {
        if (result == null) return notGraded(message);
        return new SubmitResult(message, true, result.totalScore(), result.earnedScore(),
                passScore, result.passed(), showAnswers ? result.questions() : null);
    }
}
