package com.survey.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.PageParams;
import com.survey.common.SqlLikeEscaper;
import com.survey.common.PageResult;
import com.survey.entity.ErrorLog;
import com.survey.mapper.ErrorLogMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 异常日志（t_error_log）：异步落库、分页查询、删除 / 清空、按保留期回收。
 * ★ 删除与清空都不可恢复（管理端二次确认）；只清异常日志，不动审计表 t_operation_log。
 * 保留期与登录日志共用 log.retention-days（默认 90，≤0 关闭），运维只调一个值。
 * save 是 @Async + 内部 try-catch：记录日志失败绝不影响业务。
 */
@Service
public class ErrorLogService {

    /** 统一用 SLF4J 记录：System.out/err/printStackTrace 在生产会直接丢失。 */
    private static final Logger LOG = LoggerFactory.getLogger(ErrorLogService.class);

    @Autowired
    private ErrorLogMapper errorLogMapper;

    /**
     * 异步记录一条异常日志。
     * 整体 try-catch：异常处理器在"处理异常时"再抛异常，问题会变得很难排查。
     */
    @Async
    public void save(ErrorLog errorLog) {
        try {
            errorLogMapper.insert(errorLog);
        } catch (Exception e) {
            // 记录日志失败不能影响业务
            LOG.error("保存异常日志失败：{}", e.getMessage(), e);
        }
    }

    /**
     * 分页查询异常日志（管理端）。
     * startPage 只对紧接着的那条查询生效；响应回显钳制后的页码 / 页大小。
     */
    public PageResult<ErrorLog> page(int pageNum, int pageSize, String keyword) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // LIKE 通配符必须转义：输入 % 会匹配所有行（筛选被静默绕过），输入 _ 会被当成任意字符
        keyword = SqlLikeEscaper.escape(keyword);
        List<ErrorLog> list = errorLogMapper.findPageByCondition(keyword);
        PageInfo<ErrorLog> pageInfo = new PageInfo<>(list);
        // 回显钳制后的值：回显原值会让前端分页组件把总页数 / 每页条数算错
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /** 异常总数（管理端展示/红点）。 */
    public int count() {
        return errorLogMapper.count();
    }

    /** 删除单条异常日志（不可恢复）。 */
    public int delete(Integer id) {
        return errorLogMapper.deleteById(id);
    }

    /** 清空全部异常日志（不可恢复，管理端有二次确认）。 */
    public int clearAll() {
        return errorLogMapper.clearAll();
    }

    // ==================== 保留策略 ====================

    /** 异常日志保留天数（log.retention-days，默认 90；≤0 关闭自动清理）。与登录日志共用配置。 */
    @Value("${log.retention-days:90}")
    private int retentionDays;

    /**
     * 每天 3:30 清理过期异常日志（create_time 有索引，走范围扫描）。
     * 表只增不减会稳定膨胀、拖慢模糊查询，所以按保留期回收；
     * 整体 try-catch：维护任务失败不能影响业务，也不能让调度器反复抛异常刷屏。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void purgeExpiredLogs() {
        if (retentionDays <= 0) {
            return;   // 配成 0 或负数 = 关闭自动清理（保留全部记录，交给管理员手动清）
        }
        try {
            int removed = errorLogMapper.deleteOlderThan(retentionDays);
            if (removed > 0) {
                LOG.info("异常日志保留策略：删除 {} 天前的记录 {} 条", retentionDays, removed);
            }
        } catch (Exception e) {
            LOG.error("异常日志清理失败：{}", e.getMessage(), e);
        }
    }
}
