package com.survey.service;

import com.survey.entity.Answer;
import com.survey.entity.AnswerDetail;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.SurveyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AnswerService {

    @Autowired
    private AnswerMapper answerMapper;
    @Autowired
    private AnswerDetailMapper answerDetailMapper;
    @Autowired
    private SurveyMapper surveyMapper;
    @Autowired
    private NotificationService notificationService;

    /**
     * 提交答卷
     */
    @Transactional
    public void submit(Integer surveyId, Integer userId, List<AnswerDetail> details) {
        Answer answer = new Answer();
        answer.setSurveyId(surveyId);
        answer.setUserId(userId);
        answerMapper.insert(answer);

        for (AnswerDetail d : details) {
            d.setAnswerId(answer.getId());
            answerDetailMapper.insert(d);
        }

        // ★ 给问卷创建者发通知
        Survey survey = surveyMapper.findById(surveyId);
        if (survey != null && survey.getUserId() != null && survey.getUserId() != 0) {
            String title = "收到新答卷";
            String content = "你的问卷《" + survey.getTitle() + "》收到了一份新的答卷";
            String link = "/my/surveys/" + surveyId + "/statistics";
            notificationService.send(survey.getUserId(), title, content, "answer", link);
        }
    }

    public int countResponses(Integer surveyId) {
        return answerMapper.countBySurveyId(surveyId);
    }

    public List<Map<String, Object>> countQuestion(Integer questionId) {
        return answerDetailMapper.countByQuestionId(questionId);
    }

    public List<String> listTextAnswers(Integer questionId) {
        return answerDetailMapper.findTextAnswers(questionId);
    }

    public List<AnswerDetail> listDetailsBySurveyId(Integer surveyId) {
        return answerDetailMapper.findBySurveyId(surveyId);
    }

    public List<Answer> listAnswersBySurveyId(Integer surveyId) {
        return answerMapper.findBySurveyId(surveyId);
    }

    public List<AnswerDetail> listDetailsByAnswerId(Integer answerId) {
        return answerDetailMapper.findByAnswerId(answerId);
    }
}