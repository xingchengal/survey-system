package com.survey.dto;

/**
 * 保存题目后回显的「题目 id 一览」：提交数组第 i 项的真实 id / sortOrder 就是本列表第 i 项。
 * 前端靠这个映射把编辑器里的临时负数 id 换成真 id —— 跳转目标只能存数据库里的题目 id。
 * ★ 只回显 id 与 sortOrder：跳转目标是题目而不是选项，整份回显在 200 题 × 50 选项时可达几 MB。
 */
public class QuestionIdItem {

    /** 题目在数据库里的真实 id（新建题也能拿到，来自自增主键回填） */
    private Integer id;

    /** 保存后的排序号（从 0 开始，与提交数组顺序一致） */
    private Integer sortOrder;

    public QuestionIdItem() {
    }

    public QuestionIdItem(Integer id, Integer sortOrder) {
        this.id = id;
        this.sortOrder = sortOrder;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
