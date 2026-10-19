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
 * AI 智能出题：按一句话主题调用 Qwen 生成整份问卷；用户身份只取 X-User-Id，问卷归其名下。
 * ★ 本类只做「校验参数 → 调 Service → 组装响应」；外部 HTTP / Prompt / 解析 / 落库都在 Service。
 * ★ 生成失败由 Service 转 BizException（400）；外部 HTTP 不进事务（120 秒超时不能占着数据库连接）。
 */
@Tag(name = "AI 生成问卷", description = "调用 Qwen 大模型生成问卷")
@RestController
@RequestMapping("/api/ai")
public class AiGenerateController {

    private static final Logger LOG = LoggerFactory.getLogger(AiGenerateController.class);

    @Autowired
    private AiGenerateService aiGenerateService;

    /**
     * H5 额度钱包：AI 生成扣 3 点，先预扣、失败原路退回（余额不足不会白调一次模型）。
     */
    @Autowired
    private WalletService walletService;

    /** 注入请求对象只为取 uri / method / IP 传给 Service（Service 不依赖 Web 上下文）。 */
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

        // 先预扣 3 点：余额不足在这里就 400，不会去调模型。
        String chargeKey = walletService.chargeForAi(userId);
        int surveyId;
        try {
            surveyId = aiGenerateService.generateAndSave(
                    userId, topic, extra,
                    httpRequest.getRequestURI(), httpRequest.getMethod(), httpRequest.getRemoteAddr());
        } catch (RuntimeException e) {
            // 生成失败原路退回，用户不白付；退款失败只记日志，不掩盖原始异常。
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
