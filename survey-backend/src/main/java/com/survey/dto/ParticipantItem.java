package com.survey.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 公开报名名单的一项（GET /api/answer/participants/{shareCode}）。
 * ★ 只返回「称呼 + 提交时间 + 签到状态」，手机号等其它答案一律不出 —— 公开名单是给人接龙看的，不是公开整份答卷。
 * 称呼取问卷的第一道单行文本题，没有则「匿名」；最多前 50 位。
 *
 * @param seq        第几位（1 起，按提交时间正序 = 接龙顺序）
 * @param name       称呼（第一道单行文本题的回答；没有则「匿名」）
 * @param submitTime 报名时间
 * @param checkedIn  1 = 已签到 / 0 = 未签到（问卷未启用签到时恒为 0）
 */
public record ParticipantItem(
        int seq,
        String name,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime submitTime,
        int checkedIn) {
}
