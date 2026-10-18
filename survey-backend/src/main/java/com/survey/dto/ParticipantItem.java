package com.survey.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 报名名单的一项（公开接口 GET /api/answer/participants/{shareCode}）。
 *
 * ★ 只暴露**称呼与提交时间**，不暴露手机号等其它答案 —— 公开名单是"接龙给人看"，
 *   不是把整份答卷公开。
 * ★ 称呼取"问卷的第一道单行文本题"（接龙/报名模板的第一题就是姓名）；
 *   取不到时显示"匿名"。
 *
 * @param seq        第几位（1 起，按提交时间正序 = 接龙顺序）
 * @param name       称呼（姓名题的回答；没有则"匿名"）
 * @param submitTime 报名时间
 */
public record ParticipantItem(
        int seq,
        String name,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime submitTime) {
}
