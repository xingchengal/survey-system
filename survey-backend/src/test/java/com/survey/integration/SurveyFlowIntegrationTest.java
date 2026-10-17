package com.survey.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * **真库集成测试**：注册 → 建卷 → 存题 → 发布 → 匿名提交 → 统计 → 分页 → 导出 → 删除，
 * 全程走真实 HTTP + 真实 MySQL（独立的空库 survey_db_test，绝不碰开发/演示库）。
 *
 * ── 它补的是哪块空白（《待解决问题》C4）─────────────────
 *   {@code EndpointAuthMatrixTest} 覆盖的是"权限闸门"，但把 Mapper 全 mock 掉了：
 *   它证明不了 SQL 对不对、事务回滚对不对、导出真的产出一个 xlsx。
 *   本类补上的正是这一段：一条完整业务链路，用真库跑通。
 *
 * ── 为什么不零依赖地默认跑 ──────────────────────────────
 *   它必须有 MySQL。默认 `mvnw test` / CI 的"单元测试"阶段**没有库**，
 *   所以本类被 pom 的 surefire `<excludes>` 排除，只有显式点名才跑：
 *
 *   ```powershell
 *   # 本机（先建空库并导入结构，见 sql/init.sql）
 *   mysql -uroot -p --default-character-set=utf8mb4 -e "CREATE DATABASE IF NOT EXISTS survey_db_test ..."
 *   mysql -uroot -p --default-character-set=utf8mb4 survey_db_test < sql/init.sql
 *   $env:TEST_DB_PASSWORD='<本机口令>'
 *   ./mvnw -o "-Dtest=SurveyFlowIntegrationTest" test
 *   ```
 *   CI 里由 `backend-integration` 作业提供 MySQL service 再跑（见 .github/workflows/ci.yml）。
 *
 * ── 数据隔离与清理 ──────────────────────────────────────
 *   · 账号名 / 问卷标题都带时间戳，避免与历史数据相撞；
 *   · 用例结尾自己走"回收站 → 彻底删除"验证级联删除；
 *   · {@code @AfterEach} 再按 id / username 兜底清理一次（失败中断也不会留垃圾）。
 *   ★ 别把这里的数据源指向 survey_db —— 那是活库，测试会往里写。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class SurveyFlowIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper om = new ObjectMapper();

    private String token;
    private String username;
    private Integer surveyId;

    // ==================== 主链路 ====================

    @Test
    @DisplayName("★ 全链路（真库 + 真 HTTP）：注册→建卷→存题→发布→提交→统计→分页→导出→删除")
    void fullSurveyFlow() throws Exception {
        // ---------- 1. 滑块 → 注册 → 登录 ----------
        Res slider = send("GET", "/api/captcha/slider", null);
        assertEquals(200, slider.status, "取滑块验证码：" + slider.body);
        JsonNode s = json(slider.body);
        String captchaToken = s.get("token").asText();
        int offset = s.get("offset").asInt();

        Res verified = send("POST",
                "/api/captcha/verify?token=" + enc(captchaToken) + "&left=" + offset, null);
        assertEquals(200, verified.status, "滑块判定：" + verified.body);
        assertTrue(json(verified.body).get("passed").asBoolean(), "按后端下发的 offset 拖动应当判定通过");

        username = "it" + String.valueOf(System.currentTimeMillis()).substring(5);
        Res registered = send("POST", "/api/user/register?captchaToken=" + enc(captchaToken),
                Map.of("username", username, "password", "Test123456"));
        assertEquals(200, registered.status, "注册临时账号：" + registered.body);

        Res login = send("POST", "/api/user/login", Map.of("username", username, "password", "Test123456"));
        assertEquals(200, login.status, "登录：" + login.body);
        token = json(login.body).get("token").asText();
        assertFalse(token.isBlank(), "登录应返回 token");

        // ---------- 2. 建卷 + 存题 ----------
        Res add = send("POST", "/api/survey/add", Map.of(
                "title", "【集成测试】全链路 " + System.currentTimeMillis(),
                "description", "由 SurveyFlowIntegrationTest 创建，跑完即删",
                "status", 0));
        assertEquals(200, add.status, "新建问卷：" + add.body);
        surveyId = json(add.body).get("id").asInt();

        Res save = send("POST", "/api/question/save/" + surveyId, List.of(
                Map.of("title", "你对本次服务满意吗？", "type", 1, "required", 1, "sortOrder", 1,
                        "options", List.of(
                                Map.of("content", "满意", "sortOrder", 1),
                                Map.of("content", "不满意", "sortOrder", 2))),
                Map.of("title", "还有什么建议？", "type", 3, "required", 0, "sortOrder", 2)));
        assertEquals(200, save.status, "保存题目：" + save.body);
        JsonNode saved = json(save.body);
        assertEquals(2, saved.size(), "应回显 2 道题的 id");
        int q1 = saved.get(0).get("id").asInt();
        int q2 = saved.get(1).get("id").asInt();

        // ---------- 3. 发布 → 匿名提交 3 份 ----------
        Res publish = send("POST", "/api/survey/publish/" + surveyId, null);
        assertEquals(200, publish.status, "发布：" + publish.body);
        String shareCode = json(publish.body).get("shareCode").asText();
        assertFalse(shareCode == null || shareCode.isBlank(), "发布后应有分享码");

        for (int i = 1; i <= 3; i++) {
            Res submit = send("POST", "/api/answer/submit-share/" + shareCode, List.of(
                    Map.of("questionId", q1, "content", i == 3 ? "不满意" : "满意"),
                    Map.of("questionId", q2, "content", "第 " + i + " 份建议")));
            assertEquals(200, submit.status, "匿名提交第 " + i + " 份：" + submit.body);
        }

        Res count = send("GET", "/api/answer/count/" + surveyId, null);
        assertEquals(200, count.status);
        assertEquals(3, Integer.parseInt(count.body.trim()), "答卷数应为 3");

        // ---------- 4. 统计（整份问卷）----------
        Res stats = send("GET", "/api/answer/statistics/survey/" + surveyId, null);
        assertEquals(200, stats.status, "整份问卷统计：" + stats.body);
        JsonNode statsJson = json(stats.body);
        assertEquals(3, statsJson.get("answerCount").asInt(), "统计里的答卷数应与 /count 一致");
        JsonNode questions = statsJson.get("questions");
        assertEquals(2, questions.size(), "应返回 2 道题");

        JsonNode q1Stats = questions.get(0);
        assertEquals(q1, q1Stats.get("question").get("id").asInt(), "统计顺序应与题目顺序一致");
        assertEquals(2, countOf(q1Stats.get("chartData"), "满意"), "满意 = 2");
        assertEquals(1, countOf(q1Stats.get("chartData"), "不满意"), "不满意 = 1");
        assertEquals(3, questions.get(1).get("textAnswers").size(), "填空题应有 3 条文本");

        // ---------- 5. 答卷分页（本次 C1 的改动）----------
        Res page1 = send("GET", "/api/answer/list/" + surveyId + "?pageNum=1&pageSize=2", null);
        assertEquals(200, page1.status, "分页第 1 页：" + page1.body);
        JsonNode p1 = json(page1.body);
        assertEquals(3, p1.get("total").asInt());
        assertEquals(2, p1.get("list").size(), "第 1 页 2 条");
        assertEquals(1, p1.get("pageNum").asInt());
        assertEquals(2, p1.get("pageSize").asInt());

        Res page2 = send("GET", "/api/answer/list/" + surveyId + "?pageNum=2&pageSize=2", null);
        JsonNode p2 = json(page2.body);
        assertEquals(1, p2.get("list").size(), "第 2 页 1 条（3 = 2 + 1，不重不漏）");

        int answerId = p1.get("list").get(0).get("id").asInt();
        Long idInPage2 = p2.get("list").get(0).get("id").asLong();
        List<Long> ids1 = new ArrayList<>();
        p1.get("list").forEach(a -> ids1.add(a.get("id").asLong()));
        assertFalse(ids1.contains(idInPage2), "两页之间不能出现同一条答卷");

        // ---------- 6. 单份答卷详情 ----------
        Res detail = send("GET", "/api/answer/detail/" + answerId, null);
        assertEquals(200, detail.status);
        assertEquals(2, json(detail.body).size(), "一份答卷应有 2 条明细");

        // ---------- 7. Excel 导出（真文件）----------
        HttpResponse<byte[]> export = download("/api/answer/export/" + surveyId);
        assertEquals(200, export.statusCode(), "导出应 200");
        byte[] xlsx = export.body();
        assertTrue(xlsx.length > 1000, "导出的 xlsx 不应是空文件，实际 " + xlsx.length + " 字节");
        assertEquals('P', xlsx[0]);
        assertEquals('K', xlsx[1]);
        String disposition = export.headers().firstValue("Content-Disposition").orElse("");
        assertTrue(disposition.contains("attachment"), "导出响应应带 Content-Disposition: " + disposition);

        // ---------- 8. 回收站 → 彻底删除（级联）----------
        assertEquals(200, send("PUT", "/api/survey/recycle/" + surveyId, null).status);
        assertEquals(200, send("DELETE", "/api/survey/permanent/" + surveyId, null).status);
        assertEquals(400, send("GET", "/api/survey/" + surveyId, null).status, "删除后再查应 400");

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_answer WHERE survey_id = ?", Integer.class, surveyId),
                "彻底删除后答卷应被级联删除");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_question WHERE survey_id = ?", Integer.class, surveyId),
                "题目应被级联删除");
    }

    /** 兜底清理：用例失败中断也不会在测试库里留下垃圾。 */
    @AfterEach
    void cleanup() {
        if (surveyId != null) {
            jdbc.update("DELETE d FROM t_answer_detail d JOIN t_answer a ON d.answer_id = a.id WHERE a.survey_id = ?", surveyId);
            jdbc.update("DELETE FROM t_answer WHERE survey_id = ?", surveyId);
            jdbc.update("DELETE o FROM t_option o JOIN t_question q ON o.question_id = q.id WHERE q.survey_id = ?", surveyId);
            jdbc.update("DELETE FROM t_question WHERE survey_id = ?", surveyId);
            jdbc.update("DELETE FROM t_survey_version WHERE survey_id = ?", surveyId);
            jdbc.update("DELETE FROM t_survey WHERE id = ?", surveyId);
        }
        if (username != null) {
            List<Integer> ids = jdbc.queryForList("SELECT id FROM t_user WHERE username = ?", Integer.class, username);
            for (Integer uid : ids) {
                jdbc.update("DELETE FROM t_refresh_token WHERE user_id = ?", uid);
                jdbc.update("DELETE FROM t_login_log WHERE user_id = ?", uid);
                jdbc.update("DELETE FROM t_notification WHERE user_id = ?", uid);
                jdbc.update("DELETE FROM t_user WHERE id = ?", uid);
            }
        }
    }

    // ==================== 工具方法 ====================

    private record Res(int status, String body) {
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private Res send(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Accept", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body), StandardCharsets.UTF_8));
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Res(r.statusCode(), r.body());
    }

    private HttpResponse<byte[]> download(String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode json(String body) throws Exception {
        return om.readTree(body);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 在 [{content,count}] 里找某个选项的计数（找不到返回 -1，便于断言失败时看出差异）。 */
    private static int countOf(JsonNode chartData, String content) {
        for (JsonNode row : chartData) {
            if (content.equals(row.path("content").asText())) {
                return row.path("count").asInt();
            }
        }
        return -1;
    }
}
