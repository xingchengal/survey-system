package com.survey.service;

import com.survey.common.AnswerQuality;
import com.survey.common.QuestionType;
import com.survey.entity.Answer;
import com.survey.entity.AnswerDetail;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.mapper.AnswerMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 答卷质量判定与质控摘要。
 *
 * ── 职责边界（很重要）─────────────────────────────────
 *   本类**只做判定**，不写库：写入 `t_answer.quality_flag/quality_reasons/duration_ms` 的
 *   动作在 `AnswerService.submit` 的同一事务里完成（判定要基于"刚插入的明细"，
 *   所以必须紧跟着提交逻辑，不能拆成独立事务）。
 *
 * ── ★ 判定只在提交那一刻做一次 ──────────────────────────
 *   规则以后调整、题目后来被编辑，都**不会**让历史答卷重新判定。
 *   否则同一份答卷今天有效、明天无效，用户无法解释图表的差异。
 *
 * ── 三件它**不做**的事（避免被误解为"AI 质检"）──────────
 *   1. 不做语义矛盾检测（"没孩子却填了孩子年龄"需要题目间约束规则，属产品能力）；
 *   2. 不相信前端上报的耗时（可伪造，只作提示）；
 *   3. 不自动删除可疑答卷 —— 只打标，是否剔除由查询方（统计接口 / 前端开关）决定。
 */
@Service
public class AnswerQualityService {

    @Autowired
    private AnswerMapper answerMapper;

    /**
     * 判定一份答卷是否可疑。
     *
     * @param details           本次提交的明细（已插入，但内存里就是刚提交的这批）
     * @param questions         该问卷的题目
     * @param optionsByQuestion 题目 → 选项（选择题才有；必须按 sort_order 排好，位次判定依赖它）
     * @param durationMs        前端上报的耗时（可空：老客户端 / 直接调接口）
     * @return 判定结果（是否可疑 + 命中原因码，顺序稳定便于断言与展示）
     */
    public QualityVerdict evaluate(List<AnswerDetail> details, List<Question> questions,
                                   Map<Integer, List<Option>> optionsByQuestion, Integer durationMs) {
        List<String> reasons = new ArrayList<>();
        Map<Integer, List<String>> answersByQuestion = groupAnswers(details);

        // ── 规则 1：答题过快（无耗时数据时不判 —— 不能把"没上报"当成"很快"）──
        if (durationMs != null && durationMs >= 0 && questions.size() >= 2
                && durationMs < questions.size() * AnswerQuality.MIN_MS_PER_QUESTION) {
            reasons.add(AnswerQuality.TOO_FAST);
        }

        // ── 规则 2：答案不在选项里（脚本灌入的特征）──
        if (hasOptionMismatch(answersByQuestion, questions, optionsByQuestion)) {
            reasons.add(AnswerQuality.OPTION_MISMATCH);
        }

        // ── 规则 3：直线作答（≥3 道选择题，位次全相同）──
        if (isStraightLine(answersByQuestion, questions, optionsByQuestion)) {
            reasons.add(AnswerQuality.STRAIGHT_LINE);
        }

        // ── 规则 4：同题干的两道题答案不一致 ──
        if (hasDuplicateTitleConflict(answersByQuestion, questions)) {
            reasons.add(AnswerQuality.DUP_TITLE_CONFLICT);
        }

        return new QualityVerdict(!reasons.isEmpty(), reasons);
    }

