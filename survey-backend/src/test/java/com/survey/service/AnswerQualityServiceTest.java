package com.survey.service;

import com.survey.common.AnswerQuality;
import com.survey.entity.AnswerDetail;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.mapper.AnswerMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * 答卷质控判定的单元测试（纯规则，不连库）。
 *
 * ── 这个类钉住的重点是"不误伤" ────────────────────────
 *   质控规则最怕的不是漏判，而是**误伤**：一份认真填的答卷被标成"可疑"，
 *   作者会直接不信任这个功能（而"漏判"最多只是少标几个）。
 *   所以每个规则都配了"边界不命中"的用例：2 道选择题不算直线、没有耗时数据不判过快、
 *   同题干但答案一致不算矛盾。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnswerQualityServiceTest {

    private static final Integer Q1 = 1;
    private static final Integer Q2 = 2;
    private static final Integer Q3 = 3;

    @Mock
    private AnswerMapper answerMapper;

    @InjectMocks
    private AnswerQualityService service;

    // ==================== 夹具 ====================

    private static Question choice(int id, String title) {
        Question q = new Question();
        q.setId(id);
        q.setType(1);
        q.setTitle(title);
        q.setRequired(1);
        return q;
    }

    private static Question textQuestion(int id, String title) {
        Question q = new Question();
        q.setId(id);
        q.setType(3);
        q.setTitle(title);
        return q;
    }

    private static AnswerDetail answer(int questionId, String content) {
        AnswerDetail d = new AnswerDetail();
        d.setQuestionId(questionId);
        d.setContent(content);
        return d;
    }

    /** 每道选择题都给两个选项 A / B（顺序即位次）。 */
    private static Map<Integer, List<Option>> twoOptions(Question... questions) {
        Map<Integer, List<Option>> map = new HashMap<>();
        for (Question q : questions) {
            List<Option> options = new ArrayList<>();
            options.add(option(q.getId(), "A", 0));
            options.add(option(q.getId(), "B", 1));
            map.put(q.getId(), options);
        }
        return map;
    }

    private static Option option(int questionId, String content, int sortOrder) {
        Option o = new Option();
        o.setQuestionId(questionId);
        o.setContent(content);
        o.setSortOrder(sortOrder);
        return o;
    }

    private static Map<String, Object> reasonRow(String reasons, int cnt) {
        Map<String, Object> row = new HashMap<>();
        row.put("reasons", reasons);
        row.put("cnt", cnt);
        return row;
    }

    // ==================== 规则 1：答题过快 ====================

    @Test
    @DisplayName("答题过快：总耗时 < 题数 × 1.5 秒 → TOO_FAST")
    void tooFast() {
        List<Question> qs = List.of(choice(Q1, "题一"), choice(Q2, "题二"), choice(Q3, "题三"));
        // 3 题 × 1500ms = 4500ms 阈值；4000ms 命中；答案位次 0/1/0 → 不触发直线
        var verdict = service.evaluate(
                List.of(answer(Q1, "A"), answer(Q2, "B"), answer(Q3, "A")),
                qs, twoOptions(qs.toArray(new Question[0])), 4000);

        assertTrue(verdict.suspicious());
        assertTrue(verdict.reasons().contains(AnswerQuality.TOO_FAST));
    }

    @Test
    @DisplayName("★ 没有耗时数据（null）时不判过快 —— 不能把\"没上报\"当成\"很快\"")
    void noDurationIsNotTooFast() {
        List<Question> qs = List.of(choice(Q1, "题一"), choice(Q2, "题二"));
        var verdict = service.evaluate(
                List.of(answer(Q1, "A"), answer(Q2, "B")),
                qs, twoOptions(qs.toArray(new Question[0])), null);

        assertFalse(verdict.suspicious(), "不该命中任何规则，实际：" + verdict.reasons());
    }

    @Test
    @DisplayName("耗时充足时不判过快")
    void longEnoughIsFine() {
        List<Question> qs = List.of(choice(Q1, "题一"), choice(Q2, "题二"), choice(Q3, "题三"));
        var verdict = service.evaluate(
                List.of(answer(Q1, "A"), answer(Q2, "B"), answer(Q3, "B")),
                qs, twoOptions(qs.toArray(new Question[0])), 60_000);

        assertFalse(verdict.reasons().contains(AnswerQuality.TOO_FAST));
    }

    // ==================== 规则 2：直线作答 ====================

    @Test
    @DisplayName("直线作答：3 道选择题都选了第 1 项 → STRAIGHT_LINE")
    void straightLine() {
        List<Question> qs = List.of(choice(Q1, "题一"), choice(Q2, "题二"), choice(Q3, "题三"));
        var verdict = service.evaluate(
                List.of(answer(Q1, "A"), answer(Q2, "A"), answer(Q3, "A")),
                qs, twoOptions(qs.toArray(new Question[0])), 60_000);

        assertTrue(verdict.reasons().contains(AnswerQuality.STRAIGHT_LINE));
    }

    @Test
    @DisplayName("★ 只有 2 道选择题时不判直线（2 题同选一项太常见，判了就是误伤）")
    void twoQuestionsAreNotStraightLine() {
        List<Question> qs = List.of(choice(Q1, "题一"), choice(Q2, "题二"));
        var verdict = service.evaluate(
                List.of(answer(Q1, "A"), answer(Q2, "A")),
                qs, twoOptions(qs.toArray(new Question[0])), 60_000);

        assertFalse(verdict.reasons().contains(AnswerQuality.STRAIGHT_LINE));
    }

    @Test
    @DisplayName("位置有区分（0/1/0）时不算直线")
    void differentPositionsAreNotStraightLine() {
        List<Question> qs = List.of(choice(Q1, "题一"), choice(Q2, "题二"), choice(Q3, "题三"));
        var verdict = service.evaluate(
                List.of(answer(Q1, "A"), answer(Q2, "B"), answer(Q3, "A")),
                qs, twoOptions(qs.toArray(new Question[0])), 60_000);

        assertFalse(verdict.reasons().contains(AnswerQuality.STRAIGHT_LINE));
    }

    // ==================== 规则 3：答案不在选项里 ====================

    @Test
    @DisplayName("答案不在选项里（脚本灌入的特征）→ OPTION_MISMATCH")
    void optionMismatch() {
        List<Question> qs = List.of(choice(Q1, "题一"));
        var verdict = service.evaluate(
                List.of(answer(Q1, "Z")),
                qs, twoOptions(qs.toArray(new Question[0])), 60_000);

        assertTrue(verdict.reasons().contains(AnswerQuality.OPTION_MISMATCH));
    }

    @Test
    @DisplayName("★ 填空题不参与选项匹配（它的答案本来就不在选项里）")
    void textAnswerIsNotMismatch() {
        List<Question> qs = List.of(textQuestion(Q1, "填空"));
        var verdict = service.evaluate(
                List.of(answer(Q1, "随便写的")),
                qs, Map.of(), 60_000);

        assertFalse(verdict.reasons().contains(AnswerQuality.OPTION_MISMATCH));
    }

    // ==================== 规则 4：同题干答案不一致 ====================

    @Test
    @DisplayName("同题干的两道题答案不一致 → DUP_TITLE_CONFLICT（可自动判定的那部分\"矛盾\"）")
    void duplicateTitleConflict() {
        Question a = choice(Q1, "你的年龄段");
        Question b = choice(Q2, "你的年龄段");   // 题干完全相同
        List<Question> qs = List.of(a, b);
        var verdict = service.evaluate(
                List.of(answer(Q1, "A"), answer(Q2, "B")),
                qs, twoOptions(qs.toArray(new Question[0])), 60_000);

        assertTrue(verdict.reasons().contains(AnswerQuality.DUP_TITLE_CONFLICT));
    }

    @Test
    @DisplayName("★ 同题干但答案一致 → 不判（重复题是设计选择，不是矛盾）")
    void duplicateTitleSameAnswerIsFine() {
        Question a = choice(Q1, "你的年龄段");
        Question b = choice(Q2, "你的年龄段");
        List<Question> qs = List.of(a, b);
        var verdict = service.evaluate(
                List.of(answer(Q1, "A"), answer(Q2, "A")),
                qs, twoOptions(qs.toArray(new Question[0])), 60_000);

        assertFalse(verdict.reasons().contains(AnswerQuality.DUP_TITLE_CONFLICT));
    }

    // ==================== 一条干净答卷 ====================

    @Test
    @DisplayName("正常答卷：任何规则都不命中，reasons 为空")
    void cleanAnswerHasNoReason() {
        List<Question> qs = List.of(choice(Q1, "题一"), choice(Q2, "题二"), choice(Q3, "题三"));
        var verdict = service.evaluate(
                List.of(answer(Q1, "A"), answer(Q2, "B"), answer(Q3, "A")),
                qs, twoOptions(qs.toArray(new Question[0])), 45_000);

        assertFalse(verdict.suspicious(), "不该命中，实际：" + verdict.reasons());
        assertTrue(verdict.reasons().isEmpty());
        assertEquals("", verdict.joined());
    }

    // ==================== 摘要 ====================

    @Test
    @DisplayName("质控摘要：把\"原因组合\"拆成单码累加，并算百分比")
    void summarizeSplitsReasonCodes() {
        when(answerMapper.countSuspiciousBySurveyId(100)).thenReturn(5);
        when(answerMapper.countByReasons(100)).thenReturn(List.of(
                reasonRow("TOO_FAST,STRAIGHT_LINE", 3),
                reasonRow("TOO_FAST", 2)));
        when(answerMapper.findSuspiciousBySurveyId(100)).thenReturn(List.of());

        Map<String, Object> summary = service.summarize(100, 63);

        assertEquals(63, summary.get("total"));
        assertEquals(5, summary.get("suspicious"));
        assertEquals(7.9, summary.get("suspiciousRate"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) summary.get("reasons");
        Map<String, Integer> byCode = new HashMap<>();
        for (Map<String, Object> r : reasons) {
            byCode.put((String) r.get("code"), (Integer) r.get("count"));
        }
        assertEquals(5, byCode.get(AnswerQuality.TOO_FAST), "3 + 2 应累加为 5");
        assertEquals(3, byCode.get(AnswerQuality.STRAIGHT_LINE));
        // 中文说明必须能取到（前端直接展示 label）
        assertTrue(reasons.stream().allMatch(r -> r.get("label") != null));
    }
}
