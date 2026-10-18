package com.survey.service;

import com.survey.common.BizException;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「从模板创建」的热度计数（Mockito，不连库）。
 *
 * ★ 为什么要钉住：
 *   `template_use_count` 是模板管理里"看哪个模板热门"的唯一数据来源。
 *   两个容易写错的点：
 *     ① 复制**已发布问卷**（isTemplate=0）也走 createFromTemplate，但那不是"用模板"，不能 +1；
 *     ② 计数必须与复制在同一个事务里（失败回滚时计数不能多）。
 */
@ExtendWith(MockitoExtension.class)
class SurveyServiceTemplateUsageTest {

    private static final Integer TEMPLATE_ID = 10;
    private static final Integer USER_ID = 7;

    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private OptionMapper optionMapper;

    @InjectMocks
    private SurveyService surveyService;

    private Survey source(Integer isTemplate, Integer status) {
        Survey s = new Survey();
        s.setId(TEMPLATE_ID);
        s.setUserId(0);
        s.setTitle("模板");
        s.setIsTemplate(isTemplate);
        s.setStatus(status);
        s.setDeleted(0);
        return s;
    }

    /** copySurvey 会 insert 一条新问卷，这里回填自增 id。 */
    private void stubCopy() {
        when(questionMapper.findBySurveyId(TEMPLATE_ID)).thenReturn(List.of());
        doAnswer(inv -> {
            Survey inserted = inv.getArgument(0);
            inserted.setId(99);
            return 1;
        }).when(surveyMapper).insert(any(Survey.class));
    }

    @Test
    @DisplayName("★ 从模板创建成功 → 模板使用次数 +1")
    void createFromTemplate_shouldIncreaseUsage() {
        when(surveyMapper.findById(TEMPLATE_ID)).thenReturn(source(1, 1));
        stubCopy();

        assertDoesNotThrow(() -> surveyService.createFromTemplate(TEMPLATE_ID, USER_ID));

        verify(surveyMapper).incrementTemplateUseCount(TEMPLATE_ID);
    }

    @Test
    @DisplayName("复制已发布问卷（非模板）→ 不增加模板使用次数")
    void createFromPublishedSurvey_shouldNotIncreaseUsage() {
        when(surveyMapper.findById(TEMPLATE_ID)).thenReturn(source(0, 1));
        stubCopy();

        assertDoesNotThrow(() -> surveyService.createFromTemplate(TEMPLATE_ID, USER_ID));

        verify(surveyMapper, never()).incrementTemplateUseCount(any());
    }

    @Test
    @DisplayName("草稿且非模板 → 400，计数不动作")
    void createFromDraft_shouldReject() {
        when(surveyMapper.findById(TEMPLATE_ID)).thenReturn(source(0, 0));

        BizException ex = assertThrows(BizException.class,
                () -> surveyService.createFromTemplate(TEMPLATE_ID, USER_ID));
        assertTrue(ex.getMessage().contains("无法复制"), "实际文案：" + ex.getMessage());
        verify(surveyMapper, never()).incrementTemplateUseCount(any());
    }
}