    /**
     * 质控摘要（统计页顶部展示）。
     *
     * @param surveyId 问卷
     * @param total    答卷总数
     * @return { total, suspicious, suspiciousRate, reasons:[{code,label,count}], samples:[...] }
     */
    public Map<String, Object> summarize(Integer surveyId, int total) {
        int suspicious = answerMapper.countSuspiciousBySurveyId(surveyId);
        List<Map<String, Object>> reasonRows = answerMapper.countByReasons(surveyId);
        List<Answer> samples = answerMapper.findSuspiciousBySurveyId(surveyId);

        // 把"原因组合"拆成单个码累加（一条答卷可能同时命中多条规则）
        Map<String, Integer> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : reasonRows) {
            String reasons = row.get("reasons") == null ? null : row.get("reasons").toString();
            int cnt = row.get("cnt") == null ? 0 : ((Number) row.get("cnt")).intValue();
            if (reasons == null || reasons.isBlank()) {
                continue;
            }
            for (String code : reasons.split(",")) {
                String trimmed = code.trim();
                if (!trimmed.isEmpty()) {
                    byCode.merge(trimmed, cnt, Integer::sum);
                }
            }
        }
        List<Map<String, Object>> reasonStats = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byCode.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", e.getKey());
            item.put("label", AnswerQuality.describe(e.getKey()));
            item.put("count", e.getValue());
            reasonStats.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("suspicious", suspicious);
        // 保留一位小数，前端直接显示百分比
        result.put("suspiciousRate", total == 0 ? 0.0 : Math.round(suspicious * 1000.0 / total) / 10.0);
        result.put("reasons", reasonStats);
        result.put("samples", samples);
        return result;
    }

    // ==================== 内部判定 ====================

    /** 明细按题分组（一题可能多行 —— 多选题"一选项一行"）。 */
    private static Map<Integer, List<String>> groupAnswers(List<AnswerDetail> details) {
        Map<Integer, List<String>> map = new LinkedHashMap<>();
        if (details == null) {
            return map;
        }
        for (AnswerDetail d : details) {
            if (d.getQuestionId() != null && d.getContent() != null) {
                map.computeIfAbsent(d.getQuestionId(), k -> new ArrayList<>()).add(d.getContent());
            }
        }
        return map;
    }

    /** 规则 2：任一选择题的答案不在它的选项文本里。 */
    private static boolean hasOptionMismatch(Map<Integer, List<String>> answersByQuestion,
                                             List<Question> questions,
                                             Map<Integer, List<Option>> optionsByQuestion) {
        for (Question q : questions) {
            if (!QuestionType.hasOptions(q.getType())) {
                continue;
            }
            List<String> answers = answersByQuestion.get(q.getId());
            if (answers == null || answers.isEmpty()) {
                continue;   // 未作答不判（必填校验在提交守卫里）
            }
            List<Option> options = optionsByQuestion.getOrDefault(q.getId(), List.of());
            if (options.isEmpty()) {
                continue;   // 题目没配选项（脏数据），无法判定
            }
            List<String> texts = options.stream().map(Option::getContent).collect(Collectors.toList());
            for (String a : answers) {
                if (!texts.contains(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 规则 3：≥3 道选择题，且每个"选中项在选项里的位次"完全相同。
     *
     * ★ 多选怎么算：多选可能一次选 2 项（位次 0 和 1）—— 只要出现不同位次，
     *   就不算直线作答（说明用户确实做了区分）。
     * ★ 答案不在选项里的题直接跳过：那种情况由规则 2 报告，这里不重复刷同一个原因。
     */
    private static boolean isStraightLine(Map<Integer, List<String>> answersByQuestion,
                                          List<Question> questions,
                                          Map<Integer, List<Option>> optionsByQuestion) {
        List<Integer> positions = new ArrayList<>();
        int optionQuestionsConsidered = 0;

        for (Question q : questions) {
            if (!QuestionType.hasOptions(q.getType())) {
                continue;
            }
            List<String> answers = answersByQuestion.get(q.getId());
            if (answers == null || answers.isEmpty()) {
                continue;
            }
            List<Option> options = optionsByQuestion.getOrDefault(q.getId(), List.of());
            if (options.isEmpty()) {
                continue;
            }
            List<String> texts = options.stream().map(Option::getContent).collect(Collectors.toList());
            boolean allResolvable = answers.stream().allMatch(texts::contains);
            if (!allResolvable) {
                continue;   // 有答案不在选项里 → 交给规则 2
            }
            optionQuestionsConsidered++;
            for (String a : answers) {
                positions.add(texts.indexOf(a));
            }
        }

        if (optionQuestionsConsidered < AnswerQuality.STRAIGHT_LINE_MIN_QUESTIONS || positions.isEmpty()) {
            return false;
        }
        int first = positions.get(0);
        return positions.stream().allMatch(p -> Objects.equals(p, first));
    }

    /**
     * 规则 4：题干相同的两道题，答案不一致。
     *
     * ★ 比较方式：把每题的全部答案排序后拼成字符串再比 ——
     *   多选是"一选项一行"，直接比 List 顺序不稳（库里没有保证答案行的顺序）。
     * ★ 只在**两边都作答**时比较：一边没答不算矛盾（可能是跳转跳过了）。
     */
    private static boolean hasDuplicateTitleConflict(Map<Integer, List<String>> answersByQuestion,
                                                     List<Question> questions) {
        Map<String, List<Question>> byTitle = new HashMap<>();
        for (Question q : questions) {
            String title = q.getTitle() == null ? null : q.getTitle().trim();
            if (title == null || title.isEmpty()) {
                continue;
            }
            byTitle.computeIfAbsent(title, k -> new ArrayList<>()).add(q);
        }
        for (List<Question> group : byTitle.values()) {
            if (group.size() < 2) {
                continue;
            }
            String firstSignature = null;
            for (Question q : group) {
                List<String> answers = answersByQuestion.get(q.getId());
                if (answers == null || answers.isEmpty()) {
                    continue;
                }
                String signature = answers.stream().sorted().collect(Collectors.joining("|"));
                if (firstSignature == null) {
                    firstSignature = signature;
                } else if (!firstSignature.equals(signature)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判定结果。
     *
     * @param suspicious 是否命中至少一条规则
     * @param reasons    命中的规则码（顺序固定，便于断言语义稳定）
     */
    public record QualityVerdict(boolean suspicious, List<String> reasons) {

        /** 拼成 `t_answer.quality_reasons` 的存储形式（逗号分隔；列宽 200 已足够 4 个码）。 */
        public String joined() {
            return String.join(",", reasons);
        }
    }
}
