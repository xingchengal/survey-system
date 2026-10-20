package com.survey.common;

import com.survey.entity.Option;
import com.survey.entity.Question;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 填写页的「随机抽题 / 题目乱序 / 选项乱序」唯一口径（纯函数，可单测）。
 *
 * ★ 随机必须可复现：同一 attemptToken + 同一份问卷，刷新页面要抽到同一套题。
 *   做法是用 token 派生种子喂 {@link Random}，不用真随机 —— 否则用户答到一半刷新，
 *   题就换了，已答内容对不上题目。提交时后端再用同一 token 重算一次，用于判分总分。
 * ★ 抽题后按 sort_order 还原相对顺序：抽到的题不该因为随机顺序而打乱正常阅读。
 * ★ 选项乱序按「题目 id」再派生一次种子，避免所有题的选项都按同一个顺序翻转。
 * ★ 只影响填写页：编辑器、统计、回看都不走这里，仍按 sort_order 全量。
 */
public final class AttemptShuffle {

    private AttemptShuffle() {
    }

    /**
     * 抽题 + 可选题目乱序。
     *
     * @param surveyId         用于隔离不同问卷的种子（同 token 也不该跨卷复用）
     * @param all              全量题目（按 sort_order）
     * @param perAttempt       0 或 >= 题数 = 不抽；N = 抽 N 道
     * @param shuffleQuestions 抽完后是否再乱序
     * @param token            前端持久化的抽题令牌（UUID）
     */
    public static List<Question> arrange(Integer surveyId, List<Question> all, int perAttempt,
                                         boolean shuffleQuestions, String token) {
        List<Question> picked = pick(surveyId, all, perAttempt, token);
        if (!shuffleQuestions || picked.size() <= 1) {
            return picked;
        }
        List<Question> shuffled = new ArrayList<>(picked);
        Collections.shuffle(shuffled, random("questions:" + surveyId + ":" + token));
        return shuffled;
    }

    /**
     * 只抽题（保持原相对顺序）：perAttempt 为 0、负数或 >= 题数时返回全量副本。
     */
    public static List<Question> pick(Integer surveyId, List<Question> all, int perAttempt, String token) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int total = all.size();
        if (perAttempt <= 0 || perAttempt >= total) {
            return new ArrayList<>(all);
        }
        List<Question> candidates = new ArrayList<>(all);
        Collections.shuffle(candidates, random("draw:" + surveyId + ":" + token));
        List<Question> picked = new ArrayList<>(candidates.subList(0, perAttempt));
        // 抽完还原相对顺序：随机只决定“抽哪几道”，不决定“按什么顺序读”。
        picked.sort(Comparator.comparing(Question::getSortOrder, Comparator.nullsLast(Integer::compareTo)));
        return picked;
    }

    /** 逐题打乱选项顺序；只处理带选项的题型（options 为 null 或 <=1 时跳过）。 */
    public static void shuffleOptions(Integer surveyId, List<Question> questions, String token) {
        if (questions == null) {
            return;
        }
        for (Question q : questions) {
            List<Option> options = q.getOptions();
            if (options == null || options.size() <= 1) {
                continue;
            }
            List<Option> shuffled = new ArrayList<>(options);
            Collections.shuffle(shuffled, random("options:" + surveyId + ":" + q.getId() + ":" + token));
            q.setOptions(shuffled);
        }
    }

    /** 抽题数量上限 = 题目总数；0 表示不抽。返回夹紧后的值（越界由调用方决定是否 400）。 */
    public static boolean isPerAttemptValid(int perAttempt, int total) {
        return perAttempt == 0 || (perAttempt >= 1 && perAttempt <= total);
    }

    /** 种子：String.hashCode 跨 JVM 稳定，足够让“同一 token 抽同一套题”。 */
    private static Random random(String seed) {
        return new Random(seed.hashCode());
    }
}
