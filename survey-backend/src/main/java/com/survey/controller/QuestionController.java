package com.survey.controller;

import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.dto.QuestionIdItem;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.service.QuestionService;
import com.survey.service.SurveyAccessService;
import com.survey.service.SurveyService;
import com.survey.common.OwnershipGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 题目管理：问卷题目的保存与查询。
 * /save/{surveyId}：需登录 + 归属校验（作者/协作者/管理员），回收站/已下架问卷冻结。
 * /list/{surveyId}：在 JwtAuthFilter 白名单里 —— 匿名答题者必须能加载题目；
 * 内部再区分：已发布任何人可读，草稿只有作者/管理员/协作者可读。
 * 保存是全量覆盖：重排 sort_order、删除本次未传的题，跳转规则整体重写。
 */
@Tag(name = "题目管理", description = "问卷题目的保存与查询")
@RestController
@RequestMapping("/api/question")
public class QuestionController {

    @Autowired
    private QuestionService questionService;

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private SurveyAccessService surveyAccessService;

    /**
     * 编辑器声明"我在编辑器读题目"的请求头值：只有带它且是作者/管理员/协作者时才免答题密码。
     * 填写页永远不带，所以作者自己打开分享链接同样要输密码。
     */
    private static final String EDITOR_HEADER = "1";

    /**
     * 保存问卷的题目（全量覆盖）：返回与入参按下标对应的 [{id, sortOrder}]，前端据此回填新题 id。
     * 跳转目标必须属于本问卷，校验在 Service —— 目标指向已删题目时 400，而不是静默失效。
     */
    @Operation(summary = "保存问卷的题目（全量覆盖）")
    @PostMapping("/save/{surveyId}")
    public List<QuestionIdItem> saveQuestions(@PathVariable Integer surveyId,
                                              @RequestBody List<Question> questions,
                                              @RequestHeader("X-User-Id") Integer userId,
                                              @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) throw new BizException("问卷不存在");
        // 能力 × 对象：editor 协作者可以改题，viewer/analyst 不行；条件跳转规则的编辑也走这条闸门。
        surveyAccessService.requirePermission(survey, userId, role, Permission.SURVEY_EDIT, "修改");
        // 已下架的问卷冻结内容：全量覆盖语义，下架期间改题会让审核依据失效。
        surveyAccessService.requireNotSuspended(survey, role, "编辑题目");
        // 回收站里的问卷不能改题：全量覆盖会删旧题，让历史答卷的 question_id 悬空。
        OwnershipGuard.requireNotInRecycleBin(survey, "编辑题目");
        List<QuestionIdItem> saved = questionService.saveQuestions(surveyId, questions, userId);
        // 题目存在 t_question/t_option，t_survey 没动，不推时间戳的话编辑器"已保存"会停在旧时间。
        surveyService.touchUpdatedTime(surveyId);
        return saved;
    }

    /**
     * 查询问卷的所有题目：白名单接口，匿名答题者必须能加载。
     * 已发布任何人可读；草稿只有作者/管理员/协作者可读，否则别人的草稿会被随便翻。
     */
    @Operation(summary = "查询问卷的所有题目")
    @GetMapping("/list/{surveyId}")
    public List<Question> getQuestions(@PathVariable Integer surveyId,
                                       @RequestHeader(value = "X-User-Id", required = false) Integer userId,
                                       @RequestHeader(value = "X-User-Role", required = false) String role,
                                       @RequestHeader(value = "X-Survey-Password", required = false) String password,
                                       @RequestHeader(value = "X-Survey-Editor", required = false) String editor) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }

        // 协作者（任何角色）也能读题目，否则被邀请的人连问卷都打不开。
        surveyAccessService.requireVisible(survey, userId, role, "问卷尚未发布，无权查看");

        // 先判"能不能看"再判"要不要密码"，否则草稿会先被问密码、反而泄露问卷存在。
        // 两条口径别合并：编辑器 → 作者/管理员/协作者免口令；填写页 → 一律比口令（含作者自己）。
        if (EDITOR_HEADER.equals(editor)) {
            // 协作者任何角色都免答题口令：答题密码是给填写人的门槛，不是给协作成员的。
            if (!surveyAccessService.isCollaborator(survey, userId)) {
                surveyService.requireAccessPasswordForEditor(survey, userId, role, password);
            }
        } else {
            surveyService.requireAccessPassword(survey, password);
        }

        return questionService.getQuestionsBySurveyId(surveyId);
    }
}