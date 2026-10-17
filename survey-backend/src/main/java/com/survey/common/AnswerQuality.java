package com.survey.common;

/**
 * 答卷质量（无效样本）判定的**唯一口径**：规则码、阈值、中文说明。
 *
 * ── 为什么要有"质控"这一层 ──────────────────────────
 *   答卷数、选项分布这些数字，是**假设每份答卷都认真填了**才能用的。
 *   实际收上来的数据里混着：脚本灌的、乱点一通、为了抽奖全选第一项……
 *   如果统计时不把它们标出来，图表的结论就是"在一堆垃圾上算出来的精确数"。
 *
 * ── 四条规则（都在提交时算一次，不回算历史）────────────
 *   | 规则码 | 判定 | 为什么这么定 |
 *   |---|---|---|
 *   | `TOO_FAST` | 耗时 < 题数 × 1500ms | 每题连读题带作答不足 1.5 秒，基本不可能是认真填的。★ 耗时由**前端上报**，可伪造 —— 只作提示，不作安全边界 |
 *   | `STRAIGHT_LINE` | ≥3 道选择题，且答案在各自选项里的位次全相同 | 典型的"一路点第一个"。少于 3 道时不判（2 道选同一项太常见） |
 *   | `OPTION_MISMATCH` | 选择题的答案不在该题的选项里 | 正常页面选不出这种答案；出现即说明是脚本直接调接口灌的 |
 *   | `DUP_TITLE_CONFLICT` | 同一问卷里**题干相同**的两道题答案不一致 | 这是"前后矛盾"里**唯一能自动判定**的一种。真正的语义矛盾（"没孩子"却填了"孩子年龄"）需要题目间的约束规则，那属于产品能力，不是算法能猜的 |
 *
 * ── ★ 阈值为什么写死成常量 ──────────────────────────
 *   它们要能被解释、被复现。"1.5 秒/题"是从"正常阅读 + 点击"的经验值来的，
 *   放在配置里反而会让不同环境算出不同的质量结论，对比与复盘都变难。
 *   要调整就改这里，并且**只影响新提交的答卷**（老答卷不回算，理由见 `AnswerMapper.updateQuality`）。
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
