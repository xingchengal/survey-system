package com.survey.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.PageParams;
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
     * 分页查询操作日志（管理端），支持按用户名和操作名筛选。
     * ★ PageHelper 的 startPage 必须紧挨着 mapper 查询调用。
     */
    public PageResult<OperationLog> page(int pageNum, int pageSize, String username, String operation) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // ★ LIKE 通配符必须转义：
        //   否则用户在搜索框里输入一个 % 就等于"匹配所有行"（筛选被静默绕过），
        //   输入 _ 会被当成"任意一个字符"；见 common/SqlLikeEscaper 的类注释。
        //   本方法有**两个**模糊搜索参数，都要转义。
        username = SqlLikeEscaper.escape(username);
        operation = SqlLikeEscaper.escape(operation);
        List<OperationLog> list = operationLogMapper.findPageByCondition(username, operation);
        PageInfo<OperationLog> pageInfo = new PageInfo<>(list);
        // ★ 回显**钳制后**的页码/页大小（不能回显调用方传进来的原值）。
        //   分页保护 PageParams 只在 startPage 那一层生效，这里却把原值写进了响应：
        //   请求 pageSize=100000 时实际只返回 100 条，响应体却回显 100000 ——
        //   分页组件据此算总页数/显示"每页条数"都会错；pageSize=0 或负数时同理。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }
}
