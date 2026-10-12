package com.survey;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 问卷系统后端启动类。
 *
 * ── 四个注解分别做什么 ────────────────────────────────
 *   @SpringBootApplication : 组合注解（配置类 + 自动配置 + 扫描 com.survey 包下的组件）
 *   @MapperScan            : 扫描 MyBatis Mapper 接口，省得漏标
 *                            （项目里每个 Mapper 也各自标了 @Mapper，双保险）
 *   @EnableScheduling      : 开启定时任务 —— ★ 去掉它，SurveyService.autoPublishAndClose()
 *                            上的 @Scheduled 会**静默失效**，问卷再也不会自动发布/关闭
 *   @EnableAsync           : 开启异步 —— ★ 去掉它，所有 @Async 方法（发通知、写操作/异常日志）
 *                            会退化成同步执行，提交答卷等接口的响应会变慢
 *
 * ── 启动注意 ──────────────────────────────────────────
 *   项目里没有配 spring.profiles.active，靠 application.yaml 的
 *   `spring.profiles.default: local` 兜底去加载 application-local.yml（数据库密码等在里面）。
 *   如果连这个兜底也没有，就会退回到 application.yaml 的占位符值，表现是"能启动但一碰数据库就报错"。
 *   生产环境必须显式指定 --spring.profiles.active=prod，并用环境变量注入
 *   DB_PASSWORD / QWEN_API_KEY / JWT_SECRET。
 *
 * ── 启动方式 ──────────────────────────────────────────
 *   IDEA 直接 Run 这个类，或命令行 `java -jar xxx.jar --spring.profiles.active=local`
 *   （工作区里的「启动问卷系统-改进版.bat」已经封装好了 JDK17 定位 + profile 参数）。
 */
@SpringBootApplication
@MapperScan("com.survey.mapper")
@EnableScheduling  // 开启定时任务
@EnableAsync       // 开启 @Async 异步（发通知、写日志）
public class SurveyBackendApplication {
    public static void main(String[] args) {
        // ★ 滑块验证码用 JDK 自带的 AWT 画图（service/CaptchaService 的 BufferedImage）。
        //   服务器/容器里**没有显示器**，AWT 必须以 headless 模式运行，否则
        //   java.awt.GraphicsEnvironment 初始化会抛 HeadlessException，注册页直接取不到验证码。
        //   这里在**启动最早期**设置（早于任何 AWT 类被加载）；application.yaml 里也配了一份
        //   （spring 属性 java.awt.headless），两处都留着是双保险：这一行保证"命令行直接 java -jar"
        //   也能跑，yaml 那份保证"用 Spring 的属性机制覆盖"时也一致。
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(SurveyBackendApplication.class, args);
    }
}
