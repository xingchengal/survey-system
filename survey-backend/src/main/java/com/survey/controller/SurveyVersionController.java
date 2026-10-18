package com.survey.controller;

import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.common.OwnershipGuard;
import com.survey.dto.SurveyVersionDiff;
import com.survey.dto.SurveyVersionSummary;
import com.survey.entity.Survey;
import com.survey.entity.SurveyVersion;
import com.survey.service.QuestionService;
import com.survey.service.SurveyAccessService;
import com.survey.service.SurveyService;
import com.survey.service.SurveyVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 问卷版本：编辑历史、版本对比、回滚。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   SurveyEdit.vue → "历史版本"按钮（列表 + 回滚 + 与当前版本对比）
 *
 * ── 权限（4 个端点全部"需登录 + 归属"，与其它答卷/统计端点同一口径）──
 *   版本快照里是**问卷题目结构的全文**，草稿的题目还没公开 —— 所以不能只校验"已登录"，
 *   否则任何登录用户改一下 URL 里的 id 就能读到别人草稿的题目结构。
 *   管理员放行（与 AnswerController.requireSurveyOwnership 一致）。
 *
 * ── 路由为什么不会和 SurveyController 的 /{id} 打架 ──────
 *   `/api/survey/version/...` 至少三段，而 `SurveyController` 的 `GET /{id}` 只匹配两段
 *   （`/api/survey/{id}`）。Spring 按段数匹配，不会把 "version" 当成问卷 id。
 *   ★ 但 `/api/survey/version` 本身（两段）确实会被 `GET /{id}` 接住 → 走"问卷不存在/已下线"的 400，
 *     这也是为什么下面的列表端点一定带 `/list/`。
 *
 * ── 回滚的语义（★ 别按"撤销"理解）─────────────────────
 *   回滚不是"删掉后面几版"，而是**把旧结构写回去并生成一个新版本**：
 *   历史是线性的、可审计的（v5 → 回滚到 v2 → 产生 v6，v6 的内容等于 v2）。
 *   因此回滚之后仍然可以"回滚到 v5"把刚才的操作撤回来。
 */
@Tag(name = "问卷版本", description = "编辑历史、版本对比、回滚")
@RestController
@RequestMapping("/api/survey/version")
public class SurveyVersionController {

    @Autowired
    private SurveyVersionService versionService;

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private QuestionService questionService;

    @Autowired
    private SurveyAccessService surveyAccessService;

    @Operation(summary = "某问卷的版本列表（新的在前，不含题目快照）")
    @GetMapping("/list/{surveyId}")
    public List<SurveyVersionSummary> list(@PathVariable Integer surveyId,
                                           @RequestHeader("X-User-Id") Integer userId,
                                           @RequestHeader("X-User-Role") String role) {
        requireOwnership(surveyId, userId, role);
        return versionService.listVersions(surveyId);
    }

    @Operation(summary = "查看某个版本（含题目快照 JSON）")
    @GetMapping("/{versionId}")
    public SurveyVersion detail(@PathVariable Integer versionId,
                                @RequestHeader("X-User-Id") Integer userId,
                                @RequestHeader("X-User-Role") String role) {
        SurveyVersion version = versionService.requireById(versionId);
        requireOwnership(version.getSurveyId(), userId, role);
        return version;
    }

    @Operation(summary = "对比两个版本（结构化差异）")
    @GetMapping("/diff/{fromId}/{toId}")
    public SurveyVersionDiff diff(@PathVariable Integer fromId,
                                  @PathVariable Integer toId,
                                  @RequestHeader("X-User-Id") Integer userId,
                                  @RequestHeader("X-User-Role") String role) {
        SurveyVersion from = versionService.requireById(fromId);
        // 归属只看 from 所在的问卷；"两个版本必须属于同一份问卷"由 service.diff 再校验一次
        requireOwnership(from.getSurveyId(), userId, role);
        return versionService.diff(fromId, toId);
    }

    @Operation(summary = "回滚到某个版本（会生成一个新版本，历史不丢）")
    @PostMapping("/rollback/{versionId}")
    public SurveyVersion rollback(@PathVariable Integer versionId,
                                  @RequestHeader("X-User-Id") Integer userId,
                                  @RequestHeader("X-User-Role") String role) {
        SurveyVersion version = versionService.requireById(versionId);
        Survey survey = requireOwnership(version.getSurveyId(), userId, role);
        // ★ 回收站里的问卷不能回滚：回滚走的是"全量覆盖式保存"，会让历史答卷的 question_id 悬空。
        OwnershipGuard.requireNotInRecycleBin(survey, "回滚版本");
        // ★ 已下架问卷同样不能回滚（回滚会改题目，等于绕开"下架期间内容冻结"）
        surveyAccessService.requireNotSuspended(survey, role, "回滚版本");
        SurveyVersion created = questionService.rollbackToVersion(version, userId);
        // ★ 回滚改的是题目（t_question/t_option），同样不会动 t_survey 的时间戳
        surveyService.touchUpdatedTime(version.getSurveyId());
        return created;
    }

    /**
     * 归属口径与其它答卷/统计端点完全一致：作者本人或管理员。
     * ★ 手写 if 是不允许的（《交接文档》约定 6：归属校验一律走 OwnershipGuard）。
     */
    private Survey requireOwnership(Integer surveyId, Integer userId, String role) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        // ★ 版本历史是**编辑辅助**（对比/回滚都会改内容），按 survey:edit 判；
        //   文案保持 "查看版本历史" 不变（矩阵测试按它断言）。
        surveyAccessService.requirePermission(survey, userId, role, Permission.SURVEY_EDIT, "查看版本历史");
        return survey;
    }
}
