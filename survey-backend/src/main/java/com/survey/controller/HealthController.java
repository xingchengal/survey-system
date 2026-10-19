package com.survey.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查：服务与数据库是否可用，启动脚本把它当就绪探针轮询。
 * ★ 必须公开（JwtAuthFilter 白名单）：未登录时启动器探针就要拿到响应，不要加登录要求。
 * ★ 必须真的探测数据库：常量 UP 会掩盖"探针绿、接口全 500"；DB 异常返 503。
 * databaseError 只回传异常类名，避免泄露连接串等内部信息。
 */
@Tag(name = "健康检查", description = "服务状态监测")
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger LOG = LoggerFactory.getLogger(HealthController.class);

    /** 数据源；required = false 让无数据源的切片测试也能加载本类（此时不判为故障）。 */
    @Autowired(required = false)
    private DataSource dataSource;

    @Operation(summary = "服务健康检查（含数据库探测）")
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("timestamp", System.currentTimeMillis());

        boolean dbUp;
        String dbError = null;
        if (dataSource == null) {
            dbUp = true;   // 没有数据源可探（测试环境），不视为故障
        } else {
            dbUp = false;
            try (Connection conn = dataSource.getConnection()) {
                dbUp = conn.isValid(2);   // 2 秒超时，避免探针本身把线程挂住
            } catch (Exception e) {
                dbError = e.getClass().getSimpleName();
                LOG.warn("健康检查：数据库探测失败 —— {}", e.getMessage());
            }
        }

        result.put("status", dbUp ? "UP" : "DOWN");
        result.put("database", dbUp ? "UP" : "DOWN");
        if (dbError != null) {
            result.put("databaseError", dbError);
        }
        return ResponseEntity.status(dbUp ? 200 : 503).body(result);
    }
}
