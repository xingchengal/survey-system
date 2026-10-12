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
 * 异常日志业务：异步落库异常、分页查询、清理。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   GlobalExceptionHandler  → 捕获到异常后调 save() 落库
 *   AiGenerateService       → AI 调用失败时调 save() 落库（它自己吞了异常，不走全局处理器）
 *   ErrorLogController      → 管理端的查询 / 删除 / 清空
 *
 * ── 涉及的表 ──────────────────────────────────────────
 *   t_error_log（含 requestUri、errorType、stackTrace、ip 等排查信息）
 *
 * ── 设计要点 ──────────────────────────────────────────
 *   save() 是 @Async + 内部 try-catch：**记录日志这件事绝不能影响业务**。
 *   依赖 SurveyBackendApplication 上的 @EnableAsync。
 */
@Service
public class ErrorLogService {

    /** 统一用 SLF4J 记录：System.out/err/printStackTrace 在生产会直接丢失。 */
    private static final Logger LOG = LoggerFactory.getLogger(ErrorLogService.class);

    @Autowired
    private ErrorLogMapper errorLogMapper;

    /**
     * 异步记录一条异常日志。
     * ★ 整体 try-catch：连"写日志失败"都不允许往上抛 ——
     *   否则异常处理器自己在处理异常时又抛异常，问题会变得很难排查。
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
     * ★ PageHelper 的 startPage 只对紧接着的那一条查询生效，两者不要被别的查询隔开。
     */
    public PageResult<ErrorLog> page(int pageNum, int pageSize, String keyword) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // ★ LIKE 通配符必须转义：
        //   否则用户在搜索框里输入一个 % 就等于"匹配所有行"（筛选被静默绕过），
        //   输入 _ 会被当成"任意一个字符"；见 common/SqlLikeEscaper 的类注释。
        keyword = SqlLikeEscaper.escape(keyword);
        List<ErrorLog> list = errorLogMapper.findPageByCondition(keyword);
        PageInfo<ErrorLog> pageInfo = new PageInfo<>(list);
        // ★ 回显**钳制后**的页码/页大小（不能回显调用方传进来的原值）。
        //   分页保护 PageParams 只在 startPage 那一层生效，这里却把原值写进了响应：
        //   请求 pageSize=100000 时实际只返回 100 条，响应体却回显 100000 ——
        //   分页组件据此算总页数/显示"每页条数"都会错；pageSize=0 或负数时同理。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /** 异常总数（管理端展示/红点）。 */
    public int count() {
        return errorLogMapper.count();
    }

    /** 删除单条异常日志。 */
    public int delete(Integer id) {
        return errorLogMapper.deleteById(id);
    }

    /** 清空全部异常日志。★ 不可恢复，管理端有二次确认。 */
    public int clearAll() {
        return errorLogMapper.clearAll();
    }

    // ==================== 保留策略 ====================

    /**
     * 异常日志保留天数，默认 90 天。
     * ★ 可用配置项 `log.retention-days` 覆盖（application.yaml / 环境变量都行），
     *   所以运维不需要改代码就能调整保留窗口。
     */
    @Value("${log.retention-days:90}")
    private int retentionDays;

    /**
     * 每天凌晨 3:30 清理过期异常日志。
     *
     * ★ 为什么需要它：这张表若**只增不减**（save() 持续写入，清理只有管理员手动 clearAll()），
     *   生产环境长期运行下会稳定膨胀，连带 findPageByCondition 的模糊查询也变慢。
     *   所以按保留期自动清理（create_time 上有索引，走范围扫描）。
     *
     * ★ 为什么整体 try-catch：与 save() 同样的理由 ——
     *   日志维护任务绝不能因为自身失败而影响业务，更不能让调度器反复抛异常刷屏。
     * ★ 依赖启动类上的 @EnableScheduling（与 SurveyService.autoPublishAndClose 同源）。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void purgeExpiredLogs() {
        if (retentionDays <= 0) {
            return;   // 配成 0 或负数 = 关闭自动清理（保留全部历史，交给管理员手动清）
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
