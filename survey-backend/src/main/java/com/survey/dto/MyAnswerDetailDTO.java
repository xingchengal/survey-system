package com.survey.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「我的成绩 → 查看详情」的返回体：一份答卷的逐题回放。
 * 按答卷归属校验（answer.userId == 当前用户），不复用作者侧 /answer/detail/{id}（那条要求问卷归属）。
 * totalScore 是提交时快照；earnedScore 未判分为 null；passed 为 null = 未设及格线或未判分。
 * ★ correctAnswers 仅当作者开启「提交后显示答案」才有值，否则恒为 null —— 成绩页不能变成泄题入口。
 * questions 顺序同问卷题目，含未作答的题。
 */
public record MyAnswerDetailDTO(
        Integer answerId,
        Integer surveyId,
        String surveyTitle,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime submitTime,
        Integer totalScore,
        Integer earnedScore,
        Integer passScore,
        Integer passed,
        List<QuestionDetail> questions) {

    /**
     * 单题回放：answer 已按题型渲染成可读文本（未作答为"未作答"）；type 供前端决定特殊展示（矩阵 / 排序）。
     * correct 为 null = 未判分或不计分题；correctAnswers 为 null = 未开启"提交后显示答案"。
     */
    public record QuestionDetail(
            Integer questionId,
            String title,
            Integer type,
            String answer,
            Boolean correct,
            List<String> correctAnswers) {
    }
}
