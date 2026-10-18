package com.survey.controller;

import com.survey.entity.Answer;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.entity.User;
import com.survey.mapper.AnnouncementMapper;
import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.ErrorLogMapper;
import com.survey.mapper.LoginLogMapper;
import com.survey.mapper.NotificationMapper;
import com.survey.mapper.OperationLogMapper;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.RefreshTokenMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.RoleMapper;
import com.survey.mapper.SurveyCollaboratorMapper;
import com.survey.mapper.SurveyIpMapper;
import com.survey.mapper.SurveyReviewMapper;
import com.survey.mapper.SurveyVersionMapper;
import com.survey.mapper.UserMapper;
import com.survey.mapper.WalletMapper;
import com.survey.mapper.WalletTransactionMapper;
import com.survey.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端点权限的「运行时」矩阵 —— 走真实 Spring MVC 栈（过滤器 + 切面 + 拦截器 + 统一异常处理器），
 * 但**不连库**：13 个 Mapper 与 DataSource 全部换成 Mockito mock。
 *
 * ── 它补的是哪块空白 ───────────────────────────────────
 *   《待解决问题》C4 的原话：现有 174 例都是纯单测，"验不到 Controller 上有没有挂
 *   {@code @RequireAdmin} 这一层"。确实如此：
 *     · {@code SurveyControllerRoutingTest} 只看注解有没有写；
 *     · {@code AdminCheckAspectTest} 只证明切面本身按角色拒绝；
 *     · 两者都不证明"某个管理端点真的经过了切面"——
 *       注解漏挂、切点写错、端点被别的 Controller 抢先映射，三种都能让上面两个测试保持绿色。
 *   本类用真正的 MockMvc 请求打每一个端点，逐档断言：
 *     · 公开档：不带 token 也不能 401；
 *     · 需登录档（含归属档）：不带 token 必须 401；
 *     · 管理档：普通用户 token 必须 400「无权限」；管理员 token 必须**不被这道闸门拦下**。
 *
 * ── 为什么能保证"不碰真库" ──────────────────────────────
 *   DataSource 与全部 Mapper 都是 mock ⇒ 任何一条 SQL 都不可能发出去。
 *   这条约束很重要：本机 3306 是**用户的活库**，测试若真连库，跑一遍就可能写脏数据。
 *   ★ Mockito 的默认答案对集合返回空列表、对 int 返回 0，所以大部分列表端点会拿到空数据，
 *     正好够验证"权限放行/拦截"，不需要造业务数据。
 *
 * ── 为什么 Mockito 只能 mock 接口/非 final 类 ────────────
 *   src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker 是
 *   {@code mock-maker-subclass}（沙箱禁 Attach API）。Mapper 全是接口，不受影响。
 *
 * ── 维护口径 ───────────────────────────────────────────
 *   ALL_ENDPOINTS 是"94"这个数字在测试侧的钉子：新增/删除端点后，本类会先红，
 *   逼你把它归进 PUBLIC / ADMIN / 其余（需登录）三档之一。改动时同步
 *   docs/问卷系统-端点权限审计表.md 与《交接文档》的端点数。
 */
