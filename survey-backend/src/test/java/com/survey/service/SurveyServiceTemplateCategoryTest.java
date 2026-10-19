package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.Survey;
import com.survey.mapper.SurveyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「设为模板」与分类清单的联动（Mockito，不连库）。
 *
 * ★ 钉住两条：
 *   ① 设为模板时现场输入的新分类要自动登记（否则模板管理里永远缺这一类）；
 *   ② 取消模板时不登记（分类会被清空，不该往清单里写）。
 */
@ExtendWith(MockitoExtension.class)
class SurveyServiceTemplateCategoryTest {

    private static final Integer SURVEY_ID = 88;

    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private TemplateCategoryService templateCategoryService;

    @InjectMocks
    private SurveyService surveyService;

    private Survey survey() {
        Survey s = new Survey();
        s.setId(SURVEY_ID);
        s.setDeleted(0);
        return s;
    }

    @Test
    @DisplayName("设为模板：trim 分类、自动登记分类、写回问卷")
    void setTemplate_shouldEnsureCategory() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey());
        when(surveyMapper.setTemplate(SURVEY_ID, 1, "培训反馈")).thenReturn(1);

        int rows = surveyService.setTemplate(SURVEY_ID, 1, "  培训反馈 ");

        assertEquals(1, rows);
        verify(templateCategoryService).ensure("培训反馈");
        verify(surveyMapper).setTemplate(SURVEY_ID, 1, "培训反馈");
    }

    @Test
    @DisplayName("设为模板但分类为空：400，不写库也不登记")
    void setTemplate_withoutCategory_shouldReject() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey());

        BizException ex = assertThrows(BizException.class,
                () -> surveyService.setTemplate(SURVEY_ID, 1, "  "));

        assertTrue(ex.getMessage().contains("必须选择模板分类"));
        verify(templateCategoryService, never()).ensure(any());
        verify(surveyMapper, never()).setTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("取消模板：分类归一成 NULL，且不登记分类")
    void cancelTemplate_shouldNotEnsure() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey());
        when(surveyMapper.setTemplate(SURVEY_ID, 0, null)).thenReturn(1);

        surveyService.setTemplate(SURVEY_ID, 0, "残留分类");

        verify(templateCategoryService, never()).ensure(any());
        verify(surveyMapper).setTemplate(SURVEY_ID, 0, null);
    }

    @Test
    @DisplayName("回收站里的问卷不能设模板：400，且不碰分类清单")
    void setTemplate_inRecycleBin_shouldReject() {
        Survey deleted = survey();
        deleted.setDeleted(1);
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(deleted);

        BizException ex = assertThrows(BizException.class,
                () -> surveyService.setTemplate(SURVEY_ID, 1, "满意度"));

        assertTrue(ex.getMessage().contains("回收站"));
        verify(templateCategoryService, never()).ensure(any());
        verify(surveyMapper, never()).setTemplate(any(), any(), any());
    }
}
