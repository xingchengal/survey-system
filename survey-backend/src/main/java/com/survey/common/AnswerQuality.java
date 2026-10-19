package com.survey.common;

/**
 * 答卷质量（无效样本）判定的**唯一口径**：规则码、阈值、中文说明。
 * 提交时算一次并随 t_answer 落库，不回算历史答卷。
 * ★ 耗时由前端上报、可伪造 —— TOO_FAST 只作提示，不作安全边界。
 */
public final class AnswerQuality {

    private AnswerQuality() {
    }

    /** 每题"合理最短耗时"（毫秒）。低于 `题数 × 该值` → TOO_FAST。 */
    public static final long MIN_MS_PER_QUESTION = 1500L;

    /** 直线作答判定的**最少选择题数**（少于 3 道不判：2 道选同一项太常见）。 */
    public static final int STRAIGHT_LINE_MIN_QUESTIONS = 3;

    /** 答题过快 */
    public static final String TOO_FAST = "TOO_FAST";
    /** 直线作答（所有选择题选了同一位置） */
    public static final String STRAIGHT_LINE = "STRAIGHT_LINE";
    /** 答案不在该题的选项里（脚本灌入的特征） */
    public static final String OPTION_MISMATCH = "OPTION_MISMATCH";
    /** 同题干的两道题答案不一致（"前后矛盾"里可自动判定的那部分） */
    public static final String DUP_TITLE_CONFLICT = "DUP_TITLE_CONFLICT";

    /** `t_answer.quality_reasons` 的列宽（varchar(200)）—— 拼接后必须校验，否则写库 500。 */
    public static final int MAX_REASONS_LENGTH = 200;

    /** 规则码 → 面向用户的中文说明。未知码原样返回（向前兼容）。 */
    public static String describe(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case TOO_FAST -> "答题过快";
            case STRAIGHT_LINE -> "直线作答（选项位置全相同）";
            case OPTION_MISMATCH -> "答案不在选项范围内";
            case DUP_TITLE_CONFLICT -> "同题干的两题答案不一致";
            default -> code;
        };
    }
}
