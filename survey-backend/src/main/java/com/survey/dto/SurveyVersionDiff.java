package com.survey.dto;

import java.util.List;

/**
 * 两个版本之间的差异（结构化，前端可直接按段渲染）。
 *
 * ── 为什么按 questionId 对齐，而不是"按行号比较" ────────────
 *   `QuestionService.saveQuestions` 是"按 id 保留/更新"（不是全删重建），所以同一道题的
 *   id 在两个版本里是**稳定**的；按 id 对齐才能区分"这道题被改了"和"这道题被删了、另一道新增了"。
 *   按行号比较会把"中间插入一道题"渲染成"后面每一题都变了"。
 *
 * ── 只有顺序变化怎么表示 ────────────────────────────────
 *   题目内容没变、只是拖动了位置 ⇒ 归入 `modified`，`changes` 里写「位置：第 2 题 → 第 4 题」。
 *   不单列 `moved`：对用户来说"这题变了"和"这题挪了"在 UI 上可以混排，多一个分类反而难读。
 *
 * @param fromVersionNo 起始版本号（旧）
 * @param toVersionNo   目标版本号（新）
 * @param titleFrom     起始版本时的标题
 * @param titleTo       目标版本时的标题
 * @param titleChanged  标题是否变了（前端据此决定要不要显示标题那一行）
 * @param added         新增的题目（在目标版本有、起始版本没有）
 * @param removed       删除的题目（在起始版本有、目标版本没有）
 * @param modified      修改过的题目（两边都有，但内容/选项/顺序不同）
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

    /**
     * 单题的差异。
     *
     * @param questionId 题目 id
     * @param title      题目在**目标版本**里的题干（删除的题用起始版本的题干）
     * @param changes    人类可读的变更点列表（如 ["题干修改", "选项变化", "位置：第 2 题 → 第 4 题"]）
     */
    public record QuestionChange(Integer questionId, String title, List<String> changes) {
    }
}
