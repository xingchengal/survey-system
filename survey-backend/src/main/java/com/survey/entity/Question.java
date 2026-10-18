package com.survey.entity;

import java.util.List;

/**
 * 题目实体 —— 对应表 t_question。
 *
 * ── 谁在用 ────────────────────────────────────────────
 *   QuestionController（保存/查询）、QuestionService（全量覆盖保存）、
 *   SurveyEdit.vue（编辑器里的题目列表）、SurveyFillByCode.vue（答题页）
 *
 * ── ★ options 不是数据库列 ─────────────────────────────
 *   List&lt;Option&gt; options 是纯粹的"传输字段"：
 *     - 前端保存时把选项数组一起塞在每道题里（POST /api/question/save/{id}）；
 *     - 后端查询时由 QuestionService.getQuestionsBySurveyId() 逐题查出选项再塞回来。
 *   所以它只在 {@link com.survey.common.QuestionType#hasOptions} 为真的题型（单选/多选/下拉/排序）
 *   时有值，文本/评分/日期/NPS/矩阵为 null。
 *
 * ── ★ 矩阵题的 matrixRows / matrixCols ──────────────────
 *   这两列是**数据库列**（t_question.matrix_rows / matrix_cols），存 JSON 字符串数组，
 *   只有 type=8（矩阵）有值，其它题型的列恒为 NULL。
 *   ★ 为什么用字符串而不是 List：MyBatis 没有内置 JSON ↔ List 映射，
 *     用字符串 + {@link com.survey.common.MatrixConfig} 解析/校验，比引入 TypeHandler 更少魔法。
 */
public class Question {
    private Integer id;
    private Integer surveyId;      // 所属问卷 ID
    private Integer type;          // 题型：见 common/QuestionType（1~10）
    private String title;          // 题目内容
    private Integer required;      // 是否必填：0=否，1=是
    private Integer sortOrder;     // 排序。★ 它决定了"逻辑跳转里那个 1 基序号"的含义
    private String matrixRows;     // 矩阵题行标题（JSON 数组文本），仅 type=8
    private String matrixCols;     // 矩阵题列标题（JSON 数组文本），仅 type=8

    /**
     * 分值：0 = 不参与判分（默认）；&gt;0 表示这道题在考试模式下计分。
     * ★ 只有单选(1)/多选(2)/下拉(5) 允许 &gt;0 —— 保存时校验（QuestionService.doSaveQuestions）。
     * ★ 判分口径见 {@link com.survey.common.Grader}；总分 = 本问卷所有 &gt;0 的分值之和。
     */
    private Integer score;

    // 关键：用来接收前端传过来的选项列表（非数据库字段，见类注释）
    private List<Option> options;

    // ===== Getter/Setter =====
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getSurveyId() { return surveyId; }
    public void setSurveyId(Integer surveyId) { this.surveyId = surveyId; }

    public Integer getType() { return type; }
    public void setType(Integer type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Integer getRequired() { return required; }
    public void setRequired(Integer required) { this.required = required; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public String getMatrixRows() { return matrixRows; }
    public void setMatrixRows(String matrixRows) { this.matrixRows = matrixRows; }

    public String getMatrixCols() { return matrixCols; }
    public void setMatrixCols(String matrixCols) { this.matrixCols = matrixCols; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public List<Option> getOptions() { return options; }
    public void setOptions(List<Option> options) { this.options = options; }
}