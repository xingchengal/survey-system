package com.survey.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j（OpenAPI 3）接口文档配置 —— 决定 /doc.html 页面顶部显示的元信息。
 *
 * ── 访问控制（重要，别以为文档是公开的）────────────────
 *   filter/DocAccessFilter.java 会拦截 /doc.html、/v3/api-docs、/swagger-ui 等路径，
 *   要求请求带 doc_token Cookie，且其 JWT 里 **scope == "doc" 且 role == "admin"**，否则 403。
 *   ★ 过滤器**先**校验 scope（"文档票 vs 登录票"的边界）、再校验 role ——
 *     只写 role 会让人误以为把登录 token 塞进该 Cookie 也能看文档。
 *   这个 Cookie 由 UserController 登录时**只对管理员**下发（HttpOnly + SameSite=Strict）。
 *
 * ── 三个"版本号"互不相干，别搞混 ───────────────────────
 *   这里的 version("2.3.0")     : 接口文档版本（与前端「系统信息」卡片的产品版本保持一致），纯展示
 *   pom.xml 的 0.0.1-SNAPSHOT   : Maven 构建版本（决定 jar 包名）
 *   Dashboard.vue 的 v2.3.0     : 前端"系统信息"卡片上的产品版本
 *
 * ★ 首页那几行（标题/描述/版本）是**手写**的，不会跟着 Controller 自动更新 ——
 *   每次增删端点或改规模都要回来核一遍，否则文档首页会一直停在上一个数字上。
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("问卷调查系统 API 文档")
                        .description("基于 Spring Boot 4.0.8 + MyBatis 的问卷调查系统接口文档"
                                + " · **98** 个方法级端点（公开 12 / 需登录 31 / 需登录+归属 25 / 管理员 30）"
                                + " · 21 张表 · 339 个后端用例。"
                                + "权限分档与业务前置条件的权威口径见仓库 docs/问卷系统-端点权限审计表.md")
                        .version("2.3.0")
                        .contact(new Contact()
                                .name("开发者")
                                .email("dev@example.com")));
    }
}
