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
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   SurveyEdit.vue         → 编辑问卷时保存题目、回显题目
 *   SurveyFillByCode.vue   → 匿名答题页加载题目
 *   SurveyStatistics.vue   → 统计页按题目维度取数
 *
 * ── 权限（两个端点不一样，别搞混）──────────────────────
 *   POST /save/{surveyId} : 需登录 + 归属校验（只能改自己的问卷，管理员例外）
 *   GET  /list/{surveyId} : 半公开，在 JwtAuthFilter 白名单里 —— 这是功能需要，
 *                           匿名答题者必须能加载题目。但内部做了区分：
 *                           已发布(status=1) 任何人可读；草稿只有作者本人或管理员可读。
 *
 * ── 保存语义（改这里前务必理解）────────────────────────
 *   /save 是「全量覆盖」：前端把整个题目数组发过来，后端重排 sort_order、
 *   删掉本次没传的题。逻辑跳转规则（option.nextQuestionId，存**真 question.id**）也随之整体重写，
 *   并会校验"跳转目标必须属于本问卷"（目标指向已删题目时给 400，而不是静默失效）。
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
     * 编辑器用这个请求头声明"我是编辑器在读题目"。
     * ★ 只有带它、**且**请求者是作者本人或管理员时，才免答题密码（见
     *   {@code SurveyService.requireAccessPasswordForEditor}）。
     *   填写页永远不带它 —— 所以作者自己打开分享链接时，同样必须输对密码。
     */
    private static final String EDITOR_HEADER = "1";

    @Operation(summary = "保存问卷的题目（全量覆盖）")
    @PostMapping("/save/{surveyId}")
    public List<QuestionIdItem> saveQuestions(@PathVariable Integer surveyId,
                                              @RequestBody List<Question> questions,
                                              @RequestHeader("X-User-Id") Integer userId,
                                              @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) throw new BizException("问卷不存在");
        // ★ H2：能力（survey:edit）× 对象（作者/协作者/管理员）。editor 协作者可以改题，
        //   viewer/analyst 不行 —— 条件跳转规则的编辑也走这条闸门。
        surveyAccessService.requirePermission(survey, userId, role, Permission.SURVEY_EDIT, "修改");
        // ★ 回收站里的问卷不能改题目。
        //   这个接口是**全量覆盖**语义（没传的旧题会被删掉），在回收站问卷上执行
        //   会让历史答卷的 question_id 悬空 —— 后果比改个标题严重得多。
        OwnershipGuard.requireNotInRecycleBin(survey, "编辑题目");
        List<QuestionIdItem> saved = questionService.saveQuestions(surveyId, questions, userId);
        // ★ 题目保存在 t_question / t_option 上，t_survey 一行都没动，
        //   不显式推一下的话编辑器的"已保存 · X 分钟前"会一直停在旧时间。
        surveyService.touchUpdatedTime(surveyId);
        return saved;
    }

    /**
     * 查询问卷的所有题目。
     * 这个接口在 JwtAuthFilter 的白名单里 —— 这是功能需要：匿名答题者必须能加载题目。
     * 所以不能简单地要求登录，但必须区分两种情况：
     *   - 已发布的问卷：任何人都可以读（等同于公开内容，分享链接答题就靠这个）
     *   - 未发布的问卷（草稿）：只有作者本人或管理员能读，否则别人的草稿会被随便翻
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

        // ★ 协作者（任何角色）也能读题目：否则被邀请来看的人连问卷都打不开。
        surveyAccessService.requireVisible(survey, userId, role, "问卷尚未发布，无权查看");

        // ★ 答题密码：没设就直通；设了就必须带对请求头。
        //   放在 requireVisible 之后：先判"能不能看"，再判"要不要密码"，
        //   否则草稿问卷会先被问密码、反而泄露"存在这样一份问卷"。
        // ★ 两条口径，别合并：
        //   · 编辑器（带 X-Survey-Editor）→ 作者/管理员免口令，否则作者一设密码就把自己锁死；
        //   · 填写页 → 一律比口令（含作者自己打开分享链接），否则"能不能读题"就不再等于
        //     "口令对不对"，密码门会形同虚设。
        if (EDITOR_HEADER.equals(editor)) {
            // ★ 编辑器视角：作者 / 管理员 / **协作者（任何角色）** 都不需要答题口令 ——
            //   答题密码是给"填写人"的准入门槛，不是给协作成员的。
            //   少了协作者这一条，被邀请的人一进编辑器就吃 400、题目加载失败（实测踩到）。
            if (!surveyAccessService.isCollaborator(survey, userId)) {
                surveyService.requireAccessPasswordForEditor(survey, userId, role, password);
            }
        } else {
            surveyService.requireAccessPassword(survey, password);
        }

        return questionService.getQuestionsBySurveyId(surveyId);
    }
}