@SpringBootTest(properties = {
        // 占位数据源：真连接由 @MockitoBean DataSource 顶掉，这里只是让自动配置有值可绑
        "spring.datasource.url=jdbc:mysql://127.0.0.1:1/survey_db_not_used_in_tests",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        // JwtUtil 启动即校验密钥（≥32 字节），测试自带一枚
        "jwt.secret=test-only-secret-for-mockmvc-authz-0123456789abcdef",
        // 不碰 Redis：缓存绕过、限流走进程内计数（两条链路都有降级分支）
        "app.cache.stats-enabled=false",
        "app.rate-limit.redis-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EndpointAuthMatrixTest {

    // ==================== 一、94 个端点的三档分类（与审计表逐条对应） ====================

    /** 公开档 12 条（JwtAuthFilter 白名单）。 */
    private static final Set<String> PUBLIC = Set.of(
            "POST /api/user/register",
            "POST /api/user/login",
            "POST /api/user/refresh",
            "GET /api/captcha/slider",
            "POST /api/captcha/verify",
            "GET /api/survey/share/{shareCode}",
            "GET /api/survey/result/{shareCode}",
            "POST /api/survey/view/{shareCode}",
            "GET /api/question/list/{surveyId}",
            "POST /api/answer/submit-share/{shareCode}",
            "GET /api/announcement/active",
            "GET /api/health"
    );

    /** 管理档 26 条（@RequireAdmin，类级或方法级）。 */
    private static final Set<String> ADMIN = Set.of(
            "GET /api/wallet/admin/page",
            "POST /api/wallet/admin/adjust",
            "GET /api/wallet/admin/reconcile",
            "GET /api/review/list",
            "POST /api/review/{id}/handle",
            "POST /api/survey/unsuspend/{id}",
            "GET /api/user/page",
            "GET /api/user/page-deleted",
            "DELETE /api/user/delete/{id}",
            "PUT /api/user/restore/{id}",
            "GET /api/survey/page",
            "GET /api/survey/list-with-response-count",
            "GET /api/survey/system-overview",
            "PUT /api/survey/set-template/{id}",
            "GET /api/log/page",
            "GET /api/error-log/page",
            "GET /api/error-log/count",
            "DELETE /api/error-log/{id}",
            "DELETE /api/error-log/clear",
            "POST /api/announcement/add",
            "GET /api/announcement/list",
            "PUT /api/announcement/status/{id}",
            "DELETE /api/announcement/delete/{id}",
            "POST /api/demo/init",
            "DELETE /api/demo/reset",
            "POST /api/template-seeder/init"
    );

    /** 全部 94 条（= 审计表的活行；顺序无关）。 */
    private static final Set<String> ALL_ENDPOINTS = Set.of(
            "GET /api/wallet",
            "GET /api/wallet/pricing",
            "GET /api/wallet/admin/page",
            "GET /api/wallet/admin/reconcile",
            "GET /api/wallet/transactions",
            "POST /api/wallet/admin/adjust",
            "POST /api/wallet/recharge",
            "DELETE /api/collaborator/self/{surveyId}",
            "POST /api/collaborator/self/{surveyId}/apply",
            "GET /api/review/list",
            "POST /api/review/{id}/handle",
            "GET /api/review/my",
            "POST /api/survey/unsuspend/{id}",
            "POST /api/survey/{surveyId}/report",
            "DELETE /api/survey/{surveyId}/collaborators/{targetUserId}",
            "GET /api/collaborator/shared",
            "GET /api/survey/{surveyId}/collaborators",
            "POST /api/survey/{surveyId}/collaborators",
            "PUT /api/survey/{surveyId}/collaborators/{targetUserId}",
            "DELETE /api/announcement/delete/{id}",
            "DELETE /api/demo/reset",
            "DELETE /api/error-log/clear",
            "DELETE /api/error-log/{id}",
            "DELETE /api/notification/clear",
            "DELETE /api/notification/{id}",
            "DELETE /api/survey/permanent/{id}",
            "DELETE /api/user/account",
            "DELETE /api/user/delete/{id}",
            "GET /api/announcement/active",
            "GET /api/announcement/list",
            "GET /api/answer/count/{surveyId}",
            "GET /api/answer/detail/{answerId}",
            "GET /api/answer/export/{surveyId}",
            "GET /api/answer/list/{surveyId}",
            "GET /api/answer/quality/{surveyId}",
            "GET /api/answer/statistics/survey/{surveyId}",
            "GET /api/answer/statistics/{questionId}",
            "GET /api/answer/texts/{questionId}",
            "GET /api/captcha/slider",
            "GET /api/error-log/count",
            "GET /api/error-log/page",
            "GET /api/health",
            "GET /api/log/page",
            "GET /api/notification/list",
            "GET /api/notification/unread",
            "GET /api/question/list/{surveyId}",
            "GET /api/survey/list-with-response-count",
            "GET /api/survey/my",
            "GET /api/survey/my-statistics",
            "GET /api/survey/page",
            "GET /api/survey/recycle/page",
            "GET /api/survey/result/{shareCode}",
            "GET /api/survey/share/{shareCode}",
            "GET /api/survey/system-overview",
            "GET /api/survey/templates",
            "GET /api/survey/version/diff/{fromId}/{toId}",
            "GET /api/survey/version/list/{surveyId}",
            "GET /api/survey/version/{versionId}",
            "GET /api/survey/{id}",
            "GET /api/user/login-logs",
            "GET /api/user/page",
            "GET /api/user/page-deleted",
            "GET /api/user/profile",
            "POST /api/ai/generate",
            "POST /api/announcement/add",
            "POST /api/answer/submit-share/{shareCode}",
            "POST /api/answer/submit/{surveyId}",
            "POST /api/captcha/verify",
            "POST /api/demo/init",
            "POST /api/question/save/{surveyId}",
            "POST /api/survey/add",
            "POST /api/survey/copy/{id}",
            "POST /api/survey/from-template/{templateId}",
            "POST /api/survey/publish/{id}",
            "POST /api/survey/{id}/request-restore",
            "POST /api/survey/version/rollback/{versionId}",
            "POST /api/survey/view/{shareCode}",
            "POST /api/template-seeder/init",
            "POST /api/user/login",
            "POST /api/user/logout",
            "POST /api/user/refresh",
            "POST /api/user/register",
            "PUT /api/announcement/status/{id}",
            "PUT /api/notification/read-all",
            "PUT /api/notification/read/{id}",
            "PUT /api/survey/recycle/{id}",
            "PUT /api/survey/restore/{id}",
            "PUT /api/survey/set-template/{id}",
            "PUT /api/survey/update",
            "PUT /api/user/avatar",
            "PUT /api/user/contact",
            "PUT /api/user/nickname",
            "PUT /api/user/password",
            "PUT /api/user/restore/{id}"
    );

    // ==================== 二、被测依赖 ====================

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /** 顶掉真数据源：健康检查与 MyBatis 都拿到它，任何 getConnection 都不会碰到 MySQL。 */
    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private SurveyMapper surveyMapper;
    @MockitoBean
    private QuestionMapper questionMapper;
    @MockitoBean
    private OptionMapper optionMapper;
    @MockitoBean
    private AnswerMapper answerMapper;
    @MockitoBean
    private AnswerDetailMapper answerDetailMapper;
    @MockitoBean
    private NotificationMapper notificationMapper;
    @MockitoBean
    private OperationLogMapper operationLogMapper;
    @MockitoBean
    private ErrorLogMapper errorLogMapper;
    @MockitoBean
    private LoginLogMapper loginLogMapper;
    @MockitoBean
    private RefreshTokenMapper refreshTokenMapper;
    @MockitoBean
    private AnnouncementMapper announcementMapper;
    @MockitoBean
    private SurveyVersionMapper surveyVersionMapper;
    /**
     * ★ H2 新增：协作者表也必须 mock。
     *   漏掉它的后果不是"少一条数据"，而是 SurveyAccessService 拿着**真实** Mapper
     *   去查一个被 mock 掉的数据源 —— 归属类用例会以 500 收场，看起来像权限逻辑坏了。
     *   默认返回 null（= 不是协作者），所以原有 74 端点的"他人问卷 400"口径不变。
     */
    @MockitoBean
    private SurveyCollaboratorMapper surveyCollaboratorMapper;

    /**
     * ★ 系统总览新增计数后必须补 mock：SurveyService.getSystemOverview 会读
     *   t_survey_review 的待处理数；没 mock 时它拿真实代理去访问被 mock 掉的数据源，
     *   adminReadOnlyEndpoints 的 /system-overview 会 500。
     *   SurveyIpMapper 同理（提交守卫会读它，虽然这条用例没走到，但保持一致）。
     */
    @MockitoBean
    private SurveyReviewMapper surveyReviewMapper;
    @MockitoBean
    private SurveyIpMapper surveyIpMapper;

    /**
     * ★ H5 新增：钱包相关 Mapper 也要 mock。
     *   SurveyService / UserService 现在依赖 WalletService（它再依赖这两个 Mapper），
     *   不 mock 的话它们会拿真实代理去访问被 mock 掉的数据源 —— 一旦某条用例
     *   走到 publish/register 的成功路径，就会以 500 收场，看起来像权限逻辑坏了。
     */
    @MockitoBean
    private WalletMapper walletMapper;
    @MockitoBean
    private WalletTransactionMapper walletTransactionMapper;

    private String userToken;
    private String otherUserToken;
    private String adminToken;

    private static final int USER_ID = 1;
    private static final int OTHER_ID = 2;
    private static final int ADMIN_ID = 3;
    private static final int DELETED_ID = 4;
    private static final int STALE_TOKEN_ID = 5;

    @BeforeEach
    void setUp() throws Exception {
        // JwtAuthFilter 每个受保护请求都会查一次用户
        lenient().when(userMapper.findById(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return switch (id) {
                case USER_ID -> user(USER_ID, "user1", "user", null);
                case OTHER_ID -> user(OTHER_ID, "user2", "user", null);
                case ADMIN_ID -> user(ADMIN_ID, "admin1", "admin", null);
                // 已注销 / 已物理删除：findById 查不到 → 过滤器必须 401
                case DELETED_ID -> null;
                // 改过密码：token_valid_after 晚于这枚 token 的 iat → 过滤器必须 401
                case STALE_TOKEN_ID -> user(STALE_TOKEN_ID, "stale", "user",
                        LocalDateTime.now().plusMinutes(5));
                default -> null;
            };
        });

        // 归属场景用的问卷：2 自己的草稿 / 3 自己的已发布 / 4 别人的草稿 /
        // 5 自己在回收站 / 6 自己被下架（SUSPENDED；status 仍是 1）/ 7 别人已发布
        lenient().when(surveyMapper.findById(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return switch (id) {
                case 2 -> survey(2, USER_ID, 0, 0);
                case 3 -> survey(3, USER_ID, 1, 0);
                case 4 -> survey(4, OTHER_ID, 0, 0);
                case 5 -> survey(5, USER_ID, 1, 1);
                case 6 -> {
                    // 举报审核下架的问卷：status 不变（还是 1），只有 lifecycle 变成 SUSPENDED
                    Survey suspended = survey(6, USER_ID, 1, 0);
                    suspended.setLifecycle("SUSPENDED");
                    yield suspended;
                }
                case 7 -> survey(7, OTHER_ID, 1, 0);
                default -> null;
            };
        });

        // 反查归属用：题目 / 答卷本身不含身份，必须先由它们反查出 surveyId 再校归属
        lenient().when(questionMapper.findById(anyInt())).thenAnswer(inv -> {
            Question q = new Question();
            q.setId(1);
            q.setSurveyId(4);   // 属于"别人"的问卷
            q.setTitle("题目");
            return q;
        });
        lenient().when(answerMapper.findById(anyInt())).thenAnswer(inv -> {
            Answer a = new Answer();
            a.setId(1);
            a.setSurveyId(4);
            return a;
        });

        // 健康检查真的探一次库 —— 换个可用的假连接，让 /api/health 走 200 分支
        Connection connection = mock(Connection.class);
        lenient().when(connection.isValid(anyInt())).thenReturn(true);
        lenient().when(dataSource.getConnection()).thenReturn(connection);

        userToken = jwtUtil.generateToken(USER_ID, "user1", "user");
        otherUserToken = jwtUtil.generateToken(OTHER_ID, "user2", "user");
        adminToken = jwtUtil.generateToken(ADMIN_ID, "admin1", "admin");
    }

    // ==================== 三、端点清单与分类的自洽性 ====================

    @Test
    @DisplayName("★ 端点清单 = 实际注册的 /api 映射 = 94；且公开 12 + 管理 26 + 其余 56")
    void endpointInventory_matchesRegisteredMappings() {
        Set<String> registered = registeredApiEndpoints();
        assertEquals(94, registered.size(),
                "注册的 /api 端点数变了。请同步 docs/问卷系统-端点权限审计表.md 与本类 ALL_ENDPOINTS。实际："
                        + registered);
        assertEquals(new TreeSet<>(ALL_ENDPOINTS), new TreeSet<>(registered),
                "本类清单与注册映射有差异（多/少各列在前面）：多="
                        + diff(registered, ALL_ENDPOINTS) + " 少=" + diff(ALL_ENDPOINTS, registered));
        assertEquals(12, PUBLIC.size(), "公开档固定 12 条（新增 GET /api/survey/result/{shareCode}）");
        assertEquals(26, ADMIN.size(), "管理档固定 26 条（@RequireAdmin 方法级 + 类级，含钱包管理 3 条）");
        assertEquals(94 - 12 - 26, ALL_ENDPOINTS.size() - PUBLIC.size() - ADMIN.size());
        // 两档不能交叉
        for (String p : PUBLIC) {
            assertFalse(ADMIN.contains(p), p + " 不能同时属于公开档与管理档");
        }
    }

    // ==================== 四、逐档验证 ====================

    @Test
    @DisplayName("★ 公开档 12 条：不带 token 也不能 401（否则登录页/匿名答题/探针全挂）")
    void publicEndpoints_shouldNotRequireToken() throws Exception {
        for (String endpoint : PUBLIC) {
            MvcResult r = mvc.perform(build(endpoint)).andReturn();
            assertNotEquals(401, r.getResponse().getStatus(),
                    "公开端点不该 401：" + endpoint + " → " + r.getResponse().getContentAsString());
        }
    }

    @Test
    @DisplayName("★ 非公开的 82 条：不带 token 一律 401（不能只靠 Controller 里那句 if）")
    void protectedEndpoints_withoutToken_shouldBe401() throws Exception {
        for (String endpoint : ALL_ENDPOINTS) {
            if (PUBLIC.contains(endpoint)) {
                continue;
            }
            MvcResult r = mvc.perform(build(endpoint)).andReturn();
            assertEquals(401, r.getResponse().getStatus(),
                    "需登录端点不带 token 应 401：" + endpoint + " → " + r.getResponse().getContentAsString());
        }
    }

    @Test
    @DisplayName("★ 管理档 26 条：普通用户 token → 403 +「无权限」（切面真的生效了）")
    void adminEndpoints_withNormalUser_shouldBe403Forbidden() throws Exception {
        for (String endpoint : ADMIN) {
            MvcResult r = mvc.perform(build(endpoint).header("Authorization", "Bearer " + userToken))
                    .andReturn();
            assertEquals(403, r.getResponse().getStatus(),
                    "管理端点用普通用户 token 应 403：" + endpoint + " → " + r.getResponse().getContentAsString());
            assertTrue(r.getResponse().getContentAsString().contains("无权限"),
                    "文案应为「无权限」（说明是切面拦的，不是别的原因）：" + endpoint
                            + " → " + r.getResponse().getContentAsString());
        }
    }

    @Test
    @DisplayName("★ 只读管理端点：管理员 token → 200（证明上一条不是因为 500 才「不 400」）")
    void adminReadOnlyEndpoints_withAdminToken_shouldReturn200() throws Exception {
        // ★ 这里**只打只读端点**：POST /api/demo/init、DELETE /api/demo/reset 这类写/破坏性端点，
        //   一旦将来有人去掉 @MockitoBean，就会对真库动手。它们的"角色闸门"与只读端点
        //   走的是同一个切面，由 adminEndpoints_withNormalUser_shouldBe400NoPermission 覆盖。
        List<String> readOnly = List.of(
                "GET /api/user/page",
                "GET /api/user/page-deleted",
                "GET /api/survey/page",
                "GET /api/survey/list-with-response-count",
                "GET /api/survey/system-overview",
                "GET /api/log/page",
                "GET /api/error-log/page",
                "GET /api/error-log/count",
                "GET /api/announcement/list"
        );
        for (String endpoint : readOnly) {
            mvc.perform(build(endpoint).header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }
    }

    // ==================== 五、身份头不可伪造 ====================

    @Test
    @DisplayName("★ 普通用户 token + 伪造 X-User-Role: admin → 仍 403「无权限」（过滤器覆盖请求头）")
    void forgedRoleHeader_shouldBeOverwrittenByFilter() throws Exception {
        mvc.perform(get("/api/user/page")
                        .header("Authorization", "Bearer " + userToken)
                        .header("X-User-Role", "admin")
                        .header("X-User-Id", String.valueOf(ADMIN_ID)))
                .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("无权限"));
    }

    @Test
    @DisplayName("★ 白名单路径也会剥掉伪造身份：匿名 + 伪造 admin 头 → 读不到别人的草稿题目")
    void whitelist_stripsForgedIdentity() throws Exception {
        // 问卷 4 是他人草稿：若身份头没被覆盖，admin 会放行 → 200；正确行为是 400
        mvc.perform(get("/api/question/list/4")
                        .header("X-User-Role", "admin")
                        .header("X-User-Id", String.valueOf(ADMIN_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("问卷尚未发布，无权查看"));
    }

    // ==================== 六、归属校验（需登录 + 归属档） ====================

    @Test
    @DisplayName("★ GET /survey/{id}：自己的草稿 200 / 别人草稿 400 / 别人已发布 200 / 管理员 200")
    void surveyGet_visibilityMatrix() throws Exception {
        mvc.perform(get("/api/survey/2").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/survey/4").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权限查看他人未发布的问卷"));

        mvc.perform(get("/api/survey/7").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/survey/4").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 回收站优先：已发布问卷进了回收站，非作者读到的是「已在回收站」而不是 200")
    void surveyGet_recycleBinRule() throws Exception {
        mvc.perform(get("/api/survey/5").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/survey/5").header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("问卷已在回收站中"));
    }

    @Test
    @DisplayName("★ 答卷统计类端点：别人的问卷 400「无权查看他人问卷的数据」，自己的放行")
    void answerStatistics_ownershipMatrix() throws Exception {
        mvc.perform(get("/api/answer/statistics/survey/4").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权查看他人问卷的数据"));

        mvc.perform(get("/api/answer/statistics/survey/4").header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/answer/list/4").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权查看他人问卷的数据"));

        mvc.perform(get("/api/answer/quality/4").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权查看他人问卷的数据"));
    }

    @Test
    @DisplayName("★ 反查归属：题目 id / 答卷 id 要先反查所属问卷再校归属（改 URL 也越不了权）")
    void textsAndDetail_reverseLookupOwnership() throws Exception {
        mvc.perform(get("/api/answer/texts/1").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权查看他人问卷的数据"));

        mvc.perform(get("/api/answer/detail/1").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权查看他人问卷的数据"));

        // 作者本人：放行（题目/答卷属于问卷 4，owner = OTHER_ID）
        mvc.perform(get("/api/answer/texts/1").header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 答卷列表已分页：返回 PageResult（total/list/pageNum/pageSize），不再是裸数组")
    void answerList_shouldReturnPageResult() throws Exception {
        mvc.perform(get("/api/answer/list/4")
                        .param("pageNum", "2")
                        .param("pageSize", "5")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.list").isArray())
                .andExpect(jsonPath("$.pageNum").value(2))
                .andExpect(jsonPath("$.pageSize").value(5));
    }

    @Test
    @DisplayName("★ 页码天文数字不会 500：pageNum 夹到 10000（防 (pageNum-1)×pageSize 溢出成负数 OFFSET）")
    void answerList_hugePageNum_shouldBeClampedNot500() throws Exception {
        mvc.perform(get("/api/answer/list/4")
                        .param("pageNum", String.valueOf(Integer.MAX_VALUE))
                        .param("pageSize", "100")
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageNum").value(10000))
                .andExpect(jsonPath("$.pageSize").value(100));
    }

    @Test
    @DisplayName("★ 题目列表：他人草稿 400；作者本人 200 且返回空列表")
    void questionList_ownershipMatrix() throws Exception {
        mvc.perform(get("/api/question/list/4").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("问卷尚未发布，无权查看"));

        mvc.perform(get("/api/question/list/4").header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("★ 版本历史：他人问卷 400「无权限查看版本历史他人问卷」；作者本人 200")
    void versionList_ownershipMatrix() throws Exception {
        mvc.perform(get("/api/survey/version/list/4").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权限查看版本历史他人问卷"));

        mvc.perform(get("/api/survey/version/list/4").header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("★ 写操作同口径：把别人的问卷移入回收站 → 400「无权限操作他人问卷」")
    void surveyRecycle_otherOwner_shouldBeRejected() throws Exception {
        mvc.perform(put("/api/survey/recycle/4").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无权限操作他人问卷"));
    }

    @Test
    @DisplayName("★ 作者申请恢复上架：作者 200 / 他人 400 / 未被下架 400（归属在 Service 判，不靠前端显隐）")
    void requestRestore_ownershipMatrix() throws Exception {
        // 6 是 USER_ID 名下、lifecycle=SUSPENDED 的问卷
        mvc.perform(post("/api/survey/6/request-restore").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        mvc.perform(post("/api/survey/6/request-restore").header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("只能为自己的问卷申请恢复"));

        // 3 是自己名下、但正常已发布的问卷：没被下架就没什么可申请
        mvc.perform(post("/api/survey/3/request-restore").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("该问卷没有被下架，无需申请"));
    }

    // ==================== 七、认证边界与错误口径 ====================

    @Test
    @DisplayName("token 无效 / 缺失 / 账号已注销 / 票已作废 → 各自 401，且文案可区分")
    void tokenEdgeCases_shouldBe401WithDistinctMessages() throws Exception {
        mvc.perform(get("/api/user/profile").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("token 无效或已过期"));

        mvc.perform(get("/api/user/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("未登录或 token 缺失"));

        String deletedUserToken = jwtUtil.generateToken(DELETED_ID, "ghost", "user");
        mvc.perform(get("/api/user/profile").header("Authorization", "Bearer " + deletedUserToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号不存在或已注销，请重新登录"));

        String staleToken = jwtUtil.generateToken(STALE_TOKEN_ID, "stale", "user");
        mvc.perform(get("/api/user/profile").header("Authorization", "Bearer " + staleToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("登录状态已失效，请重新登录"));
    }

    @Test
    @DisplayName("已下线的字面量端点 → 404 +「已下线」；非数字 id → 400；不存在的数字 id → 400")
    void routingAndValidation_branches() throws Exception {
        mvc.perform(get("/api/survey/list").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("已下线")));

        mvc.perform(get("/api/survey/abc").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("问卷 ID 必须是数字"));

        mvc.perform(get("/api/survey/1").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("问卷不存在，ID：1"));
    }

    @Test
    @DisplayName("★ 请求体不是合法 JSON → 400「需要合法的 JSON」，不是 500（前端才不会以为服务端挂了）")
    void malformedJson_shouldBe400Not500() throws Exception {
        mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求体格式不正确（需要合法的 JSON）"));
    }

    // ==================== 八、工具方法 ====================

    /** 少数管理端点要求必填查询参数；缺参数会在**参数绑定阶段**先 400，根本走不到切面。 */
    private static final java.util.Map<String, String> REQUIRED_QUERY = java.util.Map.of(
            "PUT /api/announcement/status/{id}", "status=1",
            "PUT /api/survey/set-template/{id}", "isTemplate=1"
    );

    /**
     * 带 @Valid 的 POST 端点：必须给一份**能通过校验**的 JSON，否则 400 会先于切面发生
     * （参数校验在进入 Controller 方法之前，而 @RequireAdmin 切面在方法调用时）。
     * ★ 这不是给端点开后门：它只用于"普通用户应被 403"的矩阵用例，body 合法才有机会走到切面。
     */
    private static final java.util.Map<String, String> REQUIRED_BODY = java.util.Map.of(
            "POST /api/wallet/admin/adjust",
            "{\"userId\":1,\"amount\":1,\"reason\":\"matrix\",\"idempotencyKey\":\"matrix:adjust\"}"
    );

    /** 把 "METHOD /api/xxx/{var}" 变成一次 MockMvc 请求（路径变量填 1，POST/PUT 带 JSON）。 */
    private MockHttpServletRequestBuilder build(String endpoint) {
        int sp = endpoint.indexOf(' ');
        String method = endpoint.substring(0, sp);
        String path = endpoint.substring(sp + 1).replaceAll("\\{[^}]+}", "1");
        String query = REQUIRED_QUERY.get(endpoint);
        if (query != null) {
            path = path + "?" + query;
        }
        String body = REQUIRED_BODY.getOrDefault(endpoint, "{}");
        return switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path).contentType(MediaType.APPLICATION_JSON).content(body);
            case "PUT" -> put(path).contentType(MediaType.APPLICATION_JSON).content(body);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException("未覆盖的 HTTP 方法：" + endpoint);
        };
    }

    /** 从 RequestMappingHandlerMapping 里取出真正注册的 /api 端点（类级前缀已拼好）。 */
    private Set<String> registeredApiEndpoints() {
        Set<String> out = new TreeSet<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty() || info.getPathPatternsCondition() == null) {
                continue;
            }
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod m : methods) {
                    out.add(m.name() + " " + pattern);
                }
            }
        }
        return out;
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> d = new LinkedHashSet<>(a);
        d.removeAll(b);
        return d;
    }

    private static User user(int id, String username, String role, LocalDateTime tokenValidAfter) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setNickname(username);
        u.setRole(role);
        u.setTokenValidAfter(tokenValidAfter);
        return u;
    }

    private static Survey survey(int id, int ownerId, int status, int deleted) {
        Survey s = new Survey();
        s.setId(id);
        s.setUserId(ownerId);
        s.setStatus(status);
        s.setDeleted(deleted);
        s.setTitle("问卷-" + id);
        return s;
    }
}
