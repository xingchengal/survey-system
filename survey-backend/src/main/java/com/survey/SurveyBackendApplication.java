package com.survey;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 后端启动类。
 * ★ @EnableScheduling：去掉它定时任务会静默失效（问卷不再自动发布/关闭）。
 * ★ @EnableAsync：去掉它通知与日志退化为同步，提交答卷等接口会变慢。
 * ★ 默认 profile=local（application.yaml 的 spring.profiles.default）；生产必须显式 prod，
 *   并用环境变量注入 DB_PASSWORD / QWEN_API_KEY / JWT_SECRET。
 */
@SpringBootApplication
@MapperScan("com.survey.mapper")
@EnableScheduling  // 开启定时任务
@EnableAsync       // 开启 @Async 异步（发通知、写日志）
public class SurveyBackendApplication {
    public static void main(String[] args) {
        // ★ AWT 必须 headless：否则滑块取图抛 HeadlessException；yaml 里还有一份双保险
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(SurveyBackendApplication.class, args);
    }
}
