package com.survey.controller;

import com.survey.common.BizException;
import com.survey.common.Permission;
import com.survey.entity.Survey;
import com.survey.service.SurveyAccessService;
import com.survey.service.SurveyService;
import com.survey.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 上传：目前只有「选项配图」一种。
 *
 * ★ 必须带 surveyId 并校验问卷编辑权：只验登录会让接口变成免费图床（任何人可无限传图占磁盘）。
 * ★ 返回相对路径 /uploads/xxx.webp，前端写进选项的 imageUrl；图片本身由静态资源映射提供。
 */
@Tag(name = "上传", description = "选项配图")
@RestController
public class UploadController {

    @Autowired
    private UploadService uploadService;

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private SurveyAccessService surveyAccessService;

    @Operation(summary = "上传选项配图（作者 / 可编辑协作者）")
    @PostMapping("/api/upload/option-image")
    public Map<String, String> uploadOptionImage(@RequestParam("surveyId") Integer surveyId,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestHeader("X-User-Id") Integer userId,
                                                 @RequestHeader("X-User-Role") String role) {
        Survey survey = surveyService.getById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        // 能力 × 对象：editor 协作者可以改题，也就可以换配图；viewer / analyst 不行。
        surveyAccessService.requirePermission(survey, userId, role, Permission.SURVEY_EDIT, "上传图片");
        surveyAccessService.requireNotSuspended(survey, role, "上传图片");
        return Map.of("url", uploadService.saveOptionImage(userId, file));
    }
}
