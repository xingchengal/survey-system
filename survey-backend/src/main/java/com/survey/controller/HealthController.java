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
 * 健康检查：返回服务与数据库是否可用。全项目最简单的接口，但有两个「隐形用户」很重要。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   1. 启动脚本「启动问卷系统-改进版.bat」：启动 jar 后轮询这里，通了才去开浏览器；
 *   2. Dashboard.vue 的「系统信息」卡片：右上角那个绿点/红点就是它。
 *
 * ── 权限 ──────────────────────────────────────────────
 *   公开：在 JwtAuthFilter 白名单里。必须公开 —— 否则未登录时探针拿不到响应，
 *   启动脚本会一直等不到就绪。
 *
 * ── 返回 ──────────────────────────────────────────────
 *   DB 正常： **200** {"status":"UP","database":"UP","timestamp":...}
 *   DB 异常： **503** {"status":"DOWN","database":"DOWN","databaseError":"...","timestamp":...}
 *
 * ★ 健康检查**必须真的探测数据库**：若无论数据库通不通都返回常量 `status=UP`，
 *   "探针说健康、实际每个接口都 500"这种最需要被发现的情况反而检测不到。
 *   这里取一次连接做 isValid(2) 探测：
 *     · 启动脚本会因为拿不到 200 而继续等待（DB 没起来本来就该等，属正确行为）；
 *     · Dashboard 的绿点读的是响应是否 ok，DB 挂掉会自动变红点。
 *   ★ databaseError 只回传**异常类名**（不回传 message），避免泄露连接串等内部信息。
 */
@Tag(name = "健康检查", description = "服务状态监测")
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger LOG = LoggerFactory.getLogger(HealthController.class);

    /**
     * 数据源。
     * ★ required = false：让"没有配置数据源"的场景（例如纯 mock 的切片测试）也能加载本类，
     *   那种情况下不把数据库判为故障。
     */
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
