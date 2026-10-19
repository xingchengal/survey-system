package com.survey.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.BizException;
import com.survey.common.DateRanges;
import com.survey.common.PageParams;
import com.survey.common.Role;
import com.survey.common.SqlLikeEscaper;
import com.survey.common.PageResult;
import com.survey.entity.OperationLog;
import com.survey.mapper.OperationLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 操作日志（t_operation_log）：异步落库 + 管理端查询 / 导出。
 * ★ 审计证据只读：不提供改单条 / 删单条，只允许 DB 层整表回收。
 * 筛选参数必须过 SqlLikeEscaper；导出上限 10000 行，超限明确拒绝、不静默截断。
 * save 是 @Async，LogAspect 侧也兜了 try-catch：日志写失败不影响业务。
 */
@Service
public class OperationLogService {

    private static final Logger LOG = LoggerFactory.getLogger(OperationLogService.class);

    /** 导出上限：导出会把全部行读进内存，超了明确拒绝（SQL 侧 LIMIT 10001 探测）。 */
    public static final int EXPORT_MAX_ROWS = 10_000;

    @Autowired
    private OperationLogMapper operationLogMapper;

    /**
     * 异步保存一条操作日志（LogAspect 在业务成功后调用）。
     * ★ 必须自己 try-catch：@Async 里逃逸的异常不会传回调用方，insert 失败时这次操作会静默从审计表消失
     *   （ip 取自可伪造的 X-Forwarded-For，超长会撑爆 varchar(50)）。
     */
    @Async
    public void save(OperationLog log) {
        try {
            operationLogMapper.insert(log);
        } catch (Exception e) {
            LOG.error("保存操作日志失败（该次操作将不出现在审计日志里）：user={} op={} —— {}",
                    log.getUserId(), log.getOperation(), e.getMessage(), e);
        }
    }

    /**
     * 分页查询操作日志（管理端）：用户名 / 操作名模糊，角色、时间范围筛选。
     * startPage 必须紧挨 mapper 查询；响应回显钳制后的页码 / 页大小。
     */
    public PageResult<OperationLog> page(int pageNum, int pageSize, String username, String operation,
                                         String role, String startDate, String endDate) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // LIKE 通配符必须转义：输入 % 会匹配所有行（筛选被静默绕过），输入 _ 会被当成任意字符
        username = SqlLikeEscaper.escape(username);
        operation = SqlLikeEscaper.escape(operation);
        List<OperationLog> list = operationLogMapper.findPageByCondition(username, operation,
                normalizeRoleFilter(role), DateRanges.startOfDay(startDate), DateRanges.endOfDay(endDate));
        PageInfo<OperationLog> pageInfo = new PageInfo<>(list);
        // 回显钳制后的值：回显原值会让前端分页组件把总页数 / 每页条数算错
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /**
     * 导出用的操作日志（不分页，最多 {@link #EXPORT_MAX_ROWS} 行）。
     * 筛选条件与 {@link #page} 完全一致；超过上限直接报错，不静默截断。
     */
    public List<OperationLog> exportLogs(String username, String operation,
                                         String role, String startDate, String endDate) {
        List<OperationLog> list = operationLogMapper.findForExport(
                SqlLikeEscaper.escape(username), SqlLikeEscaper.escape(operation),
                normalizeRoleFilter(role), DateRanges.startOfDay(startDate), DateRanges.endOfDay(endDate));
        if (list.size() > EXPORT_MAX_ROWS) {
            throw new BizException("导出结果超过 " + EXPORT_MAX_ROWS
                    + " 条日志，请缩小时间范围后再导出（导出的每一行都要进内存，不设上限会拖垮服务）");
        }
        return list;
    }

    /**
     * 角色筛选归一：只放行 admin / user，其余（含空串与非法值）当作"全部"。
     * 与 UserService.normalizeRoleFilter 同一口径，各留一份是因为入参来源不同。
     */
    private static String normalizeRoleFilter(String role) {
        if (role == null) return null;
        String value = role.trim();
        return Role.ADMIN.equals(value) || Role.USER.equals(value) ? value : null;
    }
}
