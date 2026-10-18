package com.survey.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 「我的成绩」—— 登录用户查自己在一份考试问卷里的成绩（GET /api/answer/score/{surveyId}）。
 *
 * ★ 只返回**自己的**答卷：surveyId + 登录用户 id 共同定位，不接受 answerId（否则要额外做归属校验）。
 * ★ 匿名答卷（user_id = NULL）查不到，返回 400「你还没有提交过这份问卷」——
 *   匿名考生只在提交那一刻能看到分数（返回体里），之后没有身份可以回溯。
 *
 * @param surveyId    问卷 id
 * @param title       问卷标题（省掉前端再查一次问卷）
 * @param totalScore  本卷总分（提交时的快照）
 * @param earnedScore 得分
 * @param passScore   及格分（没设为 null）
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
