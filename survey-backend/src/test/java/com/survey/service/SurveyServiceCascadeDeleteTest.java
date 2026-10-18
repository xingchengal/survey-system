package com.survey.service;

import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyCollaboratorMapper;
import com.survey.mapper.SurveyIpMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyReviewMapper;
import com.survey.mapper.SurveyVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 彻底删除问卷的**级联完整性**（Mockito，不连库）。
 *
 * ★ 为什么专门钉这一组：
 *   `cascadeDeleteSurvey` 是"彻底删除"的唯一实现。项目后加的 4 张从属表
 *   （版本快照 / 协作者 / IP 记录 / 举报）如果没接进来，删除后会在库里留下孤儿行：
 *     · `t_survey_version` 里是**完整题目 JSON** —— 用户以为删干净了，内容其实还在；
 *     · 其它三张会出现在"共享给我的问卷"、IP 判定、审核台列表里，指向不存在的问卷。
 *   这类问题不会有任何报错，只能靠这条测试 + 真实数据核对发现。
 *
 * ★ 新加"从属于问卷"的表时：在 SurveyService 里补一行 deleteBySurveyId，并在这里补断言。
 */
@ExtendWith(MockitoExtension.class)
class SurveyServiceCascadeDeleteTest {

    private static final Integer SURVEY_ID = 900;

    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private OptionMapper optionMapper;
    @Mock
    private AnswerMapper answerMapper;
    @Mock
    private AnswerDetailMapper answerDetailMapper;
    @Mock
    private StatsCacheService statsCacheService;

    @Mock
    private SurveyVersionMapper surveyVersionMapper;
    @Mock
    private SurveyCollaboratorMapper surveyCollaboratorMapper;
    @Mock
    private SurveyIpMapper surveyIpMapper;
    @Mock
    private SurveyReviewMapper surveyReviewMapper;

    @InjectMocks
    private SurveyService surveyService;

    @Test
    @DisplayName("★ 彻底删除必须清空全部从属表，且问卷本体最后删")
    void cascadeDelete_shouldCleanEveryChildTable() {
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of());
        when(surveyMapper.deleteById(SURVEY_ID)).thenReturn(1);

        int rows = surveyService.cascadeDeleteSurvey(SURVEY_ID);

        assertEquals(1, rows);
        verify(answerDetailMapper).deleteBySurveyId(SURVEY_ID);
        verify(answerMapper).deleteBySurveyId(SURVEY_ID);
        verify(optionMapper).deleteBySurveyId(SURVEY_ID);
        verify(questionMapper).deleteBySurveyId(SURVEY_ID);
        // ★ 这 4 张是本轮补上的"后加子表"
        verify(surveyVersionMapper).deleteBySurveyId(SURVEY_ID);
        verify(surveyCollaboratorMapper).deleteBySurveyId(SURVEY_ID);
        verify(surveyIpMapper).deleteBySurveyId(SURVEY_ID);
        verify(surveyReviewMapper).deleteBySurveyId(SURVEY_ID);

        // 问卷本体必须在所有子表之后删，否则 survey_id 再也定位不到子表
        InOrder order = inOrder(surveyVersionMapper, surveyCollaboratorMapper,
                surveyIpMapper, surveyReviewMapper, surveyMapper);
        order.verify(surveyVersionMapper).deleteBySurveyId(SURVEY_ID);
        order.verify(surveyCollaboratorMapper).deleteBySurveyId(SURVEY_ID);
        order.verify(surveyIpMapper).deleteBySurveyId(SURVEY_ID);
        order.verify(surveyReviewMapper).deleteBySurveyId(SURVEY_ID);
        order.verify(surveyMapper).deleteById(SURVEY_ID);

        verify(statsCacheService).evictSurvey(eq(SURVEY_ID), anyList());
    }

    @Test
    @DisplayName("id 为 null 时直接返回 0，不执行任何删除")
    void cascadeDelete_nullId_noop() {
        assertEquals(0, surveyService.cascadeDeleteSurvey(null));
        verifyNoInteractions(surveyMapper, questionMapper, optionMapper, answerMapper,
                answerDetailMapper, surveyVersionMapper, surveyCollaboratorMapper,
                surveyIpMapper, surveyReviewMapper);
    }
}
