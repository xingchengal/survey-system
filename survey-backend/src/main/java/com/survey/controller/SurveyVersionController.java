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
 * 4 个端点全部"需登录 + 归属"：快照是草稿全文，只验登录会让任何用户改 id 读到别人草稿。
 * 回滚不是"撤销"：把旧结构写回去并生成新版本（v5 → 回滚 v2 → 产生 v6），历史线性可审计。
 * /api/survey/version/... 至少三段，不会被 SurveyController 的 GET /{id}（两段）抢走。
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
        // 归属只看 from 所在问卷；"两个版本必须属于同一份问卷"由 service.diff 再校验一次。
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
        // 回收站里的问卷不能回滚：回滚是"全量覆盖式保存"，会让历史答卷的 question_id 悬空。
        // 已下架问卷同理，否则绕过"下架期间内容冻结"。
        OwnershipGuard.requireNotInRecycleBin(survey, "回滚版本");
        surveyAccessService.requireNotSuspended(survey, role, "回滚版本");
        SurveyVersion created = questionService.rollbackToVersion(version, userId);
        // 回滚改的是题目子表，t_survey 时间戳要手动推。
        surveyService.touchUpdatedTime(version.getSurveyId());
        return created;
    }

    /**
     * 归属口径：作者本人或管理员，统一走 SurveyAccessService.requirePermission（版本历史按 survey:edit 判）。
     */
    private Survey requireOwnership(Integer surveyId, Integer userId, String role) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        // 版本历史是编辑辅助（对比/回滚都会改内容），按 survey:edit 判。
        surveyAccessService.requirePermission(survey, userId, role, Permission.SURVEY_EDIT, "查看版本历史");
        return survey;
    }
}
