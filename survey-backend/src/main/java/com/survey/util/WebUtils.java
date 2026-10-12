package com.survey.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Web 层公共小工具。
 *
 * ── IP 取值为什么必须只有一处实现 ──────────────────────
 *   IP 的取值口径若在两个类里逐行重复，将来要加"信任几层反向代理"的配置就得改多处，
 *   漏一处就会出现"日志里的 IP 有时对、有时不对"这种极难查的问题。现在统一到这里。
 *   ★ 两套口径的分工（**不要合并**）：
 *     日志 / 异常落库 / 操作日志 → getClientIp()（含 XFF，**仅供参考**）
 *     限流计数                  → getConnectionIp()（容器给出的真实连接地址，不可伪造）
 */
public final class WebUtils {

    /**
     * ip 字段的列宽上限：t_operation_log.ip 与 t_error_log.ip 都是 varchar(50)。
     * 超长会导致日志落库失败（详见 getClientIp 里的说明）。
     */
    private static final int IP_COLUMN_MAX_LENGTH = 50;

    /** Jackson 序列化器（无状态、线程安全，静态复用即可） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebUtils() {
    }

    /**
     * 写出统一的失败响应：{@code {"success":false,"code":<码>,"message":"<原因>"}}。
     *
     * ★ 用 Jackson 序列化而不是手工拼字符串：
     *   手工拼接时，message 里只要出现双引号或反斜杠就会产出**非法 JSON**；
     *   一旦有人把动态内容（例如回显请求 URI）塞进 message，还会变成响应体注入。
     *   两个 Filter 共用这一份实现，从根上杜绝这类问题。
     *
     * @param response HTTP 响应
     * @param code     HTTP 状态码（401 / 403 / 429 …）
     * @param message  给用户看的原因
     */
    public static void writeJsonError(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message);
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }

    /**
     * 取客户端 IP。
     *
     * ★ 优先级：X-Forwarded-For → X-Real-IP → getRemoteAddr()。
     *   前两个是给反向代理用的，但**客户端可以伪造** —— 所以这个值只适合
     *   "记日志 / 限流参考"，绝不能用于任何安全判决（例如鉴权、归属校验）。
     *   多级代理时 XFF 形如 "client, proxy1, proxy2"，取第一段。
     *
     * @return 客户端 IP；拿不到时返回 null 或容器给出的 remoteAddr
     */
    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        // ★★ **按列宽截断**：X-Forwarded-For 完全由客户端控制，而 t_operation_log.ip 与
        //   t_error_log.ip 都是 varchar(50) —— 一个 60 字符的伪造 XFF 就能让日志 insert
        //   因"数据过长"失败，这次操作从审计里静默消失。
        //   截断虽让 ip 不完整，但**保住一条日志**远比"字段好看"重要。
        if (ip != null && ip.length() > IP_COLUMN_MAX_LENGTH) {
            ip = ip.substring(0, IP_COLUMN_MAX_LENGTH);
        }
        return ip;
    }

    /**
     * 取**不可伪造**的客户端连接 IP（容器给出的真实来源地址）。
     *
     * ★ 与 {@link #getClientIp} 的关键区别：本方法**完全不看任何请求头**。
     *
     * ★ 为什么必须有它：基于 IP 的限流若拿可伪造的 X-Forwarded-For 当计数键，防爆破就能被绕过 ——
     *   攻击者每发一个请求换一个 XFF，计数 key 每次都不同，"10 次/分钟/IP"形同虚设。
     *   所以**凡是安全相关的计数（限流）必须用这个**。
     *
     * ★ 代价：若将来真的部署在 Nginx 等反向代理之后，
     *   这里拿到的是**代理的 IP**，所有用户会共用一个桶。
     *   那时正确做法是按"可信代理跳数"取 XFF 的固定某一段（例如只信任最后 1 跳），
     *   而**不是**简单地退回信任 XFF（那等于把刚堵上的洞重新打开）。
     *   当前项目是单实例直连部署（前端 dist 打进后端），不受影响。
     *
     * @return 容器层连接地址，拿不到时返回 "unknown"
     */
    public static String getConnectionIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return (ip == null || ip.isEmpty()) ? "unknown" : ip;
    }
}
