package com.survey.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.PageResult;
import com.survey.entity.ErrorLog;
import com.survey.mapper.ErrorLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ErrorLogService {

    @Autowired
    private ErrorLogMapper errorLogMapper;

    /**
     * 异步记录异常（避免阻塞主流程）
     */
    @Async
    public void save(ErrorLog errorLog) {
        try {
            errorLogMapper.insert(errorLog);
        } catch (Exception e) {
            // 记录日志失败不能影响业务
            System.err.println("保存异常日志失败：" + e.getMessage());
        }
    }

    public PageResult<ErrorLog> page(int pageNum, int pageSize, String keyword) {
        PageHelper.startPage(pageNum, pageSize);
        List<ErrorLog> list = errorLogMapper.findPageByCondition(keyword);
        PageInfo<ErrorLog> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(), pageNum, pageSize);
    }

    public int count() {
        return errorLogMapper.count();
    }

    public int delete(Integer id) {
        return errorLogMapper.deleteById(id);
    }

    public int clearAll() {
        return errorLogMapper.clearAll();
    }
}