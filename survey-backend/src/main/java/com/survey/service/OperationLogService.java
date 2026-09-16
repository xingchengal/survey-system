package com.survey.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.PageResult;
import com.survey.entity.OperationLog;
import com.survey.mapper.OperationLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OperationLogService {

    @Autowired
    private OperationLogMapper operationLogMapper;

    // 异步保存，避免阻塞主流程
    @Async
    public void save(OperationLog log) {
        operationLogMapper.insert(log);
    }

    // 分页查询
    public PageResult<OperationLog> page(int pageNum, int pageSize, String username, String operation) {
        PageHelper.startPage(pageNum, pageSize);
        List<OperationLog> list = operationLogMapper.findPageByCondition(username, operation);
        PageInfo<OperationLog> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(), pageNum, pageSize);
    }
}