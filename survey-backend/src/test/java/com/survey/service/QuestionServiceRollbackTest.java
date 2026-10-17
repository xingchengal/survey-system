package com.survey.service;

import com.survey.common.BizException;
import com.survey.dto.SurveySnapshot;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.SurveyVersion;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 问卷回滚（`QuestionService.rollbackToVersion`）的单元测试。
 *
 * ── 这里钉住的是"回滚的边界"，不是"回滚能跑通" ────────────
 *   1. 题目都还在 → 走**更新**（保留 id），历史答卷的 question_id 不会悬空；
 *   2. 有题已被删 → 会重新插入拿**新 id**；若还有跳转规则指向它，**必须拒绝**，
 *      而不是静默把规则置空（那会让用户以为回滚成功了）；
 *   3. 第三点单独断言：拒绝时**不能有任何写库动作**（校验必须在 doSaveQuestions 之前）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionServiceRollbackTest {

    private static final Integer SURVEY_ID = 100;

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private OptionMapper optionMapper;
    @Mock
    private SurveyVersionService surveyVersionService;

    @InjectMocks
    private QuestionService questionService;

    // ==================== 夹具 ====================

    private static Question single(int id, String title, int sortOrder, String... optionTexts) {
        Question q = new Question();
        q.setId(id);
        q.setSurveyId(SURVEY_ID);
        q.setType(1);
        q.setTitle(title);
        q.setRequired(1);
        q.setSortOrder(sortOrder);
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < optionTexts.length; i++) {
            Option o = new Option();
            o.setId(1000 + i);
            o.setQuestionId(id);
            o.setContent(optionTexts[i]);
            o.setSortOrder(i);
            options.add(o);
        }
        q.setOptions(options);
        return q;
    }

    private static SurveyVersion version(int id, int versionNo) {
        SurveyVersion v = new SurveyVersion();
        v.setId(id);
        v.setSurveyId(SURVEY_ID);
        v.setVersionNo(versionNo);
        v.setQuestionsJson("{}");   // 实际内容由 loadSnapshot 的 mock 提供
        return v;
    }

    private static SurveySnapshot snapshot(List<Question> questions) {
        return new SurveySnapshot("问卷", "描述", questions);
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("回滚：题目都还在 → 走更新保留 id，并返回新生成的版本")
    void rollbackUpdatesExistingQuestions() {
        SurveyVersion target = version(5, 2);
        Question fromSnapshot = single(1, "旧-题一", 0, "A");
        SurveyVersion newVersion = version(9, 8);

        when(surveyVersionService.loadSnapshot(target)).thenReturn(snapshot(List.of(fromSnapshot)));
        // 库里还留有 id=1 的题 → 不应重建
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(single(1, "现在-题一", 0, "A")));
        when(surveyVersionService.getLatest(SURVEY_ID)).thenReturn(newVersion);

        SurveyVersion result = questionService.rollbackToVersion(target, 7);

        verify(questionMapper).update(any(Question.class));
        verify(questionMapper, never()).insert(any(Question.class));
        assertSame(newVersion, result, "应当返回回滚产生的那个新版本");
    }

    @Test
    @DisplayName("★ 回滚拒绝：跳转规则指向一道在后续版本里被删掉的题（重建成新 id 会让规则悬空）")
    void rollbackRejectsJumpToRecreatedQuestion() {
        SurveyVersion target = version(5, 2);
        Question a = single(1, "题A", 0, "跳到第 99 题");
        a.getOptions().get(0).setNextQuestionId(99);
        Question b = single(99, "题B", 1, "x");

        when(surveyVersionService.loadSnapshot(target)).thenReturn(snapshot(List.of(a, b)));
        // 库里已经空了（两题都在后续版本里被删掉）→ 99 会被重新插入，拿新 id
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of());

        BizException ex = assertThrows(BizException.class,
                () -> questionService.rollbackToVersion(target, 7));

        assertTrue(ex.getMessage().contains("跳转规则"), "文案要讲清原因，实际：" + ex.getMessage());
        // ★ 校验必须在写库之前：拒绝时一条写操作都不能发生
        verify(questionMapper, never()).update(any(Question.class));
        verify(questionMapper, never()).insert(any(Question.class));
        verify(optionMapper, never()).insert(any(Option.class));
    }

    @Test
    @DisplayName("回滚：有题被删但没有跳转指向它 → 允许，重新插入拿新 id")
    void rollbackRecreatesDeletedQuestionWithoutJump() {
        SurveyVersion target = version(5, 2);
        Question b = single(99, "题B", 0, "x");
        SurveyVersion newVersion = version(9, 8);

        when(surveyVersionService.loadSnapshot(target)).thenReturn(snapshot(List.of(b)));
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of());
        when(surveyVersionService.getLatest(SURVEY_ID)).thenReturn(newVersion);

        questionService.rollbackToVersion(target, 7);

        verify(questionMapper).insert(any(Question.class));
        verify(questionMapper, never()).update(any(Question.class));
    }

    @Test
    @DisplayName("回滚后要生成新版本（回滚也是一次编辑，历史不丢）")
    void rollbackCapturesNewVersion() {
        SurveyVersion target = version(5, 2);
        Question q = single(1, "题一", 0, "A");

        when(surveyVersionService.loadSnapshot(target)).thenReturn(snapshot(List.of(q)));
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(single(1, "题一", 0, "A")));
        when(surveyVersionService.getLatest(SURVEY_ID)).thenReturn(version(9, 8));

        questionService.rollbackToVersion(target, 7);

        // 只要走了保存逻辑，末尾就会 capture（note 带"回滚到 v2"）
        verify(surveyVersionService).capture(eq(SURVEY_ID), eq(7), eq("回滚到 v2"));
    }
}
