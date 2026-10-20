package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 问卷设置归一与校验（Mockito，不连库）。
 *
 * ★ 为什么单独测 updateSurvey：`createSurvey` 只归零 status / shareCode / isTemplate，
 *   真正的设置归一（空串→NULL、0/1 开关、跳转协议、考试分值）只在 `updateSurvey` 走
 *   `normalizeSurveySettings`。这里钉住"哪些值会被静默改掉、哪些必须 400"。
 */
@ExtendWith(MockitoExtension.class)
class SurveyServiceSettingsTest {

    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private OptionMapper optionMapper;

    @InjectMocks
    private SurveyService surveyService;

    private static Survey survey(int id) {
        Survey s = new Survey();
        s.setId(id);
        s.setTitle("问卷");
        return s;
    }

    @Test
    @DisplayName("★ 空白设置归一为 NULL / 0；签到与公开名单只认 0/1")
    void updateSurveyNormalizesBlankSettings() {
        Survey s = survey(1);
        s.setAccessPassword("  ");
        s.setThankText("   ");
        s.setRedirectUrl("");
        s.setPublicResult(2);
        s.setRequireLogin(2);
        s.setCheckinEnabled(1);
        s.setShowParticipants(null);
        when(surveyMapper.update(s)).thenReturn(1);

        surveyService.updateSurvey(s);

        assertNull(s.getAccessPassword());
        assertNull(s.getThankText());
        assertNull(s.getRedirectUrl());
        assertEquals(Integer.valueOf(0), s.getPublicResult());
        assertEquals(Integer.valueOf(0), s.getRequireLogin());
        assertEquals(Integer.valueOf(1), s.getCheckinEnabled());
        assertEquals(Integer.valueOf(0), s.getShowParticipants());
        verify(surveyMapper).update(s);
    }

    @Test
    @DisplayName("★ 非 http(s) 跳转链接 / 超长密码 → 400，且不写库")
    void rejectsInvalidSettings() {
        Survey badUrl = survey(1);
        badUrl.setRedirectUrl("javascript:alert(1)");
        assertTrue(assertThrows(BizException.class, () -> surveyService.updateSurvey(badUrl))
                .getMessage().contains("http"), "伪协议必须被拒");

        Survey longPwd = survey(1);
        longPwd.setAccessPassword("x".repeat(21));
        assertTrue(assertThrows(BizException.class, () -> surveyService.updateSurvey(longPwd))
                .getMessage().contains("20"));

        verify(surveyMapper, never()).update(any());
    }

    @Test
    @DisplayName("★ 开启考试但没有计分题 → 400")
    void rejectsExamModeWithoutScoredQuestions() {
        Survey s = survey(1);
        s.setGradeEnabled(1);
        when(questionMapper.findBySurveyId(1)).thenReturn(List.of());

        assertTrue(assertThrows(BizException.class, () -> surveyService.updateSurvey(s))
                .getMessage().contains("分值"));
        verify(surveyMapper, never()).update(any());
    }

    @Test
    @DisplayName("★ 抽题数量不能超过题目总数；有逻辑跳转时禁止乱序 / 抽题")
    void rejectsDrawAndShuffleConflicts() {
        Survey tooMany = survey(1);
        tooMany.setQuestionsPerAttempt(5);
        when(questionMapper.findBySurveyId(1)).thenReturn(List.of(new Question(), new Question()));
        assertTrue(assertThrows(BizException.class, () -> surveyService.updateSurvey(tooMany))
                .getMessage().contains("题目总数"));

        Survey jump = survey(1);
        jump.setShuffleQuestions(1);
        when(questionMapper.findBySurveyId(1)).thenReturn(List.of(new Question()));
        when(optionMapper.countJumpOptions(1)).thenReturn(1);
        assertTrue(assertThrows(BizException.class, () -> surveyService.updateSurvey(jump))
                .getMessage().contains("逻辑跳转"));

        verify(surveyMapper, never()).update(any());
    }
}
