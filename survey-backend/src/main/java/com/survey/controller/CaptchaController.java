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
 * 滑块验证码：签发一次性凭证 + 底图/缺口位置（**仅注册页使用**）。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   Register.vue → 页面加载/刷新/验证失败时调 /api/captcha/slider 取图；
 *   用户拖动结束后调 POST /api/captcha/verify 上报 {token, left}，由后端**判定并标记**凭证；
 *   注册时**只回传 captchaToken**（不含 left），UserController.register 只认那个标记并消费凭证。
 *
 * ── 权限 ──────────────────────────────────────────────
 *   **公开**：它在 JwtAuthFilter 白名单里 —— 注册页本身就没有 token，
 *   而且这是"取一张图"的只读操作，不泄露任何用户数据。
 *   ★ 代价与对策：公开接口可被刷。已做两层防护：
 *     ① RateLimitInterceptor **按成本拆成两条**：/slider 取图 **20 次/分钟/IP**（要画 PNG，CPU 密集）、
 *        /verify 判定 **120 次/分钟/IP**（只做一次哈希查表，极轻）。
 *        ★ 这两条**不要合并成一条前缀规则** —— 合并后两者共用一个额度，判定（120 次/分钟）
 *          很快把取图的额度吃光，正常用户点开注册页就被 429 挡住。
 *     ② 凭证 60 秒过期 + 一次性 + 连续失败 3 次作废（见 CaptchaService）；
 *        刷图并不能提高猜中概率 —— 每次刷出来的 offset 都是新的随机值。
 */
@Tag(name = "滑块验证码", description = "注册页滑块验证（本地生成，零依赖）")
@RestController
@RequestMapping("/api/captcha")
public class CaptchaController {

    @Autowired
    private CaptchaService captchaService;

    /**
     * 取一张新的滑块验证码。
     *
     * @return bgImage / sliderImage（data URL，可直接喂给 <img src>）、
     *         canvasWidth、canvasHeight、blockSize、**offset（缺口位置，交给组件 offset 属性）**、
     *         tolerance、token（提交注册时回传）、expiresIn（秒）
     */
    @Operation(summary = "获取滑块验证码（公开）")
    @GetMapping("/slider")
    public Map<String, Object> slider(
            @RequestParam(value = "w", required = false) Integer w,
            @RequestParam(value = "l", required = false) Integer l) {
        // ★ w / l 是"前端组件实际用的宽与缺口边长"（见 vue3-slide-verify 的 props）。
        //   为什么要传上来：组件对方块位置有硬校验区间 [L+10, w−(L+10)]（L = l + 2r + 3），
        //   后端必须用**同一个 L** 生成 offset（否则越界时组件直接 throw、缺口不画），
        //   而组件的 left ≈ offset（比例 1），判定也要在同一口径下做，
        //   否则会出现"用户拖对了却报未通过"。
        //   传空/超范围时后端按组件默认值（310/42）处理，所以老前端不会因此 400。
        return captchaService.createChallenge(w, l);
    }

    /**
     * 判定一次拖动是否对准缺口（公开）。
     *
     * ★ 为什么单独一个端点，而不是"注册时一并判定"：
     *   需求两条要求叠加在一起 —— ① "滑块校验成功后，修改账号密码，保留校验状态无需重复拖拽"；
     *   ② "滑块未验证通过，提交按钮置灰"。
     *   如果只能"注册时才判定"，那么前端**无法知道该不该点亮按钮**（它自己判的坐标系和后端不同），
     *   于是"看起来过了、点注册却被拒"。所以判定必须**前置**成一次独立调用。
     *
     * ★ 判定与消费分离：本端点只**标记** verified（不消费凭证），
     *   真正的消费在注册成功那一步入库之前（见 UserController.register → consumeVerifiedOrThrow）。
     *   这样"验证通过后改密码/改手机号"不会让用户重拖，而凭证仍然是一次性的。
     *
     * @param token 凭证（来自 /slider）
     * @param left  组件 success 回调返回的 left（成功时 ≈ 后端下发的 offset，比例 1）
     * @return { passed, expectedLeft, actualLeft, diff, fails?, maxFails?, expired? }
     *         ★ 这是唯一一个"HTTP 200 但结果是 false"的接口 —— 因为"没对准"是**正常业务结果**
     *           （不是错误），前端要据此展示"再试一次"，而不是弹一条红色错误。
     */
    @Operation(summary = "校验滑块拖动结果（公开，只标记不消费）")
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestParam("token") String token,
                                      @RequestParam(value = "left", required = false) String left,
                                      HttpServletRequest request) {
        // ★ 为什么 left 收 String 而不是 Integer（收 Integer 就会掉进下面这个坑）：
        //   vue3-slide-verify 的 success 回调给的是 parseFloat(block.style.left)，
        //   在某些渲染时序下 style.left 为空 → parseFloat("") = **NaN** →
        //   前端把它序列化成字符串 **"NaN"** 发过来 → Spring 解析 Integer 失败，
        //   返回的是「参数「left」格式不正确」这种**底层类型错误**，
        //   排查者拿到它完全看不出"是滑块回调给了 NaN"。
        //   所以这里显式解析：NaN/空/非数字 → 前端能看懂的业务文案（见 CaptchaService.parseLeft）。
        return captchaService.verify(token, left, null, WebUtils.getClientIp(request));
    }
}
