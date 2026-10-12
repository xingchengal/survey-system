package com.survey.dto;

/**
 * 保存题目后回显的「题目 id 一览」。
 *
 * ── 为什么需要它 ──────────────────────────────────────
 *   新建的题在保存前没有数据库 id，而问卷编辑器的"跳转目标"必须存**真实的题目 id**。
 *   如果保存接口什么都不回显，前端就只能在保存之后另外查一次题目列表来猜"哪个 id 是哪道题"
 *   （按下标猜，题目数量或顺序一变就错位）。
 *
 *   所以保存接口返回**按下标一一对应**的 id 列表：前端提交的题目数组里第 i 项，
 *   对应的真实 id 就是本列表第 i 项的 {@code id}。
 *
 * ★ 只回显 id 与 sortOrder（不整份题目回显）：跳转目标指向的永远是"题目"而不是"选项"，
 *   所以前端只需要"题 → id"的映射；整份回显在极端情况下（200 题 × 50 选项）会有几 MB，
 *   而这个列表最多 200 项。
 */
public class QuestionIdItem {

    /** 题目在数据库里的真实 id（新建的题也能拿到，来自自增主键回填）。 */
    private Integer id;

    /** 保存后的排序号（从 0 开始，与提交数组的顺序一致）。 */
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
