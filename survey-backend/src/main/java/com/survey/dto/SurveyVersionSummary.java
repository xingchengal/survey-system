package com.survey.dto;

/**
 * 版本列表项（**刻意不含 `questionsJson`**）。
 *
 * 版本列表动辄几十条，而单条 JSON 在极端问卷下是 MB 级 —— 列表只返回展示需要的字段。
 * 要拿某一版的完整内容，走 `GET /api/survey/version/{versionId}`。
 *
 * @param id            版本 id（回滚/对比用它，不是 versionNo）
 * @param versionNo     版本号（人看的，如 v3）
 * @param title         快照时的问卷标题
 * @param questionCount 当时的题目数
 * @param changeSummary 相对上一版的变更摘要（自动生成）
 * @param createdBy     操作人 id
 * @param createdTime   生成时间（已格式化为 yyyy-MM-dd HH:mm:ss）
 */
public record SurveyVersionSummary(
        Integer id,
        Integer versionNo,
        String title,
        Integer questionCount,
        String changeSummary,
        Integer createdBy,
        String createdTime) {
}
