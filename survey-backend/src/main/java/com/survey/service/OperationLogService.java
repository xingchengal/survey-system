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
 * 操作日志业务：异步落库、分页查询。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   aspect/LogAspect.java   → 业务方法正常返回后调 save() 落库
 *   OperationLogController  → 管理端分页查询
 *
 * ── 涉及的表 ──────────────────────────────────────────
 *   t_operation_log（记录：谁、什么角色、做了什么操作、哪个方法、什么 IP、什么时候）
 *
 * ── 设计要点 ──────────────────────────────────────────
 *   1. save() 是 @Async，不阻塞业务；LogAspect 那一侧还整体包了 try-catch，
 *      两层都保证「日志写失败不影响业务」。依赖 @EnableAsync。
 *   2. 这个 service 只有「写」和「查」两个能力 —— 审计日志只追加，
 *      不提供修改/单条删除接口，避免有人事后篡改记录（只有整表清空在 DB 层面可做）。
 */
@Service
public class OperationLogService {

    private static final Logger LOG = LoggerFactory.getLogger(OperationLogService.class);

    /**
     * 操作日志单次导出最多多少行。
     * ★ 与 AnswerController / UserService / SurveyService 的导出上限同一个思路：
     *   导出会把全部行读进内存，不设上限时一次"顺手点一下"就足以把堆打满。
     *   SQL 侧固定 `LIMIT 10001`，超过就明确拒绝，不静默截断。
     */
    public static final int EXPORT_MAX_ROWS = 10_000;

    @Autowired
    private OperationLogMapper operationLogMapper;

    /**
     * 异步保存一条操作日志（调用方是 LogAspect，业务成功后才调）。
     *
     * ★★ 这里**必须有 try-catch**：@Async 落库点若让异常逃逸，它不会传回调用方，
     *   调用链会以为"日志已经记好了"（ErrorLogService.save 与 NotificationService.send 同样自带）。
     *   后果很实际：t_operation_log.ip 是 varchar(50)，而 ip 取自**客户端可伪造**的
     *   X-Forwarded-For；任何人给请求加一个 60 字符的 XFF，这条 insert 就会因"数据过长"失败。
     *   异常发生在**异步线程**里 —— LogAspect 那圈 try-catch 拦的是"提交任务"这一步，
     *   根本抓不到它；于是业务照常返回成功，而这次操作**永远不进审计表**。
     *   也就是说：一个伪造的请求头就能让攻击者自己的所有写操作从审计日志里消失。
     *   现在自己兜住并记明确日志（异常仍会丢，但至少**看得见**，不再是静默）。
     *   ★ 配套：WebUtils.getClientIp 现在会把 ip 截断到列宽以内（见那边的注释）。
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
     * 分页查询操作日志（管理端），支持按用户名、操作名、**操作人角色**筛选，
     * 以及**操作时间范围**（`yyyy-MM-dd`，闭区间）。
     * ★ PageHelper 的 startPage 必须紧挨着 mapper 查询调用。
     */
    public PageResult<OperationLog> page(int pageNum, int pageSize, String username, String operation,
                                         String role, String startDate, String endDate) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // ★ LIKE 通配符必须转义：
        //   否则用户在搜索框里输入一个 % 就等于"匹配所有行"（筛选被静默绕过），
        //   输入 _ 会被当成"任意一个字符"；见 common/SqlLikeEscaper 的类注释。
        //   本方法有**两个**模糊搜索参数，都要转义。
        username = SqlLikeEscaper.escape(username);
        operation = SqlLikeEscaper.escape(operation);
        List<OperationLog> list = operationLogMapper.findPageByCondition(username, operation,
                normalizeRoleFilter(role), DateRanges.startOfDay(startDate), DateRanges.endOfDay(endDate));
        PageInfo<OperationLog> pageInfo = new PageInfo<>(list);
        // ★ 回显**钳制后**的页码/页大小（不能回显调用方传进来的原值）。
        //   分页保护 PageParams 只在 startPage 那一层生效，这里却把原值写进了响应：
        //   请求 pageSize=100000 时实际只返回 100 条，响应体却回显 100000 ——
        //   分页组件据此算总页数/显示"每页条数"都会错；pageSize=0 或负数时同理。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /**
     * 导出用的操作日志（**不分页**，最多 {@link #EXPORT_MAX_ROWS} 行）。
     * ★ 筛选条件与 {@link #page} 完全一致，所以"导出的就是屏幕上筛出来的"。
     * ★ 超过上限明确报错拒绝，不静默截断（静默截断会让管理员以为"导出了全部日志"）。
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
     * ★ 与 UserService.normalizeRoleFilter 同一口径。两个 Service 各留一份，是因为
     *   入参来源不同（用户列表 / 日志列表），为一行判断再引一个工具类不划算。
     */
    private static String normalizeRoleFilter(String role) {
        if (role == null) return null;
        String value = role.trim();
        return Role.ADMIN.equals(value) || Role.USER.equals(value) ? value : null;
    }
}
