package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 考试配置的**保存校验**单元测试。
 *
 * ── 为什么这组校验必须存在 ────────────────────────────
 *   判分规则再正确，也架不住"配了一道永远不会得分的题"：
 *     · 给矩阵/填空设分值 → 提交时 Grader 会跳过它，作者以为这题 10 分，总分却少 10；
 *     · 设了分值但没标正确答案 → 这题恒为 0 分，而且**没有任何报错**；
 *     · 单选题标了两个正确答案 → 判分按"集合完全一致"永远不成立，同样恒 0 分。
 *   这三种都只会在出成绩之后才被发现，所以一律在保存时 400 拒绝，不静默纠正。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionServiceExamValidationTest {

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private OptionMapper optionMapper;
    @Mock
    private SurveyVersionService surveyVersionService;
    @Mock
    private WalletService walletService;
    @Mock
    private SurveyMapper surveyMapper;

    @InjectMocks
    private QuestionService questionService;

    private static final Integer SURVEY_ID = 50;
    private final AtomicInteger idSeq = new AtomicInteger(1000);

    /** 造一道题；correctIndexes 里列出的选项标成正确答案。 */
    private static Question question(int type, int score, String[] contents, int[] correctIndexes) {
        Question q = new Question();
        q.setSurveyId(SURVEY_ID);
        q.setType(type);
        q.setTitle("题目");
        q.setRequired(1);
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            Option o = new Option();
            o.setContent(contents[i]);
            o.setIsCorrect(0);
            for (int idx : correctIndexes) {
                if (idx == i) o.setIsCorrect(1);
            }
            options.add(o);
        }
        q.setOptions(options);
        q.setScore(score);
        return q;
    }

    private void givenInsertsAssignIds() {
        when(questionMapper.insert(any(Question.class))).thenAnswer(inv -> {
            Question q = inv.getArgument(0);
            q.setId(idSeq.incrementAndGet());
            return 1;
        });
    }

    @Test
    @DisplayName("填空/矩阵等题型设分值 → 400（只有单选/多选/下拉能计分）")
    void scoreOnTextQuestion_rejected() {
        Question q = question(3, 10, new String[]{}, new int[]{});
        BizException e = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, List.of(q)));
        assertTrue(e.getMessage().contains("只有单选、多选、下拉"), e.getMessage());
    }

    @Test
    @DisplayName("设了分值但没标正确答案 → 400")
    void scoreWithoutCorrectAnswer_rejected() {
        Question q = question(1, 10, new String[]{"A", "B"}, new int[]{});
        BizException e = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, List.of(q)));
        assertTrue(e.getMessage().contains("没有指定正确答案"), e.getMessage());
    }

    @Test
    @DisplayName("单选题标了两个正确答案 → 400（判分会永远不成立）")
    void singleWithTwoCorrectAnswers_rejected() {
        Question q = question(1, 10, new String[]{"A", "B"}, new int[]{0, 1});
        BizException e = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, List.of(q)));
        assertTrue(e.getMessage().contains("恰好有一个正确答案"), e.getMessage());
    }

    @Test
    @DisplayName("负数分值 → 400")
    void negativeScore_rejected() {
        Question q = question(1, -1, new String[]{"A"}, new int[]{0});
        BizException e = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, List.of(q)));
        assertTrue(e.getMessage().contains("不能为负数"), e.getMessage());
    }

    @Test
    @DisplayName("分值超过上限（100）→ 400")
    void tooLargeScore_rejected() {
        Question q = question(1, 101, new String[]{"A"}, new int[]{0});
        BizException e = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, List.of(q)));
        assertTrue(e.getMessage().contains("不能超过 100"), e.getMessage());
    }

    @Test
    @DisplayName("合法的单选题：分值落库、正确答案标记落库")
    void validSingle_persistsScoreAndCorrectFlag() {
        givenInsertsAssignIds();
        Question q = question(1, 10, new String[]{"A", "B"}, new int[]{0});

        questionService.saveQuestions(SURVEY_ID, List.of(q));

        ArgumentCaptor<Question> savedQuestion = ArgumentCaptor.forClass(Question.class);
        verify(questionMapper).insert(savedQuestion.capture());
        assertEquals(10, savedQuestion.getValue().getScore());

        ArgumentCaptor<Option> savedOptions = ArgumentCaptor.forClass(Option.class);
        verify(optionMapper, times(2)).insert(savedOptions.capture());
        assertEquals(1, savedOptions.getAllValues().get(0).getIsCorrect());
        assertEquals(0, savedOptions.getAllValues().get(1).getIsCorrect());
    }

    @Test
    @DisplayName("排序题（也带 options）即使传了 correct 也会被归一成 0")
    void ranking_correctFlagIsCleared() {
        givenInsertsAssignIds();
        Question q = question(9, 0, new String[]{"甲", "乙"}, new int[]{0, 1});

        questionService.saveQuestions(SURVEY_ID, List.of(q));

        ArgumentCaptor<Option> savedOptions = ArgumentCaptor.forClass(Option.class);
        verify(optionMapper, times(2)).insert(savedOptions.capture());
        for (Option o : savedOptions.getAllValues()) {
            assertEquals(0, o.getIsCorrect(), "不可判分题型的正确答案标记必须清掉");
        }
    }

    @Test
    @DisplayName("分值=0 的单选题不要求标正确答案（只是不计分）")
    void zeroScore_doesNotRequireCorrectAnswer() {
        givenInsertsAssignIds();
        Question q = question(1, 0, new String[]{"A", "B"}, new int[]{});

        assertDoesNotThrow(() -> questionService.saveQuestions(SURVEY_ID, List.of(q)));
    }

    @Test
    @DisplayName("同一题两个内容相同的选项 → 400（否则答题页点一个会同时选中两个）")
    void duplicateOptionContents_rejected() {
        Question q = question(1, 0, new String[]{"111", "111"}, new int[]{});
        BizException e = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, List.of(q)));
        assertTrue(e.getMessage().contains("内容相同的选项"), e.getMessage());
    }

    @Test
    @DisplayName("重复判定按去空白后比较：\"A\" 与 \" A \" 也算重复")
    void duplicateOptionContents_trimmedComparison() {
        Question q = question(1, 0, new String[]{"A", " A "}, new int[]{});
        BizException e = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, List.of(q)));
        assertTrue(e.getMessage().contains("内容相同的选项"), e.getMessage());
    }

    @Test
    @DisplayName("考试模式已开启、却没有任何计分题 → 400（避免出 0/0 分的成绩卡）")
    void examSurveyWithoutScoredQuestion_rejected() {
        Survey survey = new Survey();
        survey.setId(SURVEY_ID);
        survey.setGradeEnabled(1);
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey);
        givenInsertsAssignIds();

        Question unscored = question(3, 0, new String[]{}, new int[]{});

        BizException e = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, List.of(unscored)));
        assertTrue(e.getMessage().contains("至少要有一道题设置分值"), e.getMessage());
    }
}
