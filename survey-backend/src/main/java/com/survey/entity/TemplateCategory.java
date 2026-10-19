package com.survey.entity;

import java.time.LocalDateTime;

/**
 * 模板分类 —— 对应表 t_template_category。
 *
 * ── 为什么需要这张表 ────────────────────────────────────
 *   模板分类原来是两处各写一份：
 *     · 后端没有分类清单，只有 t_survey.template_category 这个**每份模板自己的**字段；
 *     · 前端 AdminSurveyList.vue 里硬编码 7 个分类，TemplateManage.vue 又只能从
 *       "现有模板里出现过哪些分类"反推。
 *   于是"想先建一个暂时还没有模板的分类"没有任何入口，新分类只能靠某份模板先用了它。
 *   本表把分类变成**独立可维护的清单**：模板管理页加「+」、设为模板时自动登记，都写到这里。
 *
 * ── 与 t_survey.template_category 的关系 ────────────────
 *   · t_survey 上那个字段仍是"这份模板属于哪个分类"的**唯一事实**（模板广场按它筛选）；
 *   · 本表只负责"有哪些分类可选 / 分类的展示顺序"，不反向约束 t_survey；
 *   · 为兼容历史数据，迁移脚本会把库里已出现过的 template_category 全部灌进来。
 *
 * ── 命名 ────────────────────────────────────────────────
 *   name 上有唯一键（uk_template_category_name），所以"新增同名分类"会失败。
 *   ★ 归类时为兼容旧行为仍允许 allow-create（现场输入新分类）；后端 SurveyService.setTemplate
 *     会把新分类自动登记到本表，不需要前端先调一次新增接口。
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
