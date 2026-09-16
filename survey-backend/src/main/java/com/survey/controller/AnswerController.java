package com.survey.controller;

import com.alibaba.excel.EasyExcel;
import com.survey.entity.Answer;
import com.survey.entity.AnswerDetail;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.service.AnswerService;
import com.survey.service.QuestionService;
import com.survey.service.SurveyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;

@Tag(name = "答卷管理", description = "答卷提交、查询、统计、导出 Excel")
@RestController
@RequestMapping("/api/answer")
public class AnswerController {

    @Autowired
    private AnswerService answerService;

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private QuestionService questionService;

    @Operation(summary = "提交答卷（登录用户）")
    @PostMapping("/submit/{surveyId}")
    public String submit(@PathVariable Integer surveyId,
                         @RequestBody List<AnswerDetail> details,
                         @RequestHeader(value = "X-User-Id", required = false) Integer userId) {
        answerService.submit(surveyId, userId, details);
        return "提交成功";
    }

    @Operation(summary = "提交答卷（匿名访客，通过分享码）")
    @PostMapping("/submit-share/{shareCode}")
    public String submitByShareCode(@PathVariable String shareCode,
                                    @RequestBody List<AnswerDetail> details) {
        Survey survey = surveyService.getByShareCode(shareCode);
        if (survey == null) return "问卷不存在";
        if (survey.getStatus() == null || survey.getStatus() != 1) return "问卷未发布";
        answerService.submit(survey.getId(), null, details);
        return "提交成功";
    }

    @Operation(summary = "查询某问卷的答卷数量")
    @GetMapping("/count/{surveyId}")
    public int count(@PathVariable Integer surveyId) {
        return answerService.countResponses(surveyId);
    }

    @Operation(summary = "选择题选项分布统计")
    @GetMapping("/statistics/{questionId}")
    public List<Map<String, Object>> statistics(@PathVariable Integer questionId) {
        return answerService.countQuestion(questionId);
    }

    @Operation(summary = "填空题的文字回答列表")
    @GetMapping("/texts/{questionId}")
    public List<String> texts(@PathVariable Integer questionId) {
        return answerService.listTextAnswers(questionId);
    }

    @Operation(summary = "查询某问卷的所有答卷")
    @GetMapping("/list/{surveyId}")
    public List<Answer> listAnswers(@PathVariable Integer surveyId) {
        return answerService.listAnswersBySurveyId(surveyId);
    }

    @Operation(summary = "查询某份答卷的详细内容")
    @GetMapping("/detail/{answerId}")
    public List<AnswerDetail> detail(@PathVariable Integer answerId) {
        return answerService.listDetailsByAnswerId(answerId);
    }

    @Operation(summary = "导出某问卷的答卷为 Excel")
    @GetMapping("/export/{surveyId}")
    public void export(@PathVariable Integer surveyId,
                       HttpServletResponse response) throws IOException {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) return;

        List<Question> questions = questionService.getQuestionsBySurveyId(surveyId);
        List<AnswerDetail> allDetails = answerService.listDetailsBySurveyId(surveyId);

        Map<Integer, Map<Integer, String>> answerMap = new LinkedHashMap<>();
        for (AnswerDetail d : allDetails) {
            answerMap.computeIfAbsent(d.getAnswerId(), k -> new HashMap<>())
                    .put(d.getQuestionId(), d.getContent());
        }

        List<List<String>> head = new ArrayList<>();
        head.add(Collections.singletonList("答卷ID"));
        for (Question q : questions) {
            head.add(Collections.singletonList(q.getTitle()));
        }

        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, Map<Integer, String>> entry : answerMap.entrySet()) {
            List<Object> row = new ArrayList<>();
            row.add(entry.getKey());
            for (Question q : questions) {
                String content = entry.getValue().getOrDefault(q.getId(), "");
                row.add(content);
            }
            rows.add(row);
        }

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode(survey.getTitle() + "_答卷数据", "UTF-8");
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName + ".xlsx");

        EasyExcel.write(response.getOutputStream())
                .head(head)
                .sheet("答卷数据")
                .doWrite(rows);
    }
}