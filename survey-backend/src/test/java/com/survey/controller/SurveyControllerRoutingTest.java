package com.survey.controller;

import com.survey.common.BizException;
import com.survey.entity.Survey;
import com.survey.service.SurveyService;
import com.survey.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * 「端点到底还在不在」的双重验证 —— 不连库、不起 Spring 容器（与全项目其它单测同一风格）。
 *
 * ── 为什么需要这样一个测试 ──────────────────────────────
 *   下线"前端从不调用"的 URL 有两个容易出错的环节：
 *     ① **删没删干净**：光改源码不够 —— 跑起来的到底是不是新 class，从源码文本上看不出来。
 *        所以第一组用例直接在**字节码层面**反射 controller，把当前签名里所有
 *        @XxxMapping 的路径拼出来，断言被删的路径一条都不在。
 *     ② **删出副作用**：删掉 {@code /list} 之后，同一个 URL 会被 {@code GET /{id}} 接住，
 *        先抛"参数「id」格式不正确"（400）—— 文案指向根本不存在的 id 参数，前端据此排错会跑偏。
 *        第二组用例就钉住修复后的行为：这类**已下线的字面量路径必须 404 + 明确文案**，
 *        而正常数字 id 与真正的非数字 id 仍各自得到对应的结果。
 *
 * ── ★ 还要钉住"不能删的东西" ────────────────────────────
 *   删端点这件事有反面，下面两个端点**不许**因为"前端零调用"被删：
 *     · {@code POST /api/answer/submit/{surveyId}} 是"答题人身份落库"的唯一路径
 *       （删了 userId 就永远是 NULL）；
 *     · {@code PUT /api/survey/set-template/{id}} 是"设为/取消模板"的唯一写入端点
 *       （删了模板只能改库）。
 *   下面的用例把它们钉住，防止再被"零调用"这个理由误删。
 *
 * ── 与"真实 HTTP 验证"的关系 ────────────────────────────
 *   这些用例管"代码与注解"；跑起来的 8081 实例管"运行时路由"。两者互补，缺一不可。
 */
@ExtendWith(MockitoExtension.class)
class SurveyControllerRoutingTest {

    @Mock
    private SurveyService surveyService;
    @Mock
    private UserService userService;

    @InjectMocks
    private SurveyController controller;

    private static final String BEARER_ADMIN_ROLE = "admin";
    private static final Integer ADMIN_ID = 1;

    // ==================== 一、已删除的端点不能残留任何映射 ====================

    /** 已下线的端点：反射收集后逐条断言其映射"不在"。 */
    @Test
    @DisplayName("★ 字节码层面确认：SurveyController 里已无 /list、/statistics、/recycle、/delete/{id} 的映射")
    void surveyController_shouldNotContainRemovedMappings() {
        List<String> all = collectMappings(SurveyController.class);
        assertFalse(all.contains("GET /api/survey/list"), "GET /api/survey/list 应已删除，实际仍有：" + all);
        assertFalse(all.contains("GET /api/survey/statistics"), "GET /api/survey/statistics 应已删除");
        assertFalse(all.contains("GET /api/survey/recycle"), "GET /api/survey/recycle 应已删除");
        assertFalse(all.contains("DELETE /api/survey/delete/{id}"), "DELETE /api/survey/delete/{id} 应已删除");
    }

    @Test
    @DisplayName("★ 关键字面确认：UserController 的 /list 与 /count 没了；而 AnswerController 的登录态提交**必须在**")
    void otherControllers_mappingChecks() {
        // ★ POST /api/answer/submit/{surveyId} 是**有意保留的扩展点**：
        //   它是唯一能把答题人身份写进 t_answer.user_id 的路径（submit-share 传的 userId 恒为 null），
        //   也是"答卷防重 / 我的答题历史 / 实名问卷"的前提。下面这条断言把它**钉住**，
        //   防止下一个人看到"前端零调用"又把它删掉（删了这些能力就整体失效）。
        List<String> answer = collectMappings(AnswerController.class);
        assertTrue(answer.contains("POST /api/answer/submit/{surveyId}"),
                "登录态提交端点是**有意保留的扩展点**，不该被删。实际映射：" + answer);
        assertTrue(answer.contains("POST /api/answer/submit-share/{shareCode}"),
                "匿名提交端点当然也要在");

        List<String> user = collectMappings(UserController.class);
        assertFalse(user.contains("GET /api/user/list"), "GET /api/user/list 应已删除，实际仍有：" + user);
        assertFalse(user.contains("GET /api/user/count"), "GET /api/user/count 应已删除");
    }

    @Test
    @DisplayName("★ 模板管理端点必须在：PUT /api/survey/set-template/{id}")
    void templateManagementEndpoint_mustExist() {
        List<String> all = collectMappings(SurveyController.class);
        assertTrue(all.contains("PUT /api/survey/set-template/{id}"),
                "「设为/取消模板」是唯一写入端点（删了模板只能改库），映射不能丢。实际：" + all);
    }

