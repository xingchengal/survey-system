package com.survey.service;

import com.survey.entity.LoginLog;
import com.survey.mapper.LoginLogMapper;
import com.survey.util.WebUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *     · 登录接口本身就是**公开 + 高频**的入口，一次扫描就能灌满这张表（而它还没有清理任务）。
 *   将来要做失败锁定，把 record 的调用点挪到"登录失败"分支并传 status=0 即可，**不用改表**。
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
}
