package com.survey.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web 层配置：CORS + 限流拦截器注册。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 限流拦截器（登录防爆破、AI 防刷、匿名接口防灌） */
    @Autowired
    private RateLimitInterceptor rateLimitInterceptor;

    /**
     * 只挂 /api/**：静态资源、SPA 路由与文档页面不该被限流。
     * 拦截器在 Filter 之后执行，能读到 JwtAuthFilter 注入的 X-User-Id。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
    }

    /**
     * CORS：只放行本机开发来源（5173 Vite、8080 后端自身）。
     *
     * ★ 不要改成通配：allowCredentials(true) + "*" 等于任何网站都能带浏览器凭证调接口。
     * 生产是同源单包部署，本就不需要 CORS；加来源必须写全“协议+域名+端口”。
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "http://localhost:8080",
                "http://127.0.0.1:8080"
        ));
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
