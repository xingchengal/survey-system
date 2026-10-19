package com.survey.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j（OpenAPI 3）接口文档配置：/doc.html 顶部的标题、描述与版本。
 *
 * ★ 访问控制不在这里：DocAccessFilter 要求 doc_token Cookie（scope=doc 且 role=admin），否则 403。
 * ★ 首页文案是手写的，增删端点后要回来核对；version 与前端「系统信息」保持一致。
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
                                + "权限分档与业务前置条件的权威口径见仓库 docs/AUDIT.md")
                        .version("2.3.0")
                        .contact(new Contact()
                                .name("开发者")
                                .email("dev@example.com")));
    }
}
