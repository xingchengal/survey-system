package com.survey.dto;

import java.util.List;

/**
 * 两个版本之间的结构化差异（前端可按段渲染）。
 * ★ 按 questionId 对齐而不是行号：saveQuestions 按 id 保留/更新，题目 id 跨版本稳定；按行号会把"中间插一题"渲染成"后面每题都变了"。
 * 纯顺序变化归入 modified，changes 写「位置：第 2 题 → 第 4 题」，不单列 moved（多一个分类反而难读）。
 * added/removed/modified：目标版本有而旧版没有 / 旧版有而目标没有 / 两边都有但内容·选项·顺序不同。
 */
public record SurveyVersionDiff(
        Integer fromVersionId,
        Integer toVersionId,
        Integer fromVersionNo,
        Integer toVersionNo,
        String titleFrom,
        String titleTo,
        boolean titleChanged,
        List<QuestionChange> added,
        List<QuestionChange> removed,
        List<QuestionChange> modified) {

    /** 单题差异：title 用目标版本题干（删除的题用起始版本）；changes 是人类可读的变更点，如 ["题干修改", "选项变化"] */
    public record QuestionChange(Integer questionId, String title, List<String> changes) {
    }
}
