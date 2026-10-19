package com.survey.service;

import com.survey.entity.LoginLog;
import com.survey.mapper.LoginLogMapper;
import com.survey.util.WebUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 登录日志（t_login_log）：记录成功登录、查询最近登录、按保留天数清理。
 * ★ 只追加、不删单条 / 不改单条（否则审计数据可被篡改），只做整段时间窗回收。
 * 最近登录只取 10 条（前端不分页，上限写死在 Service，避免 limit 被放大成全表导出）。
 * ★ 保留 90 天（log.retention-days，0 或负数关闭）；t_operation_log 是审计证据，刻意不清理。
 * 写入失败只打日志、绝不影响登录：登录日志是审计补充，不是登录的必要步骤。
 */
@Service
public class LoginLogService {

    private static final Logger LOG = LoggerFactory.getLogger(LoginLogService.class);

    /**
     * ip 列宽（varchar(50)，与 WebUtils 同数值）。
     * 这里再截一次兜底：WebUtils 放宽后，超长 insert 在 MySQL 非严格模式静默截断、严格模式抛异常。
     */
    private static final int IP_MAX_LENGTH = 50;

    /** user_agent 列宽（t_login_log.user_agent = varchar(500)）。 */
    private static final int UA_MAX_LENGTH = 500;

    /**
     * 单个用户最多返回多少条最近登录。
     * 上限写死在 Service、不从参数取，否则 limit 可被放大成"全表导出"。
     */
    private static final int DEFAULT_RECENT_LIMIT = 10;

    @Autowired
    private LoginLogMapper loginLogMapper;

    /**
     * 记一条成功登录；调用方：UserController.login()。
     *
     * @param username 登录账号（冗余存，账号改名后历史仍可读）
     * @param request  取 IP 与 UA；为 null 时两项存 null（不抛异常）
     */
    public void recordSuccess(Integer userId, String username, String role, HttpServletRequest request) {
        if (userId == null) return;
        try {
            LoginLog log = new LoginLog();
            log.setUserId(userId);
            log.setUsername(username);
            log.setRole(role);
            log.setIp(truncate(request == null ? null : WebUtils.getClientIp(request), IP_MAX_LENGTH));
            log.setUserAgent(truncate(request == null ? null : request.getHeader("User-Agent"), UA_MAX_LENGTH));
            log.setStatus(1);   // 1=成功；失败记录不在展示范围内，暂不写
            loginLogMapper.insert(log);
        } catch (Exception e) {
            // 吞掉：登录日志写不进去，用户照样要能登进来
            LOG.error("写登录日志失败（不影响本次登录）：userId={} —— {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 查某个用户最近的登录记录（新的在前）。
     * 只按 userId 查 —— 个人中心只能看**自己**的记录；没有"按账号名查别人"的口子。
     */
    public List<LoginLog> findRecent(Integer userId) {
        return loginLogMapper.findRecentByUser(userId, DEFAULT_RECENT_LIMIT);
    }

    /** 截断到列宽以内；null / 空串原样返回（存 null 比存 "" 干净，与全项目口径一致）。 */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.isEmpty()) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    // ==================== 保留策略 ====================

    /**
     * 登录日志保留天数，默认 90 天；配成 0 或负数 = 关闭自动清理。
     * 与 t_error_log 共用 log.retention-days：运维只调一个值，不会改一个忘另一个。
     */
    @Value("${log.retention-days:90}")
    private int retentionDays;

    /**
     * 每天凌晨 3:45 清理过期登录日志（与异常日志 3:30、刷新令牌 4:15 错开，避免抢数据库连接）。
     * 整体 try-catch：维护任务失败不能影响业务，也不能让调度器反复抛异常刷屏。
     */
    @Scheduled(cron = "0 45 3 * * ?")
    public void purgeExpired() {
        if (retentionDays <= 0) {
            return;   // 配成 0 或负数 = 关闭自动清理
        }
        try {
            int removed = loginLogMapper.deleteOlderThan(retentionDays);
            if (removed > 0) {
                LOG.info("登录日志保留策略：删除 {} 天前的记录 {} 条", retentionDays, removed);
            }
        } catch (Exception e) {
            LOG.error("登录日志清理失败：{}", e.getMessage(), e);
        }
    }
}
