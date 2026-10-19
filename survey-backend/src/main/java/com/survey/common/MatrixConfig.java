package com.survey.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 矩阵题的行 / 列标题 —— 全项目唯一口径（保存校验、提交校验、统计解析都走这里）。
 * 存在 t_question.matrix_rows / matrix_cols，格式是 JSON 字符串数组；不放进 t_option：
 * 矩阵是"行 × 列"两个维度，塞进一维选项表后每处读选项的代码都得额外过滤，漏一处就错。
 * ★ 答卷 row_key 存行标题原文而非下标：存下标则改一次行顺序，历史答卷会被按新行解释。
 * ★ 行列标题另有 TextLimits.MATRIX_AXIS 兜原始 JSON 长度，超列宽会在 INSERT 时变 500。
 */
public final class MatrixConfig {

    /** 矩阵题最多多少行（访客要逐行作答，再多填答体验就崩了） */
    public static final int MAX_ROWS = 20;

    /** 矩阵题最多多少列（窄屏横向滚动，10 列是可用性的上限） */
    public static final int MAX_COLS = 10;

    /** 单个行/列标题的最大长度 */
    public static final int MAX_LABEL_LENGTH = 50;

    /**
     * Boot 4 不再注册 Jackson 2 的 ObjectMapper bean，这里自己持有实例，不注入。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MatrixConfig() {
    }

    /**
     * 解析 JSON 数组文本（null / 空白 → 空列表）。
     * @param fieldName 出错时的字段名（"矩阵行标题" / "矩阵列标题"）
     * @throws BizException JSON 损坏 → 400，而不是让 Jackson 异常变成 500
     */
    public static List<String> parse(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            String[] arr = MAPPER.readValue(json, String[].class);
            List<String> list = new ArrayList<>();
            if (arr != null) {
                for (String item : arr) {
                    list.add(item == null ? "" : item);
                }
            }
            return list;
        } catch (Exception e) {
            throw new BizException(fieldName + "格式不正确（应为字符串数组）");
        }
    }

    /**
     * 严格校验：非空、条数、标题非空、不重复、长度；返回去首尾空白后的列表（写库必须用返回值）。
     * @param fieldName "矩阵行标题" / "矩阵列标题"；maxCount 取 MAX_ROWS / MAX_COLS
     * @param minCount  最少条数（行至少 1、列至少 2 —— 一列无法构成矩阵）
     */
    public static List<String> validate(String json, String fieldName, int maxCount, int minCount) {
        TextLimits.requireWithin(json, TextLimits.MATRIX_AXIS, fieldName);
        List<String> parsed = parse(json, fieldName);
        if (parsed.size() < minCount) {
            throw new BizException(fieldName + "至少需要 " + minCount + " 项");
        }
        if (parsed.size() > maxCount) {
            throw new BizException(fieldName + "最多 " + maxCount + " 项");
        }
        List<String> cleaned = new ArrayList<>(parsed.size());
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (int i = 0; i < parsed.size(); i++) {
            String label = parsed.get(i) == null ? "" : parsed.get(i).trim();
            if (label.isEmpty()) {
                throw new BizException(fieldName + "第 " + (i + 1) + " 项不能为空");
            }
            if (label.length() > MAX_LABEL_LENGTH) {
                throw new BizException(fieldName + "第 " + (i + 1) + " 项不能超过 "
                        + MAX_LABEL_LENGTH + " 个字符");
            }
            if (seen.put(label, Boolean.TRUE) != null) {
                // 必须拒绝重复：row_key / content 都是标题原文，同名会在统计里被合并，用户看不出来但数据已错
                throw new BizException(fieldName + "不能重复：「" + label + "」出现了两次");
            }
            cleaned.add(label);
        }
        return cleaned;
    }

    /** 把标题列表写回 JSON 文本（保存时用；前端也会发同样格式） */
    public static String toJson(List<String> labels) {
        try {
            return MAPPER.writeValueAsString(labels == null ? List.of() : labels);
        } catch (Exception e) {
            throw new IllegalStateException("矩阵标题序列化失败", e);
        }
    }
}
