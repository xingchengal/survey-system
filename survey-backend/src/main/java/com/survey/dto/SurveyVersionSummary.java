package com.survey.dto;

/**
 * 版本列表项 —— 刻意不含 questionsJson：列表动辄几十条，单条快照 JSON 在极端问卷下可达 MB 级。
 * 列表只返回版本号 / 时间 / 变更摘要等展示字段；要某一版完整内容走 GET /api/survey/version/{versionId}。
 *
 * @param id            版本 id（回滚/对比用它，不是 versionNo）
 * @param versionNo     版本号（人看的，如 v3）
 * @param title         该版本的问卷标题
 * @param questionCount 该版本的题目数
 * @param changeSummary 相对上一版的变更摘要（自动生成）
 * @param createdBy     操作人 id
 * @param createdTime   生成时间（yyyy-MM-dd HH:mm:ss）
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
