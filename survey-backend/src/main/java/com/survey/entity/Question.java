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
 *   t_question 表里只有下面那 6 个字段。List&lt;Option&gt; options 是纯粹的"传输字段"：
 *     - 前端保存时把选项数组一起塞在每道题里（POST /api/question/save/{id}）；
 *     - 后端查询时由 QuestionService.getQuestionsBySurveyId() 逐题查出选项再塞回来。
 *   所以它只在 type=1（单选）/ 2（多选）时有值，填空题（type=3）为 null。
 */
public class Question {
    private Integer id;
    private Integer surveyId;      // 所属问卷 ID
    private Integer type;          // 题型：1=单选，2=多选，3=填空
    private String title;          // 题目内容
    private Integer required;      // 是否必填：0=否，1=是
    private Integer sortOrder;     // 排序。★ 它决定了"逻辑跳转里那个 1 基序号"的含义

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

    public List<Option> getOptions() { return options; }
    public void setOptions(List<Option> options) { this.options = options; }
}