package com.survey.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「我的成绩 → 查看详情」的返回体：一份答卷的逐题回放。
 *
 * ── 为什么要单独一个端点 ────────────────────────────────
 *   作者侧的 {@code GET /answer/detail/{answerId}} 要求"问卷归属"，
 *   答题人不是作者，不能复用；这里按 **答卷归属** 校验（answer.userId == 当前用户）。
 *
 * ── 隐私口径（与交卷成绩卡一致）────────────────────────
 *   · {@code correct}：该题对错。只有**计分题**才有值（null = 未判分/不参与判分）；
 *   · {@code correctAnswers}：正确答案。**只有作者开启「提交后显示答案」时才有值**，
 *     否则一律 null —— 成绩页不能变成泄题入口。
 *
 * @param answerId    答卷 id
 * @param surveyId    问卷 id
 * @param surveyTitle 问卷标题
 * @param submitTime  提交时间
 * @param totalScore  本卷总分（提交时快照）
 * @param earnedScore 得分（未判分为 null）
 * @param passScore   及格分（没设为 null）
 * @param passed      是否及格：null = 未设及格线或未判分
 * @param questions   逐题明细（顺序与问卷题目顺序一致，含未作答的题）
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
     * 单题回放。
     *
     * @param questionId     题目 id
     * @param title          题干
     * @param type           题型（前端按它决定要不要特殊展示，如矩阵/排序）
     * @param answer         我的答案（已按题型渲染成可读文本；未作答为"未作答"）
     * @param correct        是否答对：null = 未判分/不计分题
     * @param correctAnswers 正确答案原文；null = 未开启"提交后显示答案"
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
