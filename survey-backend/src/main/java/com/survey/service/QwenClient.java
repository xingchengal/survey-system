package com.survey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Qwen（硅基流动 SiliconFlow）模型调用客户端 —— 只负责"把提示词发出去、把 JSON 拿回来"。
 *
 * ── 为什么单独抽成一个客户端 ──────────────────────────
 *   项目其余链路都遵循"Controller 薄、逻辑下沉 Service"；若把 OkHttp 客户端、超时配置、
 *   429 重试、markdown 代码块剥离、JSON 提取全塞在 Controller 里：
 *   既难以单测，也让 Controller 认识了一堆 okhttp 类型。
 *
 *   现在：**本类是唯一的"外部模型"边界**。以后要换服务商（或加一个"本地模型 / 假实现"给测试用），
 *   只需要再写一个同签名的组件，业务侧（AiGenerateService）完全不用动。
 *
 * ── 它不做什么（有意为之）──────────────────────────────
 *   不写数据库、不写异常日志、不认识 HttpServletRequest、不抛 BizException ——
 *   那些都是"业务/Web 层"的事，由 AiGenerateService 负责编排。
 *   本类只抛两类异常：
 *     · {@link RateLimitedException} —— 上游 429，属**可预期**的免费额度排队；
 *     · 其它运行时异常            —— 网络/格式故障，属系统级错误。
 *
 * ── 超时取值（沿用原实现，别随手调小）────────────────────
 *   connect 30s / read **120s** / write 30s：免费额度排队时响应会很慢，
 *   read 给小了会把正常排队误判成超时失败。
 */
@Component
public class QwenClient {

    private static final Logger LOG = LoggerFactory.getLogger(QwenClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 上游返回 429（限流）时抛它：调用方据此给用户"稍后重试"这类可读提示 */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    @Value("${ai.qwen.base-url}")
    private String baseUrl;
    @Value("${ai.qwen.api-key}")
    private String apiKey;
    @Value("${ai.qwen.model}")
    private String model;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * 一次完整的"主题 → 解析好的问卷结构"，**含一次失败重试**。
     *
     * ★ 重试为什么在这里：免费额度经常遇到 429 排队，网络也会抖动 ——
     *   这是"与外部服务打交道"固有的应对方式，属于客户端自己的职责。
     * ★ 退避 sleep 有意保留（当前可接受）：
     *   限流退避 3 秒、其它错误 0.8 秒，最多阻塞请求线程 3 秒；
     *   而真正耗时的是 read timeout 120 秒，相比之下这点等待不是瓶颈。
     *   若日后压测发现线程占用问题，应改为异步重试，而不是把退避删掉。
     *
     * @param topic 用户输入的主题
     * @param extra 额外要求（可为空字符串）
     * @return 形如 {title, description, questions:[{title,type,options}]} 的 Map
     * @throws RateLimitedException 上游两次都是 429
     */
    public Map<String, Object> generateSurveyJson(String topic, String extra) {
        String prompt = buildPrompt(topic, extra);

        String aiResponse = null;
        Exception lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                aiResponse = requestOnce(prompt);
                lastError = null;
                break;
            } catch (Exception ex) {
                lastError = ex;
                LOG.warn("AI 调用第 {} 次失败：{}", attempt, ex.getMessage());
                if (attempt < 2) {
                    try {
                        // 限流退避久一点（3s），其它错误短暂等待（0.8s）
                        Thread.sleep(ex instanceof RateLimitedException ? 3000L : 800L);
                    } catch (InterruptedException ie) {
                        // ★ 恢复中断标记：吞掉 InterruptedException 会让上层无法感知"该停了"
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("AI 调用重试被中断", ie);
                    }
                }
            }
        }
        if (lastError != null) {
            if (lastError instanceof RateLimitedException rle) {
                throw rle;
            }
            throw new IllegalStateException("AI 调用失败：" + lastError.getMessage(), lastError);
        }

