package com.survey.entity;

import java.util.List;

/**
 * 题目实体 —— 表 t_question。
 * ★ options 不是数据库列：保存时随题目一起传入、查询时由 QuestionService 回填，
 *   只有 {@link com.survey.common.QuestionType#hasOptions} 为真的题型有值，其它恒为 null。
 * matrixRows / matrixCols 为数据库列（JSON 数组文本，仅 type=8）；MyBatis 无内置 JSON ↔ List 映射，故存字符串。
 */
public class Question {
    private Integer id;
    private Integer surveyId;      // 所属问卷 ID
    private Integer type;          // 题型：见 common/QuestionType（1~10）
    private String title;          // 题目内容
    private Integer required;      // 是否必填：0=否，1=是
    private Integer sortOrder;     // 排序；逻辑跳转里的 1 基序号按它编号
    private String matrixRows;     // 矩阵题行标题（JSON 数组文本），仅 type=8
    private String matrixCols;     // 矩阵题列标题（JSON 数组文本），仅 type=8

    /** 分值：0 = 不判分（默认），&gt;0 = 考试模式计分。
     * ★ 只有单选(1)/多选(2)/下拉(5) 允许 &gt;0（保存时校验）；总分 = 所有 &gt;0 的分值之和。 */
    private Integer score;

    // 前端传入的选项列表（非数据库字段，见类注释）
    private List<Option> options;

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