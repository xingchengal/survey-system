package com.survey.common;

import java.util.List;

/**
 * 通用分页返回结构 —— 全项目所有分页接口的统一响应体。
 * ★ 比 PageInfo 精简：只留 total / list / pageNum / pageSize，前端不用解析导航页码数组。
 * @param <T> 列表元素类型（Survey / User / ErrorLog ...）
 */
public class PageResult<T> {
    private long total;      // 总记录数（不是当前页条数）
    private List<T> list;    // 当前页数据
    private int pageNum;
    private int pageSize;

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
