package com.survey.common;

import java.util.List;

/**
 * 通用分页返回结构 —— 全项目所有分页接口的统一响应体。
 *
 * ── 为什么不直接返回 PageHelper 的 PageInfo ────────────
 *   PageInfo 有十几个字段（还有导航页码数组等），前端用不上；
 *   这里精简成 4 个字段，前端解构起来更直观。
 *
 * ── 谁在构造它 ────────────────────────────────────────
 *   SurveyService / UserService / ErrorLogService / OperationLogService 的各个 pageXxx 方法，
 *   套路都是：PageHelper.startPage(...) → 查询 → new PageInfo(list) → new PageResult<>(...)
 *
 * ── 前端怎么用 ────────────────────────────────────────
 *   res.total 总条数（分页组件用）、res.list 当前页数据、res.pageNum / res.pageSize 回显。
 *
 * @param <T> 列表里的元素类型（Survey / User / ErrorLog / OperationLog ...）
 */
public class PageResult<T> {
    private long total;      // 总记录数（不是当前页条数）
    private List<T> list;    // 当前页数据
    private int pageNum;     // 当前页码（从 1 开始）
    private int pageSize;    // 每页大小

    /** 无参构造：JSON 反序列化需要（本项目里主要是给 Jackson 用）。 */
    public PageResult() {}

    /** 全参构造：各 Service 的 pageXxx 方法用它组装结果。 */
    public PageResult(long total, List<T> list, int pageNum, int pageSize) {
        this.total = total;
        this.list = list;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }

    public List<T> getList() { return list; }
    public void setList(List<T> list) { this.list = list; }

    public int getPageNum() { return pageNum; }
    public void setPageNum(int pageNum) { this.pageNum = pageNum; }

    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
