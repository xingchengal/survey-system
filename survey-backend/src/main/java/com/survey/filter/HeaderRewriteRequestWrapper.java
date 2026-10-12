package com.survey.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.*;

/**
 * 可改写请求头的 HttpServletRequest 包装器 —— JwtAuthFilter 的配套工具。
 *
 * ── 为什么需要它 ──────────────────────────────────────
 *   HttpServletRequest 是只读的，没有 setHeader 方法。
 *   而本项目的设计是：过滤器解析 JWT 后，把身份"塞进请求头"，
 *   后面所有 Controller 用 @RequestHeader("X-User-Id") 就能拿到当前用户。
 *   要实现这件事，只能继承 HttpServletRequestWrapper 并覆盖 getHeader 系列方法。
 *
 * ── ★ 安全要点（为什么这个设计是可信的）────────────────
 *   setHeader 写入的值来自**过滤器内部解析出的 JWT**，不是客户端传的。
 *   客户端即使伪造 X-User-Id: 1，也会被这里覆盖 ——
 *   因为 getHeader 优先返回 customHeaders 里的值，查不到才回退给原始请求。
 *   "请求头里的 X-User-Id 可信"这条前提，成立条件就是请求经过了 JwtAuthFilter。
 *
 * ── 三个被覆盖的方法 ──────────────────────────────────
 *   getHeader(name)      : 单个取值 —— Controller 的 @RequestHeader 最终调它
 *   getHeaderNames()     : 所有头名 —— 我们加的 3 个头也要出现在里面
 *   getHeaders(name)     : 同名多值 —— 我们的头是单值，包成单元素枚举返回
 */
public class HeaderRewriteRequestWrapper extends HttpServletRequestWrapper {

    /** 我们自己要覆盖/新增的头（只放这几个，不碰客户端传的其他头） */
    private final Map<String, String> customHeaders = new HashMap<>();

    public HeaderRewriteRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    /** 由 JwtAuthFilter 调用，写入 X-User-Id / X-User-Role / X-Username。 */
    public void setHeader(String name, String value) {
        customHeaders.put(name, value);
    }

    @Override
    public String getHeader(String name) {
        // 优先返回我们写入的值（这就是"客户端伪造无效"的原因）
        if (customHeaders.containsKey(name)) {
            return customHeaders.get(name);
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        // LinkedHashSet：去重且保持顺序；先放我们的头，再放客户端的头
        Set<String> names = new LinkedHashSet<>(customHeaders.keySet());
        Enumeration<String> original = super.getHeaderNames();
        while (original.hasMoreElements()) {
            names.add(original.nextElement());
        }
        return Collections.enumeration(names);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (customHeaders.containsKey(name)) {
            return Collections.enumeration(Collections.singletonList(customHeaders.get(name)));
        }
        return super.getHeaders(name);
    }
}
