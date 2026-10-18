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
 * 登录日志业务（t_login_log）—— 需求 三.3「账号安全信息展示：最近登录时间、登录IP，数据取自系统登录日志」。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   UserController.login()      → record()  登录**成功后**写一条
 *   UserController.loginLogs()  → findRecent()  个人中心读"最近登录"
 *
 * ── ★ 当前只记「成功」的登录（与表里 status 列的注释一致）────
 *   表结构有 status（1=成功 / 0=失败），是为将来的"登录失败次数限制、多次错误临时锁定账号"预留的。
 *   现在**只写成功**，理由：
 *     · 需求要展示的是"你上次什么时候、从哪个 IP 登录过"，失败记录不在展示范围内；
 *     · 登录接口本身就是**公开 + 高频**的入口，一次扫描就能灌满这张表。
 *   ★ **"表满"不再是理由**（C2 已补）：本类现在有保留策略定时任务（`purgeExpired`，
 *     默认 90 天，配置项 `log.retention-days`，与异常日志共用同一个键）。
 *     将来把 record 挪到失败分支时，沿用同一条清理链路即可，不需要再补清理代码。
 *
 * ── ★ 为什么这张"只追加"的表要清理，而 t_operation_log 不清理 ─────
 *   `t_login_log` 只服务"最近登录"一个展示位（`findRecentByUser` 只取最近 10 条），
 *   历史行**没有任何读取方**，留着只是占空间；
 *   `t_operation_log` 是**审计证据**（谁在什么时候改了什么），必须一直留 ——
 *   两者保留策略**刻意不同**，别看到"操作日志没清理任务"就以为这里也漏了。
 *   共同点：都**不提供删单条/改单条**（那才会让审计数据可被篡改），只做整段时间窗回收。
 *
 * ── ★ 写入失败绝不影响登录 ────────────────────────────
 *   record() 整体包 try-catch，只打印一行错误。理由：登录日志是"审计的补充"，
 *   不是登录的必要步骤 —— 库这一列短暂写不进去，也不该让用户登不进来。
 *   （对比：OperationLogService.save 的 @Async 落库同样自带 try-catch，是同一个设计取向。）
 *
 * ── 涉及的表 ──────────────────────────────────────────
 *   只碰 t_login_log（不读 t_user，账号/角色由调用方从已登录用户对象传入，少一次查询）。
 */
@Service
public class LoginLogService {

    private static final Logger LOG = LoggerFactory.getLogger(LoginLogService.class);

    /**
     * ip 列宽（t_login_log.ip = varchar(50)），与 {@link WebUtils} 里的截断口径**同一数值**。
     * ★ 这里再截一次是"最后一道防线"：WebUtils.getClientIp 已经截过，
     *   但那是**另一个类的不变量**，将来它若改了（比如为了保留完整 XFF 而放宽），
     *   这里不该跟着静默失败 —— 超长 insert 在 MySQL 非严格模式下会被截断、
     *   严格模式下直接抛异常，两种都不是我们想要的。
     */
    private static final int IP_MAX_LENGTH = 50;

    /** user_agent 列宽（t_login_log.user_agent = varchar(500)）。 */
    private static final int UA_MAX_LENGTH = 500;

    /**
     * 单个用户最多返回多少条登录记录。
     * ★ 前端**不传**分页参数 —— 这是"最近登录"这种展示型数据，不是可翻页的列表；
     *   上限写死在 Service 里（而不是从参数取），避免把 limit 暴露成可被放大成"全表导出"的口子。
     */
    private static final int DEFAULT_RECENT_LIMIT = 10;

    @Autowired
    private LoginLogMapper loginLogMapper;

    /**
     * 记一条**成功**登录。调用方：UserController.login()，在拿到合法用户之后。
     *
     * @param userId    登录用户的 id
     * @param username  登录账号（冗余存，账号改名后历史仍可读）
     * @param role      角色（admin / user）
     * @param request   用于取 IP 与 UA；为 null 时两项都存 null（不抛异常）
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
            log.setStatus(1);   // 1=成功；失败分支（status=0）见类注释
            loginLogMapper.insert(log);
        } catch (Exception e) {
            // ★ 吞掉：登录日志写不进去，用户照样要能登进来
            LOG.error("写登录日志失败（不影响本次登录）：userId={} —— {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 查某个用户最近的登录记录（新的在前）。
     * ★ 只按 userId 查 —— 个人中心只能看**自己**的记录；没有"按账号名查别人"的口子。
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
     * 登录日志保留天数，默认 90 天。
     * ★ 与 `t_error_log` 共用配置项 `log.retention-days`（ErrorLogService 也是这个键）——
     *   运维只调一个值就能同时改两个日志表的窗口，不会出现"改了一个忘了另一个"。
     * ★ 配成 0 或负数 = 关闭自动清理（保留全部历史）。
     */
    @Value("${log.retention-days:90}")
    private int retentionDays;

    /**
     * 每天凌晨 3:45 清理过期登录日志。
     *
     * ★ 为什么在这个点：3:30 是异常日志清理、4:15 是刷新令牌清理 ——
     *   三条维护任务错开，避免同一时刻抢数据库连接。
     *
     * ★ 为什么整体 try-catch：与 record() 同一个理由 ——
     *   日志维护任务绝不能因为自身失败而影响业务，更不能让调度器反复抛异常刷屏。
     * ★ 依赖启动类上的 @EnableScheduling（与 ErrorLogService.purgeExpiredLogs 同源）。
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
