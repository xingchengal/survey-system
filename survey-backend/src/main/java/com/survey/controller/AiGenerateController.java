package com.survey.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Tag(name = "AI 生成问卷", description = "调用 Qwen 大模型生成问卷")
@RestController
@RequestMapping("/api/ai")
public class AiGenerateController {

    @Autowired
    private SurveyMapper surveyMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private OptionMapper optionMapper;

    @Value("${ai.qwen.base-url}")
    private String baseUrl;
    @Value("${ai.qwen.api-key}")
    private String apiKey;
    @Value("${ai.qwen.model}")
    private String model;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Operation(summary = "根据主题生成问卷")
    @PostMapping("/generate")
    @Transactional
    public Map<String, Object> generate(@RequestBody Map<String, String> body,
                                        @RequestHeader("X-User-Id") Integer userId) {
        Map<String, Object> result = new HashMap<>();
        String topic = body.get("topic");
        String extra = body.getOrDefault("extra", "");

        if (topic == null || topic.trim().isEmpty()) {
            result.put("success", false);
            result.put("message", "请输入问卷主题");
            return result;
        }

        try {
            String prompt = buildPrompt(topic, extra);
            String aiResponse = callQwen(prompt);
            Map<String, Object> parsed = parseAiResponse(aiResponse);

            String title = (String) parsed.get("title");
            String description = (String) parsed.get("description");

            // ★ 修复问题 2：校验 questions
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions = (List<Map<String, Object>>) parsed.get("questions");
            if (questions == null || questions.isEmpty()) {
                result.put("success", false);
                result.put("message", "AI 未返回有效题目，请重试");
                return result;
            }

            if (title == null || title.trim().isEmpty()) {
                title = topic;
            }
            if (description == null) {
                description = "";
            }

            Survey s = new Survey();
            s.setUserId(userId);
            s.setTitle(title);
            s.setDescription(description);
            s.setStatus(0);
            s.setIsTemplate(0);
            s.setDeleted(0);
            s.setViewCount(0);
            surveyMapper.insert(s);

            int qOrder = 0;
            for (Map<String, Object> q : questions) {
                Object titleObj = q.get("title");
                Object typeObj = q.get("type");
                if (titleObj == null || typeObj == null) continue;

                Question question = new Question();
                question.setSurveyId(s.getId());
                question.setTitle(titleObj.toString());
                question.setType(((Number) typeObj).intValue());
                question.setRequired(0);
                question.setSortOrder(qOrder++);
                questionMapper.insert(question);

                Object opts = q.get("options");
                if (opts instanceof List) {
                    int oOrder = 0;
                    for (Object o : (List<?>) opts) {
                        if (o == null) continue;
                        Option opt = new Option();
                        opt.setQuestionId(question.getId());
                        opt.setContent(o.toString());
                        opt.setSortOrder(oOrder++);
                        optionMapper.insert(opt);
                    }
                }
            }

            result.put("success", true);
            result.put("message", "AI 问卷生成成功");
            result.put("surveyId", s.getId());
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            result.put("success", false);
            result.put("message", "生成失败：" + e.getMessage());
            return result;
        }
    }

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

    private String callQwen(String prompt) throws Exception {
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
                .post(okhttp3.RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("AI 接口返回错误：" + response.code() + " " + response.message());
            }

            // ★ 修复问题 3：response.body() 可能为 null
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new RuntimeException("AI 接口返回内容为空");
            }
            String respBody = responseBody.string();
            if (respBody == null || respBody.isEmpty()) {
                throw new RuntimeException("AI 接口返回内容为空字符串");
            }

            JsonNode root = MAPPER.readTree(respBody);
            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.size() == 0) {
                throw new RuntimeException("AI 接口返回格式异常，缺少 choices");
            }
            return choices.get(0).path("message").path("content").asText();
        }
    }

    private Map<String, Object> parseAiResponse(String content) throws Exception {
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("AI 返回内容为空");
        }
        content = content.trim();
        if (content.startsWith("```")) {
            content = content.replaceFirst("^```(json)?\\s*", "");
            content = content.replaceFirst("\\s*```$", "");
        }

        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start == -1 || end == -1) {
            throw new RuntimeException("AI 返回内容不是有效 JSON：" + content);
        }
        content = content.substring(start, end + 1);

        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(content, Map.class);
        return map;
    }
}