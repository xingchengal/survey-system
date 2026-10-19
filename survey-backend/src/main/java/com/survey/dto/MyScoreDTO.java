package com.survey.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 「我的成绩」：登录用户查自己在一份判分问卷里的成绩。
 * ★ 只按 surveyId + 登录用户 id 定位，不接受 answerId（否则要多做归属校验）；匿名答卷查不到，400。
 * 未开判分（earned_score 为 NULL）同样 400；匿名考生只在提交那一刻能看到分数。
 *
 * @param surveyId    问卷 id
 * @param title       问卷标题（省掉前端再查一次问卷）
 * @param totalScore  本卷总分（提交时的快照）
 * @param earnedScore 得分
 * @param passScore   及格分（未设为 null）
 * @param passed      是否及格：null = 未设及格线
 * @param submittedAt 提交时间
 */
public record MyScoreDTO(
        Integer surveyId,
        String title,
        Integer totalScore,
        Integer earnedScore,
        Integer passScore,
        Integer passed,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime submittedAt) {
}
