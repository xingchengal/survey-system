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
     * 注册拦截器。
     * ★ 只挂 /api/** —— 静态资源、SPA 路由、接口文档不该被限流（文档页面会连续拉很多请求）。
     * ★ 拦截器在 Filter 之后执行，所以能读到 JwtAuthFilter 注入的 X-User-Id（AI 限流按用户计数就靠它）。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
    }

    /**
     * CORS 跨域配置。
     *
     * ★ 安全约束（不要改成通配）：
     *   `addAllowedOriginPattern("*")` + `setAllowCredentials(true)` 组合在一起等于
     *   「任何网站都能带着浏览器里的凭证跨域调我的接口」，所以只放行本机开发用的来源：
     *     - 5173 是 Vite 开发服务器（前端 npm run dev）
     *     - 8080 是后端自己（同源部署时其实根本用不到 CORS）
     *   生产环境是「前端 dist 拷进后端 static」的同源单包部署，天然不需要 CORS。
     *
     *   要在局域网 / 别的域名访问，把来源加进下面的 List 即可，
     *   必须写完整的「协议 + 域名 + 端口」，不支持通配。
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
