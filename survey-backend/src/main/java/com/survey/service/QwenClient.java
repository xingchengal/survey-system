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
 * Qwen（硅基流动 SiliconFlow）模型客户端：唯一的"外部模型"边界，只负责发提示词、把 JSON 拿回来。
 * ★ 外部 HTTP 不能放进事务：排队最长 120 秒，占着连接会拖垮其它请求；超时 connect/read/write = 30/120/30s，429 / 网络错误重试一次。
 * ★ 自己持有 ObjectMapper：Spring Boot 4 不再注册 Jackson 2 的 bean，注入拿不到。
 * 不写库、不写异常日志、不抛 BizException —— 编排与对外文案归 AiGenerateService。
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
     * 一次完整的「主题 → 解析好的问卷结构」，含一次失败重试（429 / 网络错误）。
     * 退避 3s / 0.8s 相对 120s 的 read 超时不是瓶颈；失败时保留最后一次异常原样上抛。
     *
     * @return 形如 {title, description, questions:[{title,type,options}]} 的 Map
     * @throws RateLimitedException 两次调用都是 429
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
                        // 恢复中断标记：吞掉 InterruptedException 会让上层无法感知"该停了"
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

    /** 发一次请求并取出 content 字段（try-with-resources 关闭 Response）。 */
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
     * 解析 AI 返回：先剥 ``` 包裹，再取第一个 { 到最后一个 }。
     * 异常里保留 AI 原文供 t_error_log 排查；对外文案由 AiGenerateService 固定，不把原文透给用户。
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

    /** 构造提示词：要求纯 JSON 输出、3 种题型、5~8 道题。 */
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
