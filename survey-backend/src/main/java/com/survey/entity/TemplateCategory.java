package com.survey.entity;

import java.time.LocalDateTime;

/**
 * 模板分类 —— 表 t_template_category，独立可维护的分类清单。
 * t_survey.template_category 仍是「这份模板属于哪个分类」的唯一事实；本表只决定有哪些分类可选及展示顺序。
 * ★ name 有唯一键（uk_template_category_name），新增同名会失败；
 *   SurveyService.setTemplate 会把新分类自动登记到本表，无需前端先调新增接口。
 */
public class TemplateCategory {

    private Integer id;

    /** 分类名（≤50 字，全表唯一） */
    private String name;

    /** 展示顺序：小的在前；新增分类追加到末尾 */
    private Integer sortOrder;

    private LocalDateTime createdTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
