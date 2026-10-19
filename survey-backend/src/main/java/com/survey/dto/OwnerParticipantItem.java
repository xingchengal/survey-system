package com.survey.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 作者端报名 / 签到名单的一项（GET /api/answer/participants/manage/{surveyId}）。
 * ★ 与公开名单的区别：带签到状态与签到时间，且不要求作者开启「公开报名名单」——这是作者看自己的数据。
 * ★ 权限是问卷归属（作者 / 管理员），不是公开接口。
 */
public record OwnerParticipantItem(
        int seq,
        Integer answerId,
        String name,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime submitTime,
        int checkedIn,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime checkedInAt) {
}
