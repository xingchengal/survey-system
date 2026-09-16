package com.survey.controller;

import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.service.QuestionService;
import com.survey.service.SurveyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "题目管理", description = "问卷题目的保存与查询")
@RestController
@RequestMapping("/api/question")
public class QuestionController {

    @Autowired
    private QuestionService questionService;

    @Autowired
    private SurveyService surveyService;

    @Operation(summary = "保存问卷的题目（全量覆盖）")
    @PostMapping("/save/{surveyId}")
    public String saveQuestions(@PathVariable Integer surveyId,
                                @RequestBody List<Question> questions,
                                @RequestHeader("X-User-Id") Integer userId,
                                @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) return "问卷不存在";
        if (!"admin".equals(role) && !survey.getUserId().equals(userId)) {
            return "无权限修改他人问卷";
        }
        questionService.saveQuestions(surveyId, questions);
        return "保存成功";
    }

    @Operation(summary = "查询问卷的所有题目")
    @GetMapping("/list/{surveyId}")
    public List<Question> getQuestions(@PathVariable Integer surveyId) {
        return questionService.getQuestionsBySurveyId(surveyId);
    }
}