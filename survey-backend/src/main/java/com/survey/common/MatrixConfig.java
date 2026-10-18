package com.survey.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 矩阵题的行 / 列标题 —— **全项目唯一口径**（保存校验、提交校验、统计解析都走这里）。
 *
 * ── 存哪、为什么 ───────────────────────────────────────
 *   存在 `t_question.matrix_rows` / `matrix_cols` 两列里，格式是 **JSON 字符串数组**：
 *     ["服务态度","响应速度"]   ×   ["很满意","满意","一般","不满意"]
 *   ★ 为什么不放进 t_option：t_option 是"一维候选答案"语义（单选/多选/下拉/排序的候选项），
 *     矩阵是"行 × 列"两个独立维度。塞进同一张表只能再加一列区分"行/列"，
 *     之后**每一处**读选项的代码都必须先过滤，漏一处就会把列当成一行答案。
 *     独立两列之后，矩阵完全不碰 t_option，现有选择题链路零改动。
 *
 * ── 为什么标题原文要和答案一起存 ─────────────────────────
 *   答卷明细 `t_answer_detail.row_key` 存的是**行标题原文**（不是行下标）。
 *   作者以后改标题只影响之后提交的答卷（与选项文本的既有口径一致）；
 *   若存下标，改一次行顺序，历史答卷就会"按新行解释"，统计静默错位。
 *
 * ── 上限为什么是这些数 ─────────────────────────────────
 *   行 20 × 列 10 × 每格标题 50 字，是问卷星类产品的常见规模；
 *   两列都是 varchar(2000)，JSON 转义后最坏情况（20 行 × 50 个汉字）约 3KB 字符，
 *   所以还要用 {@link TextLimits#MATRIX_AXIS} 兜住原始 JSON 长度，避免超列宽 → 500。
 */
public final class MatrixConfig {

    /** 矩阵题最多多少行（访客要逐行作答，再多填答体验就崩了） */
    public static final int MAX_ROWS = 20;

    /** 矩阵题最多多少列（窄屏横向滚动，10 列是可用性的上限） */
    public static final int MAX_COLS = 10;

    /** 单个行/列标题的最大长度 */
    public static final int MAX_LABEL_LENGTH = 50;

    /**
     * ★ Boot 4 不再注册 Jackson 2 的 ObjectMapper bean（见《交接文档》环境坑），
     *   所以这里自己持有实例，不注入。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MatrixConfig() {
    }

    /**
     * 解析 JSON 数组文本。
     *
     * @param json      列里的原文（null / 空白 → 空列表）
     * @param fieldName 出错时的字段名（"矩阵行标题" / "矩阵列标题"）
     * @throws BizException JSON 损坏（→ 400，而不是让 Jackson 的异常变成 500）
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
     * 严格校验：非空、条数上限、标题非空、不重复、长度上限。
     * 返回去首尾空白后的标题列表（保存时必须用返回值写库）。
     *
     * @param json      数据库列 / 请求体里的 JSON 文本
     * @param fieldName "矩阵行标题" / "矩阵列标题"
     * @param maxCount  {@link #MAX_ROWS} 或 {@link #MAX_COLS}
     * @param minCount  最少条数（行至少 1、列至少 2 —— 一列无法构成矩阵）
     */
    public static List<String> validate(String json, String fieldName, int maxCount, int minCount) {
        // ★ 先卡原始 JSON 长度：超列宽会在 INSERT 时被 MySQL 拒绝 → 500
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
                // ★ 必须拒绝重复：答卷的 row_key / content 都是标题原文，
                //   两行同名时统计会把它们合并成一行（用户看不出来，但数据已经错了）
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