        return parseAiResponse(aiResponse);
    }

    /**
     * 发一次请求并取出 content 字段。
     * ★ 用 try-with-resources 关闭 Response。
     */
    private String requestOnce(String prompt) {
        try {
            Map<String, Object> bodyMap = new HashMap<>();
            bodyMap.put("model", model);
            bodyMap.put("temperature", 0.7);

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);
            bodyMap.put("messages", messages);

            String json = MAPPER.writeValueAsString(bodyMap);

            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 429) {
                        throw new RateLimitedException("AI 接口限流（429）：免费额度请求过于频繁");
                    }
                    throw new IllegalStateException(
                            "AI 接口返回错误：" + response.code() + " " + response.message());
                }

                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    throw new IllegalStateException("AI 接口返回内容为空");
                }
                String respBody = responseBody.string();
                if (respBody.isEmpty()) {
                    throw new IllegalStateException("AI 接口返回内容为空字符串");
                }

                JsonNode root = MAPPER.readTree(respBody);
                JsonNode choices = root.path("choices");
                if (choices.isMissingNode() || !choices.isArray() || choices.size() == 0) {
                    throw new IllegalStateException("AI 接口返回格式异常，缺少 choices");
                }
                return choices.get(0).path("message").path("content").asText();
            }
        } catch (RateLimitedException | IllegalStateException e) {
            throw e;   // 已经是明确语义的异常，原样上抛
        } catch (Exception e) {
            // IOException（网络/超时）与 Jackson 解析异常都归到这里
            throw new IllegalStateException("AI 接口调用异常：" + e.getMessage(), e);
        }
    }

    /**
     * 把 AI 返回的文本解析成问卷结构。
     *
     * ★ 容错：模型经常把 JSON 包在 markdown 代码块里，或者前后多说几句废话 ——
     *   所以先剥 ``` 包裹，再取**第一个 { 到最后一个 }** 之间的内容。
     *
     * ★ 异常里**保留 AI 返回的原文**：这不是给用户看的（AiGenerateService 对外只回固定文案），
     *   而是写进 t_error_log 供排查"模型这次到底吐了什么"。所以这里不做截断，
     *   截断由写日志那一侧负责（900 字符）。
     */
    private Map<String, Object> parseAiResponse(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalStateException("AI 返回内容为空");
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(json)?\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start == -1 || end == -1 || end < start) {
            throw new IllegalStateException("AI 返回内容不是有效 JSON：" + text);
        }
        text = text.substring(start, end + 1);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.readValue(text, Map.class);
            return map;
        } catch (Exception e) {
            throw new IllegalStateException("AI 返回的 JSON 无法解析：" + text, e);
        }
    }

    /** 构造提示词（内容与原实现逐字一致，只有位置从 Controller 搬到了这里）。 */
    private String buildPrompt(String topic, String extra) {
        return "你是一个问卷设计专家。请根据下面的主题设计一份问卷，返回**纯 JSON**，不要 markdown 代码块，不要任何额外说明。\n\n" +
                "主题：" + topic + "\n" +
                (extra.isEmpty() ? "" : "额外要求：" + extra + "\n") +
                "\n" +
                "JSON 格式必须严格按照下面结构：\n" +
                "{\n" +
                "  \"title\": \"问卷标题\",\n" +
                "  \"description\": \"问卷描述（20字以内）\",\n" +
                "  \"questions\": [\n" +
                "    {\n" +
                "      \"title\": \"题目内容\",\n" +
                "      \"type\": 1,\n" +
                "      \"options\": [\"选项1\", \"选项2\", \"选项3\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n\n" +
                "题型说明：\n" +
                "- type=1：单选题，必须有 3-5 个 options\n" +
                "- type=2：多选题，必须有 3-5 个 options\n" +
                "- type=3：填空题，无 options\n\n" +
                "请生成 5-8 道题目，题型混合，语言简洁专业。";
    }
}
