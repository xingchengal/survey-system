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
 * 答卷质量判定：提交时打标，统计页出质控摘要。
 * ★ 四条规则：答题过快 / 直线作答 / 答案不在选项里 / 同题干两题矛盾；只打标，绝不删除可疑答卷。
 * 判定只在提交那一刻做一次，历史答卷不重判（否则同一份答卷今天有效、明天无效）。
 * 阈值写死在 AnswerQuality（如 MIN_MS_PER_QUESTION），取可解释的整数，不做动态调参。
 * ★ 耗时来自前端上报、可伪造，只作提示；本类不写库，落库在 AnswerService.submit 同事务。
 */
@Service
public class AnswerQualityService {

    @Autowired
    private AnswerMapper answerMapper;

    /**
     * 判定一份答卷是否可疑。
     *
     * @param optionsByQuestion 题目 → 选项（选择题才有；必须按 sort_order 排好，位次判定依赖它）
     * @param durationMs        前端上报的耗时（可空：老客户端 / 直接调接口）
     * @return 判定结果（是否可疑 + 命中原因码，顺序稳定便于断言与展示）
     */
    public QualityVerdict evaluate(List<AnswerDetail> details, List<Question> questions,
                                   Map<Integer, List<Option>> optionsByQuestion, Integer durationMs) {
        List<String> reasons = new ArrayList<>();
        Map<Integer, List<String>> answersByQuestion = groupAnswers(details);

        // 规则 1：答题过快（无耗时数据时不判 —— 不能把"没上报"当成"很快"）
        if (durationMs != null && durationMs >= 0 && questions.size() >= 2
                && durationMs < questions.size() * AnswerQuality.MIN_MS_PER_QUESTION) {
            reasons.add(AnswerQuality.TOO_FAST);
        }

        // 规则 2：答案不在选项里（脚本灌入的特征）
        if (hasOptionMismatch(answersByQuestion, questions, optionsByQuestion)) {
            reasons.add(AnswerQuality.OPTION_MISMATCH);
        }

        // 规则 3：直线作答（≥3 道选择题，位次全相同）
        if (isStraightLine(answersByQuestion, questions, optionsByQuestion)) {
            reasons.add(AnswerQuality.STRAIGHT_LINE);
        }

        // 规则 4：同题干的两道题答案不一致
        if (hasDuplicateTitleConflict(answersByQuestion, questions)) {
            reasons.add(AnswerQuality.DUP_TITLE_CONFLICT);
        }

        return new QualityVerdict(!reasons.isEmpty(), reasons);
    }

    /**
     * 质控摘要（统计页顶部展示）。
     *
     * @param total 答卷总数
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
     * 规则 3：≥3 道选择题，且选中项在选项里的位次完全相同。
     * 多选只要出现不同位次就不算直线；答案不在选项里的题跳过（交给规则 2，不重复刷同一原因）。
     */
    private static boolean isStraightLine(Map<Integer, List<String>> answersByQuestion,
                                          List<Question> questions,
                                          Map<Integer, List<Option>> optionsByQuestion) {
        List<Integer> positions = new ArrayList<>();
        int optionQuestionsConsidered = 0;

        for (Question q : questions) {
            // 排序题不参与直线判定：它的位次集合恒定包含 0..N-1，参与只会稀释规则。
            if (!QuestionType.hasOptions(q.getType()) || QuestionType.isRanking(q.getType())) {
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
     * 每题答案排序后拼串再比（多选一行一选项，库不保证行顺序）；只在两边都作答时比较。
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
