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
 * ★ IP 取值口径只留这一处，两套口径不要合并：
 * 日志 / 异常落库 / 操作日志 → getClientIp()（含 XFF，可伪造，仅供参考）；
 * 限流计数 → getConnectionIp()（容器给出的真实连接地址，不可伪造）。
 * 错误响应统一走 writeJsonError，不要手工拼 JSON。
 */
public final class WebUtils {

    /** ip 列宽上限：t_operation_log.ip 与 t_error_log.ip 都是 varchar(50)，超长会让日志落库失败。 */
    private static final int IP_COLUMN_MAX_LENGTH = 50;

    /** Jackson 序列化器（无状态、线程安全，静态复用即可） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebUtils() {
    }

    /**
     * 写出统一的失败响应：{@code {"success":false,"code":<码>,"message":"<原因>"}}。
     * 用 Jackson 序列化而不是手工拼字符串：message 里出现双引号或反斜杠就会产出非法 JSON，
     * 动态内容还会变成响应体注入；两个 Filter 共用这一份实现。
     *
     * @param code HTTP 状态码（401 / 403 / 429 …）
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
     * 取客户端 IP：X-Forwarded-For → X-Real-IP → getRemoteAddr()，多级代理时取 XFF 第一段。
     * ★ 前两个头客户端可以伪造，此值只适合记日志 / 限流参考，绝不能用于鉴权、归属校验等安全判决。
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
        // 按列宽截断：XFF 完全由客户端控制，一个 60 字符的伪造值就能让日志 insert 因「数据过长」失败，
        // 这次操作从审计里静默消失。截断后 ip 不完整，但保住一条日志远比字段好看重要。
        if (ip != null && ip.length() > IP_COLUMN_MAX_LENGTH) {
            ip = ip.substring(0, IP_COLUMN_MAX_LENGTH);
        }
        return ip;
    }

    /**
     * 取**不可伪造**的客户端连接 IP（容器给出的真实来源地址，完全不看任何请求头）。
     * 基于 IP 的限流必须用它：拿可伪造的 XFF 当计数键，换一个头就换一个桶，「10 次/分钟/IP」形同虚设。
     * 代价：部署在反向代理之后时所有用户共用一个桶，那时应按「可信代理跳数」取 XFF 的固定某一段。
     *
     * @return 容器层连接地址，拿不到时返回 "unknown"
     */
    public static String getConnectionIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return (ip == null || ip.isEmpty()) ? "unknown" : ip;
    }
}
