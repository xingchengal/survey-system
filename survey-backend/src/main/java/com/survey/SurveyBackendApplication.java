package com.survey;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.survey.mapper")
@EnableScheduling  // 开启定时任务
@EnableAsync
public class SurveyBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(SurveyBackendApplication.class, args);
    }
}