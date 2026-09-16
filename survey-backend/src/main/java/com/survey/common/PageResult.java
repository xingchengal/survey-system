package com.survey.common;

import java.util.List;

/**
 * 通用分页返回结果
 */
public class PageResult<T> {
    private long total;      // 总记录数
    private List<T> list;    // 当前页数据
    private int pageNum;     // 当前页码
    private int pageSize;    // 每页大小

    public PageResult() {}

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