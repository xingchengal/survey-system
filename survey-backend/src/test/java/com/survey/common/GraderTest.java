package com.survey.common;

import com.survey.entity.AnswerDetail;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 判分规则的单元测试（纯逻辑，不连库、不起容器）。
 *
 * ── 为什么必须钉住 ────────────────────────────────────
 *   分数算错一次就无法解释，而且**没有任何报错**：多选漏选被算成对、及格线用 &gt; 而不是 &gt;=，
 *   都只会体现为"某个学员的分数不对"，排查成本极高。所以每条规则都单独一个用例。
 *
 * ── 规则口径（与 Grader 类注释一致）────────────────────
 *   单选/下拉：恰好一项且等于正确答案 → 得分；
 *   多选：集合完全一致（全对才得分，不做部分得分）；
 *   其它题型不参与；未设分值的题不计入总分。
 */
class GraderTest {

    private static Survey survey(Integer gradeEnabled, Integer passScore) {
        Survey s = new Survey();
        s.setId(7);
        s.setTitle("期中测验");
        s.setGradeEnabled(gradeEnabled);
        s.setPassScore(passScore);
        return s;
    }

    private static Question question(int id, int type, Integer score) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setTitle("第 " + id + " 题");
        q.setScore(score);
        return q;
    }

    private static Option option(String content, boolean correct) {
        Option o = new Option();
        o.setContent(content);
        o.setIsCorrect(correct ? 1 : 0);
        return o;
    }

    private static AnswerDetail answer(int questionId, String content) {
        AnswerDetail d = new AnswerDetail();
        d.setQuestionId(questionId);
        d.setContent(content);
        return d;
    }

    private static Map<Integer, List<Option>> options(int questionId, Option... opts) {
        Map<Integer, List<Option>> map = new LinkedHashMap<>();
        map.put(questionId, List.of(opts));
        return map;
    }

    // ==================== 单选 / 下拉 ====================

    @Test
    @DisplayName("单选：选中正确答案 → 得该题全部分值")
    void single_correct() {
        Question q = question(11, 1, 10);
        Grader.Result r = Grader.grade(survey(1, null), List.of(q),
                options(11, option("A", true), option("B", false)),
                List.of(answer(11, "A")));
        assertEquals(10, r.totalScore());
        assertEquals(10, r.earnedScore());
        assertNull(r.passed(), "没设及格线时 passed 必须是 null，而不是 0");
    }

    @Test
    @DisplayName("单选：选错 → 0 分（不能因为'选了一个'就给分）")
    void single_wrong() {
        Question q = question(11, 1, 10);
        Grader.Result r = Grader.grade(survey(1, null), List.of(q),
                options(11, option("A", true), option("B", false)),
                List.of(answer(11, "B")));
        assertEquals(0, r.earnedScore());
    }

    @Test
    @DisplayName("单选：没作答 → 0 分，且总分不变")
    void single_unanswered() {
        Question q = question(11, 1, 10);
        Grader.Result r = Grader.grade(survey(1, null), List.of(q),
                options(11, option("A", true)), List.of());
        assertEquals(10, r.totalScore());
        assertEquals(0, r.earnedScore());
    }

    @Test
    @DisplayName("单选：答案前后空格要被忽略（前端偶尔会带空白）")
    void single_trimsSpaces() {
        Question q = question(11, 1, 10);
        Grader.Result r = Grader.grade(survey(1, null), List.of(q),
                options(11, option(" A ", true)),
                List.of(answer(11, "A")));
        assertEquals(10, r.earnedScore());
    }

    @Test
    @DisplayName("下拉（type=5）：与单选同一口径")
    void select_sameAsSingle() {
        Question q = question(12, 5, 5);
        Grader.Result r = Grader.grade(survey(1, null), List.of(q),
                options(12, option("选项1", true), option("选项2", false)),
                List.of(answer(12, "选项1")));
        assertEquals(5, r.earnedScore());
    }

    // ==================== 多选 ====================

    @Test
    @DisplayName("多选：全对 → 满分；顺序无关")
    void multi_allCorrect_ignoresOrder() {
        Question q = question(13, 2, 10);
        Grader.Result r = Grader.grade(survey(1, null), List.of(q),
                options(13, option("A", true), option("B", true), option("C", false)),
                List.of(answer(13, "B"), answer(13, "A")));
        assertEquals(10, r.earnedScore());
    }

    @Test
    @DisplayName("多选：漏选 → 0 分（全对才得分，不做部分得分）")
    void multi_missingOne_getsZero() {
        Question q = question(13, 2, 10);
        Grader.Result r = Grader.grade(survey(1, null), List.of(q),
                options(13, option("A", true), option("B", true), option("C", false)),
                List.of(answer(13, "A")));
        assertEquals(0, r.earnedScore());
    }

    @Test
    @DisplayName("多选：错选（多勾了一个错误项）→ 0 分")
    void multi_extraWrong_getsZero() {
        Question q = question(13, 2, 10);
        Grader.Result r = Grader.grade(survey(1, null), List.of(q),
                options(13, option("A", true), option("B", true), option("C", false)),
                List.of(answer(13, "A"), answer(13, "B"), answer(13, "C")));
        assertEquals(0, r.earnedScore());
    }

    @Test
    @DisplayName("多选：重复提交同一选项不应被当成'勾了多个'")
    void multi_duplicateRows_deduped() {
        Question q = question(13, 2, 10);
        Grader.Result r = Grader.grade(survey(1, null), List.of(q),
                options(13, option("A", true), option("B", true)),
                List.of(answer(13, "A"), answer(13, "A"), answer(13, "B")));
        assertEquals(10, r.earnedScore());
    }

    // ==================== 总分 / 及格 / 不判分 ====================

    @Test
    @DisplayName("总分只统计 score>0 的题；未设分值的题不计入")
    void total_ignoresZeroScoreQuestions() {
        Question scored = question(11, 1, 10);
        Question unscored = question(12, 1, 0);
        Grader.Result r = Grader.grade(survey(1, null), List.of(scored, unscored),
                Map.of(11, List.of(option("A", true))),
                List.of(answer(11, "A"), answer(12, "A")));
        assertEquals(10, r.totalScore(), "没设分值的题不能悄悄进总分");
        assertEquals(10, r.earnedScore());
    }

    @Test
    @DisplayName("及格：得分**等于**及格分算及格（>= 而不是 >）")
    void passed_boundaryInclusive() {
        Question q = question(11, 1, 10);
        Grader.Result r = Grader.grade(survey(1, 10), List.of(q),
                options(11, option("A", true)), List.of(answer(11, "A")));
        assertEquals(1, r.passed(), "等于及格分必须算及格");
    }

    @Test
    @DisplayName("及格：差一分不算及格")
    void passed_justBelow() {
        Question q = question(11, 1, 10);
        Grader.Result r = Grader.grade(survey(1, 11), List.of(q),
                options(11, option("A", true)), List.of(answer(11, "A")));
        assertEquals(0, r.passed());
    }

    @Test
    @DisplayName("普通问卷（gradeEnabled=0）→ 返回 null，调用方不得写任何分数字段")
    void notExam_returnsNull() {
        Question q = question(11, 1, 10);
        assertNull(Grader.grade(survey(0, null), List.of(q),
                options(11, option("A", true)), List.of(answer(11, "A"))));
        assertNull(Grader.grade(survey(null, null), List.of(q),
                options(11, option("A", true)), List.of(answer(11, "A"))));
    }

    @Test
    @DisplayName("矩阵/排序/填空即使被设了分值也不参与判分（防御：保存时本应拒绝）")
    void nonGradableTypes_areSkipped() {
        Question matrix = question(14, 8, 10);
        Grader.Result r = Grader.grade(survey(1, null), List.of(matrix), Map.of(),
                List.of(answer(14, "满意")));
        assertEquals(0, r.totalScore(), "不可判分题型的分数不能进总分");
        assertEquals(0, r.earnedScore());
    }

    @Test
    @DisplayName("多选题只标了一个正确答案时，与单选同口径（集合比较）")
    void multi_singleCorrectAnswer_stillWorks() {
        Question q = question(13, 2, 6);
        Grader.Result r = Grader.grade(survey(1, null), List.of(q),
                options(13, option("A", true), option("B", false)),
                List.of(answer(13, "A")));
        assertEquals(6, r.earnedScore());
    }

    @Test
    @DisplayName("isGradable：只有 1/2/5，其余（含 null）都不行")
    void isGradable_onlyChoiceTypes() {
        assertTrue(Grader.isGradable(1));
        assertTrue(Grader.isGradable(2));
        assertTrue(Grader.isGradable(5));
        for (int type : new int[]{3, 4, 6, 7, 8, 9, 10}) {
            assertFalse(Grader.isGradable(type), "type=" + type + " 不该参与判分");
        }
        assertFalse(Grader.isGradable(null));
    }

    @Test
    @DisplayName("totalScore：null/负数分值都不计入（防御脏数据）")
    void totalScore_ignoresNullAndNegative() {
        Question a = question(1, 1, 10);
        Question b = question(2, 1, null);
        Question c = question(3, 1, -5);
        // ★ 不能用 List.of：它不接受 null 元素（会直接 NPE），而这里正好要造一个 score=null 的题
        assertEquals(10, Grader.totalScore(java.util.Arrays.asList(a, b, c)));
    }
}
