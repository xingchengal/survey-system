package com.survey.filter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.*;

/**
 * 可改写请求头的 HttpServletRequest 包装器 —— JwtAuthFilter 注入身份用。
 *
 * ★ 安全前提：写入值来自过滤器解析出的 JWT；getHeader 优先返回 customHeaders，
 *   客户端伪造的 X-User-Id 会被覆盖 —— 但只在请求经过 JwtAuthFilter 时成立。
 * ★ 必须同时覆盖 getHeader / getHeaderNames / getHeaders，缺一个身份头就会漏给 Controller。
 */
public class HeaderRewriteRequestWrapper extends HttpServletRequestWrapper {

    /** 只覆盖身份相关的几个头，不碰客户端传的其他头。 */
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
        // 优先返回写入值，客户端伪造的同名头因此失效
        if (customHeaders.containsKey(name)) {
            return customHeaders.get(name);
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        // 去重且保持顺序：先放注入的身份头，再放客户端的头
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