    @Test
    @DisplayName("防删过头：保留的端点映射仍在（/page、/list-with-response-count、/permanent 等）")
    void keptMappings_shouldStillExist() {
        List<String> all = collectMappings(SurveyController.class);
        assertTrue(all.contains("GET /api/survey/page"), "GET /api/survey/page 必须保留，实际：" + all);
        assertTrue(all.contains("GET /api/survey/list-with-response-count"), "list-with-response-count 必须保留");
        assertTrue(all.contains("DELETE /api/survey/permanent/{id}"), "permanent 必须保留");
        assertTrue(all.contains("PUT /api/survey/recycle/{id}"), "recycle/{id} 必须保留");
        assertTrue(all.contains("GET /api/survey/recycle/page"), "recycle/page 必须保留");
        assertTrue(all.contains("GET /api/survey/templates"), "templates 必须保留");
        assertTrue(all.contains("GET /api/survey/{id}"), "GET /{id} 必须保留");
    }

    // ==================== 二、被 /{id} 接住的已下线路径必须 404 ====================

    @Test
    @DisplayName("★ 已下线的字面量路径（list/statistics/recycle/delete）→ 404 + 「已下线」文案，而不是 400「参数 id 格式不正确」")
    void removedLiteralPaths_shouldProduce404NotMisleading400() {
        for (String removed : List.of("list", "statistics", "recycle", "delete")) {
            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.getOne(removed, ADMIN_ID, BEARER_ADMIN_ROLE),
                    "路径 /api/survey/" + removed + " 应抛 404，而不是被当成 id 去解析");
            assertEquals(404, ex.getStatusCode().value(), "路径 /api/survey/" + removed + " 的状态码应为 404");
            assertTrue(ex.getReason() != null && ex.getReason().contains("已下线"),
                    "文案应说明端点已下线，实际=" + ex.getReason());
        }
    }

    @Test
    @DisplayName("真正的非数字 id（如 abc）→ 400「问卷 ID 必须是数字」（与上面的 404 口径区分开）")
    void nonNumericId_shouldBe400WithClearMessage() {
        BizException ex = assertThrows(BizException.class,
                () -> controller.getOne("abc", ADMIN_ID, BEARER_ADMIN_ROLE));
        assertEquals("问卷 ID 必须是数字", ex.getMessage());
    }

    @Test
    @DisplayName("正常数字 id 走原逻辑：问卷不存在 → 400「问卷不存在，ID：...」")
    void numericId_shouldReachService() {
        when(surveyService.getById(anyInt())).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> controller.getOne("999999", ADMIN_ID, BEARER_ADMIN_ROLE));
        assertTrue(ex.getMessage().contains("问卷不存在"), "实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("正常数字 id 且问卷属于管理员 → 正常返回（确认解析后传下去的是整数）")
    void numericId_withVisibleSurvey_shouldReturnSurvey() {
        Survey survey = new Survey();
        survey.setId(13);
        survey.setUserId(2);
        survey.setStatus(0);
        when(surveyService.getById(13)).thenReturn(survey);

        Survey got = controller.getOne("13", ADMIN_ID, BEARER_ADMIN_ROLE);
        assertEquals(13, got.getId(), "解析后的整数 id 应原样用于查询");
    }

    // ==================== 工具：把 controller 上的映射注解拼成 "GET /api/xxx" ====================

    /**
     * 反射收集某个 Controller 的方法级映射（类级 @RequestMapping 作为前缀拼上）。
     * ★ 用注解**运行时反射**读，而不是读源码文本 —— 校验的是真正会被 Spring 看到的那份信息。
     */
    private static List<String> collectMappings(Class<?> controllerClass) {
        String prefix = "";
        RequestMapping classMapping = controllerClass.getAnnotation(RequestMapping.class);
        if (classMapping != null && classMapping.value().length > 0) {
            prefix = classMapping.value()[0];
        }
        List<String> result = new ArrayList<>();
        for (Method m : controllerClass.getDeclaredMethods()) {
            for (String[] pair : new String[][]{{"GET", "/"}, {"POST", "/"}, {"PUT", "/"}, {"DELETE", "/"}}) {
                String[] paths = pathsOf(m, pair[0]);
                for (String p : paths) {
                    result.add(pair[0] + " " + prefix + p);
                }
            }
        }
        return result;
    }

    private static String[] pathsOf(Method m, String httpMethod) {
        switch (httpMethod) {
            case "GET": {
                GetMapping a = m.getAnnotation(GetMapping.class);
                return a == null ? new String[0] : (a.value().length > 0 ? a.value() : new String[]{""});
            }
            case "POST": {
                PostMapping a = m.getAnnotation(PostMapping.class);
                return a == null ? new String[0] : (a.value().length > 0 ? a.value() : new String[]{""});
            }
            case "PUT": {
                PutMapping a = m.getAnnotation(PutMapping.class);
                return a == null ? new String[0] : (a.value().length > 0 ? a.value() : new String[]{""});
            }
            case "DELETE": {
                DeleteMapping a = m.getAnnotation(DeleteMapping.class);
                return a == null ? new String[0] : (a.value().length > 0 ? a.value() : new String[]{""});
            }
            default:
                return new String[0];
        }
    }
}
