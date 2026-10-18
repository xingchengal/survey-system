package com.survey.controller;

import com.survey.common.BizException;
import com.survey.service.AiGenerateService;
import com.survey.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * AI 智能出题：调用硅基流动的 Qwen 大模型，根据一句话需求生成整份问卷。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   MySurveys.vue 的「AI 生成」弹窗（输入主题 → 生成问卷 → 跳到编辑页）
 *
 * ── 权限 ──────────────────────────────────────────────
 *   需登录：从 X-User-Id 取当前用户，生成的问卷直接归属到该用户名下。
 *   （这个类是「正确取当前用户」的现成写法模板，其它 Controller 可照抄。）
 *
 * ── ★ 本类有多薄（职责边界，别把东西搬回来）────────────
 *   它只做三件事：**校验参数 → 调 Service → 组装响应**。
 *     外部 HTTP 调用 / Prompt 构建 / 响应解析 → service/QwenClient
 *     落库（短事务）与失败时的异常日志      → service/AiGenerateService
 *   ★ 一旦让本类自己持有 OkHttpClient、@Value 注入的密钥、JSON 解析器和异常日志拼装，
 *   就既难以单测，也让 Controller 层认识了一堆 okhttp 类型。
 *
 * ── 事务边界（改了会出事）──────────────────────────────
 *   本类**没有** @Transactional，generateAndSave 也没有：
 *   外部 LLM 的 readTimeout 是 120 秒，事务只包住落库的几条 INSERT。
 *   ★ 若把"调模型 + 失败重试 sleep"一起包进事务，最坏一个请求会占着数据库连接约 4 分钟，
 *   几个并发就能耗尽 HikariCP 连接池。
 *
 * ── 容错 ──────────────────────────────────────────────
 *   失败会由 Service 写进 t_error_log；遇到上游 429 会重试一次并给前端一句人话提示。
 *   ★ AI 生成的问卷不带逻辑跳转（选项不设 nextQuestionId；该列现在存**真 question.id**，
 *     语义见 entity/Option 的类注释）。
 */
@Tag(name = "AI 生成问卷", description = "调用 Qwen 大模型生成问卷")
@RestController
@RequestMapping("/api/ai")
public class AiGenerateController {

    private static final Logger LOG = LoggerFactory.getLogger(AiGenerateController.class);

    @Autowired
    private AiGenerateService aiGenerateService;

    /**
     * H5 额度钱包：AI 生成是进阶功能（3 点）。
     * ★ 预扣 + 失败退回：扣费在调模型**之前**，模型/落库失败时原路退回。
     *   这样"余额不足"不会白白消耗一次 AI 调用，也不会出现"生成成功却扣不到钱"。
     */
    @Autowired
    private WalletService walletService;

    /**
     * 注入请求对象只为取 uri / method / 真实连接 IP 传给 Service。
     * ★ Service 有意不依赖 Web 上下文（生产环境里 Spring 注入的是请求代理，多线程下要注意只能在本线程用）。
     */
    @Autowired
    private HttpServletRequest httpRequest;

    @Operation(summary = "根据主题生成问卷")
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> body,
                                        @RequestHeader("X-User-Id") Integer userId) {
        String topic = body.get("topic");
        String extra = body.getOrDefault("extra", "");

        if (topic == null || topic.trim().isEmpty()) {
            throw new BizException("请输入问卷主题");
        }

        // ★ H5：先预扣 3 点（余额不足在这里就 400，不会去调模型）。
        String chargeKey = walletService.chargeForAi(userId);
        int surveyId;
        try {
            surveyId = aiGenerateService.generateAndSave(
                    userId, topic, extra,
                    httpRequest.getRequestURI(), httpRequest.getMethod(), httpRequest.getRemoteAddr());
        } catch (RuntimeException e) {
            // ★ 生成失败（上游超时/429/落库异常）→ 原路退回，用户不白付。
            //   退款本身失败只记日志，不掩盖原始异常。
            try {
                walletService.refundAi(userId, chargeKey);
            } catch (Exception refundError) {
                LOG.error("AI 生成失败后退款也失败：userId={} chargeKey={} —— {}",
                        userId, chargeKey, refundError.getMessage(), refundError);
            }
            throw e;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "AI 问卷生成成功");
        result.put("surveyId", surveyId);
        return result;
    }
}
