package com.survey.common;

import com.survey.entity.Option;
import com.survey.entity.Question;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 填写页抽题 / 乱序的唯一口径（纯函数，不连库）。
 *
 * ★ 最关键的不变量：**同一 token 必须抽出同一套题**。
 *   如果刷新页面就换题，用户答到一半的内容会对不上题目；提交时后端也会用错题集算总分。
 */
class AttemptShuffleTest {

    private static final Integer SURVEY_ID = 7;
    private static final String TOKEN = "attempt-token-1";

    private static Question question(int id, int sortOrder) {
        Question q = new Question();
        q.setId(id);
        q.setSurveyId(SURVEY_ID);
        q.setType(1);
        q.setTitle("第 " + id + " 题");
        q.setSortOrder(sortOrder);
        return q;
    }

    private static List<Question> fiveQuestions() {
        return List.of(question(11, 1), question(12, 2), question(13, 3),
                question(14, 4), question(15, 5));
    }

    private static List<Integer> ids(List<Question> questions) {
        return questions.stream().map(Question::getId).toList();
    }

    @Test
    @DisplayName("★ 同一 token 抽题结果可复现（刷新不换题）")
    void pickIsDeterministicForSameToken() {
        List<Question> all = fiveQuestions();

        List<Question> first = AttemptShuffle.pick(SURVEY_ID, all, 3, TOKEN);
        List<Question> second = AttemptShuffle.pick(SURVEY_ID, all, 3, TOKEN);

        assertEquals(ids(first), ids(second));
    }

    @Test
    @DisplayName("抽 N 道：数量正确，且按 sortOrder 还原相对顺序")
    void pickKeepsRelativeOrder() {
        List<Question> picked = AttemptShuffle.pick(SURVEY_ID, fiveQuestions(), 3, TOKEN);

        assertEquals(3, picked.size());
        List<Integer> order = picked.stream().map(Question::getSortOrder).toList();
        assertEquals(order.stream().sorted().toList(), order, "抽到的题必须按原题序展示");
    }

    @Test
    @DisplayName("0 / 负数 / 超过题数都视为不抽，返回全量")
    void perAttemptZeroOrOverflowMeansAll() {
        assertEquals(5, AttemptShuffle.pick(SURVEY_ID, fiveQuestions(), 0, TOKEN).size());
        assertEquals(5, AttemptShuffle.pick(SURVEY_ID, fiveQuestions(), -1, TOKEN).size());
        assertEquals(5, AttemptShuffle.pick(SURVEY_ID, fiveQuestions(), 99, TOKEN).size());
    }

    @Test
    @DisplayName("题目乱序：集合不变，但顺序与原序不同")
    void arrangeShufflesQuestionOrder() {
        List<Question> all = fiveQuestions();

        List<Question> shuffled = AttemptShuffle.arrange(SURVEY_ID, all, 0, true, TOKEN);

        assertEquals(5, shuffled.size());
        assertEquals(ids(all).stream().sorted().toList(), ids(shuffled).stream().sorted().toList());
        assertNotEquals(ids(all), ids(shuffled), "固定 token 下应打乱（否则等于没开）");
    }

    @Test
    @DisplayName("选项乱序：每题选项集合不变，且同 token 可复现")
    void shuffleOptionsKeepsSetAndIsDeterministic() {
        List<String> first = shuffledOptionContents();
        List<String> second = shuffledOptionContents();

        assertEquals(List.of("A", "B", "C"), first.stream().sorted().toList());
        assertEquals(first, second, "同 token 的选项顺序必须稳定");
    }

    /** 每次从原序构造再打乱：shuffleOptions 是就地重排，对已打乱的列表再跑会得到另一个顺序。 */
    private static List<String> shuffledOptionContents() {
        Question q = question(11, 1);
        q.setOptions(List.of(option(11, "A"), option(11, "B"), option(11, "C")));
        AttemptShuffle.shuffleOptions(SURVEY_ID, List.of(q), TOKEN);
        return q.getOptions().stream().map(Option::getContent).toList();
    }

    @Test
    @DisplayName("抽题数量合法性：0 或 1..题数")
    void perAttemptValidation() {
        assertTrue(AttemptShuffle.isPerAttemptValid(0, 5));
        assertTrue(AttemptShuffle.isPerAttemptValid(1, 5));
        assertTrue(AttemptShuffle.isPerAttemptValid(5, 5));
        assertFalse(AttemptShuffle.isPerAttemptValid(6, 5));
        assertFalse(AttemptShuffle.isPerAttemptValid(-1, 5));
    }

    private static Option option(int questionId, String content) {
        Option o = new Option();
        o.setQuestionId(questionId);
        o.setContent(content);
        o.setSortOrder(0);
        return o;
    }
}
