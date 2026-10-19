package com.survey.controller;

import com.survey.service.CaptchaService;
import com.survey.util.WebUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 滑块验证码：签发一次性凭证 + 底图/缺口，公开端点（仅注册页使用）。
 * ★ /slider 与 /verify 在 JwtAuthFilter 白名单；限流按成本拆开：取图 20 次/分钟/IP、判定 120 次/分钟/IP，
 *   合并成一条会让判定吃光取图额度，正常用户点开注册页就被 429。
 * 凭证 60 秒过期、一次性，连续失败 3 次即作废；verify 只标记不消费（消费在注册时）。
 */
@Tag(name = "滑块验证码", description = "注册页滑块验证（本地生成，零依赖）")
@RestController
@RequestMapping("/api/captcha")
public class CaptchaController {

    @Autowired
    private CaptchaService captchaService;

    /**
     * 取一张新的滑块验证码。
     * @return bgImage / sliderImage（data URL）、canvasWidth、canvasHeight、blockSize、
     *         offset（缺口位置）、tolerance、token、expiresIn（秒）
     */
    @Operation(summary = "获取滑块验证码（公开）")
    @GetMapping("/slider")
    public Map<String, Object> slider(
            @RequestParam(value = "w", required = false) Integer w,
            @RequestParam(value = "l", required = false) Integer l) {
        // w / l 是前端组件的实际宽与缺口边长：后端必须用同一个 L 生成 offset，否则组件越界直接不画、
        // 判定也会"拖对了却报未通过"；传空/超范围按组件默认值 310/42 处理，老前端不会 400。
        return captchaService.createChallenge(w, l);
    }

    /**
     * 判定一次拖动是否对准缺口（公开，只标记不消费）；HTTP 200 + passed=false 是正常业务结果。
     * @param token 凭证（来自 /slider）
     * @param left  组件 success 回调的 left，收 String 以兜住空值被序列化成 "NaN" 的情况
     * @return { passed, expectedLeft, actualLeft, diff, fails?, maxFails?, expired? }
     */
    @Operation(summary = "校验滑块拖动结果（公开，只标记不消费）")
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestParam("token") String token,
                                      @RequestParam(value = "left", required = false) String left,
                                      HttpServletRequest request) {
        // left 收 String：组件回调可能给出 NaN，显式解析后回业务文案（见 CaptchaService.parseLeft）。
        return captchaService.verify(token, left, null, WebUtils.getClientIp(request));
    }
}
