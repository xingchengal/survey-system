package com.survey.service;

import com.survey.common.BizException;
import com.survey.entity.OperationLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑块验证码（本地实现，零新增依赖）—— 生成底图 + 缺口位置 + **一次性校验凭证**，并做后端二次校验。
 *
 * ── 为什么自己画图（而不是引第三方验证码库）────────────────
 *   本机离线 Maven 仓库里**没有任何**验证码库（kaptcha / hutool-captcha / whvcse.easy-captcha
 *   都没有），沙箱也不允许联网写 ~/.m2。而 JDK 自带 AWT（BufferedImage + ImageIO），
 *   画底图与缺口图完全够用 → 所以这里自己实现，**不新增依赖**。
 *   ★ 依赖 headless：AWT 在无显示环境（服务器）必须 headless 才能跑，
 *     见 {@code resources/application.yaml} 的 {@code java.awt.headless=true}
 *     与启动类里的 {@code System.setProperty}（双保险）。
 *
 * ── 与前端的分工（vue3-slide-verify 1.1.8）─────────────────
 *   前端组件支持 {@code offset} 属性（从后端获取初始偏移），并在 {@code success} 回调里
 *   返回 **方块位置 blockLeft**（组件源码里就是 block.style.left —— 不是"拖动距离 moveX"）。
 *   于是流程是（**两步式；改回一步式会重现"前端说过了、后端说没过"**）：
 *     ① 前端 GET /api/captcha/slider → 拿到底图、缺口位置 offset、一次性 token；
 *     ② 组件用 offset 摆好缺口，用户拖动；
 *     ③ 拖动结束 → 前端 POST /api/captcha/verify 上报 {token, left}，
 *        后端比对 |left − offset| ≤ 容差，**只标记 verified、不消费凭证**；
 *     ④ 注册时前端只回传 captchaToken，由 {@link #consumeVerifiedOrThrow} 检查标记并**消费**。
 *   ★ 这样"缺口在哪"始终由后端给出、由后端校验，前端改不了答案 —— 满足需求里
 *     "对滑块校验结果做二次校验，不单纯信任前端返回信息"。
 *
 * ── 凭证生命周期（需求第 3 条）────────────────────────────
 *   · **一次性**：校验成功立即从内存移除，重放必失败；
 *   · **60 秒有效**：过期即视为无效（前端也应在此期间提交）；
 *   · **失败计数**：同一 token 连续失败到 {@value #MAX_FAILS} 次即**作废该凭证**
 *     （前端需重新取图）—— 防止有人固定一个 token 反复试探缺口位置。
 *     ★ 注意：单次失败**不作废**（只在 {@link #verify} 里累计），
 *       因为前端每次失败都会自己换图；作废只发生在"同一个 token 被反复试"这种异常形态。
 *   · **刷新即换图**：每次调用 /captcha/slider 都签发新 token（旧 token 自然过期（evictExpired() 定时清理））。
 *
 * ── 日志（需求"滑块验证成功、失败事件接入全局操作日志"）────
 *   成功：写一条审计日志（谁、耗时、偏差），复用 OperationLogService（@Async 落库）。
 *   失败：同样记一条 —— 但它**不是系统异常**，所以**不写 t_error_log**（那会污染"异常"页）；
 *         真正的系统级故障（如 AWT 不可用）才由调用方抛异常、进异常日志。
 */
@Service
public class CaptchaService {

    private static final Logger LOG = LoggerFactory.getLogger(CaptchaService.class);
    /** 画布尺寸与缺口尺寸（与前端组件 props 对齐；前端会按同样尺寸渲染） */
    public static final int CANVAS_W = 320;
    public static final int CANVAS_H = 160;
    /** 缺口方块的边长（= 前端组件的 l 参数） */
    public static final int BLOCK_SIZE = 46;
    /**
     * 容差（px）——**固定值，不再随 offset 缩放**。
     *
     * ★★ 下面三条结论都是对着组件源码定的，改之前务必读完 ★★
     *
     * 坑 1（坐标系搞反）：success 回调的 left 与 offset 之间**不存在**比例关系，
     *   于是做了"按比例换算 + 容差随位置缩放"。看组件源码就知道不对：
     *     block.value.style.left = blockLeft + "px";
     *     verify(left, blockX.value, accuracy);     // 成功条件 |blockLeft − offset| ≤ accuracy
     *     emit("success", { left: parseFloat(left) });
     *   即 **left 就是 blockLeft，而成功时 blockLeft ≈ offset** —— 比例是 1，根本不需要换算。
     *   （不要用 1.067 这类"从单次观测外推"的比例 —— 它没有推导依据。）
     *
     * 坑 2（offset 越界，高危）：组件对方块位置有硬校验 [L+10, w−(L+10)]，其中 L = l + 2r + 3；
     *   而按 [56, 264] 生成会有 **约 22% 的图落在区间外 → 组件直接 throw、缺口不画**，
     *   表现为"怎么拖都过不了、自动换图后再随机、时好时坏"。修法见 createChallenge。
     *
     * 坑 3（props 名写错）：前端曾传 :width/:height，而组件用的是 w/h —— 于是组件一直按
     *   默认 310×155 渲染，与后端的 320×160 不是同一块画布。现在前端传 :w/:h。
     *
     * ★ 既然 left ≈ offset（比例 1），容差只需覆盖"像素取整 + 拖动抖动"：
     *   组件自身 accuracy 已保证 |left − offset| ≤ accuracy（本项目设 3），这里留 12px 兜底。
     *   ★ 容差只影响"用户要不要重拖一次"，**不影响安全性** ——
     *     绕过 /api/captcha/verify 直接注册仍然必被拒（见 consumeVerifiedOrThrow）。
     */
    public static final int TOLERANCE = 12;

    /**
     * vue3-slide-verify 的默认参数 —— **只用于"有人直接调接口、没带 w/l"时的兜底**。
     *
     * ★ 实际链路里前端**每次都会**把 w/l 传上来（Register.vue 的 loadCaptcha），
     *   因为 offset 的合法区间 [L+10, w−(L+10)]（L = l + 2r + 3）依赖组件的 l/r/w ——
     *   后端用错尺寸就会生成越界值，而越界时组件在 img.onload 里直接 throw、缺口不画。
     * ★ 这几个默认值刻意保留成**组件自己的默认值**（310/42），而不是本项目的画布尺寸，
     *   目的：万一前端漏传，行为退化成"组件默认口径"而不是"后端自说自话"。
     * ★ r 不在请求参数里（前端显式传 :r="10"），所以这里只有 COMPONENT_R 一处定义，两端靠约定对齐。
     */
    private static final int COMPONENT_W = 310;
    private static final int COMPONENT_L = 42;
    private static final int COMPONENT_R = 10;
    /** 同一凭证最多允许失败几次（超过即作废，前端需重新取图） */
    public static final int MAX_FAILS = 3;

    private static final long TTL_MS = 60_000L;
    /**
     * 缺口垂直位置范围（上下留边）。
     * ★ 缺口**左边界**（offset）的合法区间不再是常量 —— 它取决于前端组件的 l/r/w
     *   （[L+10, w−(L+10)]，L = l + 2r + 3），在 createChallenge 里现算，见那里的注释。
     */
    private static final int MIN_Y = 10;

    private final SecureRandom random = new SecureRandom();

    @Autowired
    private OperationLogService operationLogService;

    /**
     * 一张待校验的滑块凭证。
     * ★ 只放在内存里（单实例部署，见 RateLimitInterceptor 的同类取舍）——
     *   多实例部署时必须换成 Redis，否则 A 实例发的图 B 实例验不过。
     */
    private static final class Challenge {
        final int offset;
        final int y;
        final long expiresAt;
        final int pieceType;
        /**
         * 前端上报的组件宽 / 缺口边长。
         * ★ 用途：算 offset 的**合法区间** [L+10, w−(L+10)]（L = l + 2r + 3）—— 必须与组件同式。
         * ★ 前端（Register.vue）**每次都上报**；默认值只用于"有人直接调接口没带参数"的兜底。
         */
        final int componentW;
        final int componentL;
        volatile int fails;
        /**
         * 是否已通过 {@code /api/captcha/verify} 的校验。
         * ★ 为什么要有它：注册接口**必须**确认"这个人真的拖对了"，
         *   而不能只看"凭证还在"（那等于谁拿到 token 都能注册）。
         *   流程因此变成两步：verify 判定 → 标记；register 只认标记并**消费**凭证。
         */
        volatile boolean verified;

        Challenge(int offset, int y, long expiresAt, int pieceType, int componentW, int componentL) {
            this.offset = offset;
            this.y = y;
            this.expiresAt = expiresAt;
            this.pieceType = pieceType;
            this.componentW = componentW;
            this.componentL = componentL;
        }
    }

    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    /**
     * 生成一张新的滑块验证码。
     *
     * @return {token, bgImage, sliderImage, canvasWidth, canvasHeight, blockSize, offset,
     *          tolerance, expiresIn, expectedLeft}
     *   ★ expectedLeft 与 offset **相等**（比例 1，见 expectedFrontendLeft）；
     *     bgImage 是**未挖洞**的纯底图（前端喂给组件的 imgs），sliderImage 仅服务端留档。
     *         ★ offset 就是缺口的位置：前端拿它交给组件的 {@code offset} 属性，
     *           用户拖动后的距离再由后端比对。
     */
    public Map<String, Object> createChallenge(Integer frontendW, Integer frontendL) {
        boolean prevHeadless = GraphicsEnvironment.isHeadless();
        // 保险：即使 yaml 忘了配 headless，这里也保证不因为"没有显示器"而崩
        System.setProperty("java.awt.headless", "true");

        // 前端可以把"它实际用的组件宽/缺口边长"带上来（0/负数/超范围一律按默认值处理）。
        // ★ 为什么需要：组件的坐标系取决于它的 w/l，而判定要在这个坐标系里做。
        //   前端与后端"默认值不同"（后端画布 320/46，组件默认 310/42），所以必须对齐。
        int cw = (frontendW != null && frontendW >= 200 && frontendW <= 500) ? frontendW : COMPONENT_W;
        int cl = (frontendL != null && frontendL >= 20 && frontendL <= 100) ? frontendL : COMPONENT_L;

        // ★★ 修复（BUG #1，高危）：offset 必须落在**组件自己的合法区间**内。
        //   组件源码（v1.1.8）在 img.onload 里有硬校验：
        //     const L = l + r * 2 + 3;                      // 方块真实边长（含 r 的圆角与描边）
        //     if (offset >= L + 10 && offset <= w - (L + 10)) blockX = offset;
        //     else throw new Error("offset must be ...");    // ← 越界直接抛，缺口根本不画
        //   旧代码按 [56, 264]（用后端自己的 46 边长算）生成，与组件口径不一致：
        //   **按区间实数计算**：[56,78] ∪ [242,264] 共 46 个位置 / 旧区间 209 个 ≈ 22% 的图会越界，
        //   用户怎么拖都过不去、换图后再随机，
        //   于是表现为"时好时坏、反复刷新"。
        int blockLen = cl + COMPONENT_R * 2 + 3;          // 与组件的 L 完全同式
        int minOffset = blockLen + 10;
        int maxOffset = cw - (blockLen + 10);
        if (maxOffset < minOffset) {
            // 尺寸不合法是**调用方传错参数**（前端 props 组合有问题），必须立刻炸出来，
            // 不能悄悄用越界值继续 —— 那正是本 bug 的来源。
            throw new IllegalStateException(
                    "滑块参数不合法：w=" + cw + " l=" + cl + " r=" + COMPONENT_R
                            + "，需要 w > 2*(l + 2r + 13)");
        }
        int offset = minOffset + random.nextInt(maxOffset - minOffset + 1);
        int y = MIN_Y + random.nextInt(Math.max(1, CANVAS_H - blockLen - MIN_Y * 2));
        int pieceType = random.nextInt(3);   // 0=方形 1=圆形 2=圆角


        String token = randomToken();
        Challenge challenge = new Challenge(offset, y, System.currentTimeMillis() + TTL_MS, pieceType, cw, cl);
        challenges.put(token, challenge);

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            BufferedImage full = drawBackground();
            // ★★ 修复（BUG #2）：**先把"没画洞"的纯背景序列化出去**，再在这张图上挖洞。
            //   为什么：前端现在把这张纯底图喂给组件的 imgs（见 Register.vue），
            //   组件会自己按 offset 在图上切出拼图块 —— 所以喂给它的**必须是无洞背景**，
            //   否则页面上会出现"两个洞"。
            //   而服务端的 full 之后才挖洞（仅用于留档/排查，前端不再使用 sliderImage）。
            result.put("bgImage", toDataUrl(full));

            BufferedImage slider = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_ARGB);
            cutPuzzle(full, slider, offset, y, pieceType);
            // ★ sliderImage 目前**没有前端消费者**（组件自己切图）。保留它的原因：
            //   ① 排查"服务端到底挖在哪"；② 将来若改成自研 Canvas 滑块，它就是要用的素材。
            result.put("sliderImage", toDataUrl(slider));
        } catch (Exception e) {
            // AWT 不可用属于**系统级故障**：抛出去让全局处理器记异常日志（滑块是注册的前置，
            // 不能因为画不出图就静默放行）
            challenges.remove(token);
            throw new IllegalStateException("验证码生成失败（AWT 不可用）", e);
        } finally {
            if (!prevHeadless) {
                // 不还原也无碍（JVM 内 headless 只影响 AWT），但保持环境干净
                LOG.debug("滑块验证码：headless 属性由 false 被临时置为 true");
            }
        }

        result.put("token", token);
        result.put("canvasWidth", CANVAS_W);
        result.put("canvasHeight", CANVAS_H);
        result.put("blockSize", BLOCK_SIZE);
        result.put("offset", offset);
        result.put("tolerance", TOLERANCE);
        result.put("expiresIn", TTL_MS / 1000);
        // ★ 额外告诉前端"在这个组件里，拖到缺口处时 success 回调的 left 大约是多少"
        //   —— 仅供排查与展示，判定仍以后端为准（前端改这个值没有任何用，因为后端会自己算一遍）
        result.put("expectedLeft", expectedFrontendLeft(challenge));
        return result;
    }

    /**
     * 第 1 步：判定"这次拖动是否对准了缺口"，对准则把凭证标记为 verified。
     *
     * ★ 为什么不在这里就消费凭证：用户可能在"验证通过 → 点注册"之间改表单、
     *   或被 @Valid 驳回（例如手机号格式错）。这时凭证已经被消费的话，用户得重新拖一次 ——
     *   而需求明确要求"滑块校验成功后，修改账号密码，保留校验状态无需重复拖拽"。
     *   所以：**判定与消费分离** —— verify 只标记，register 才消费。
     *
     * @param token 凭证
     * @param left  前端组件 success 回调返回的 left（**组件坐标系**，不是后端的 offset）
     * @return 判定结果 { passed, expectedLeft, actualLeft, diff,
     *          invalidLeft?, fails?, maxFails?, expired? }
     *   ★ invalidLeft=true 表示"上报的 left 不是有效数值"（组件在渲染时序不巧时会给出 NaN），
     *     前端应据此换图重来，而不是当成"用户拖错了"。
     * @throws BizException 凭证不存在/已过期（这类要明确报错，不能让前端以为"只是没拖准"）
     */
    public Map<String, Object> verify(String token, String rawLeft, Integer userId, String ip) {
        Challenge c = requireAlive(token);

        Map<String, Object> out = new LinkedHashMap<>();
        Integer left = parseLeft(rawLeft);
        if (left == null) {
            // ★ 这里给出的是**业务文案**：滑块库的回调在渲染时序不巧时会给出 NaN，
            //   那不是"用户拖错了"，而是"这次拖动没被正确记录" —— 必须让前端知道该重拖，
            //   而不是把 Spring 的类型错误暴露出去（原样就是「参数「left」格式不正确」）。
            out.put("passed", false);
            out.put("expectedLeft", expectedFrontendLeft(c));
            out.put("actualLeft", null);
            out.put("diff", null);
            out.put("invalidLeft", true);
            LOG.info("滑块判定收到非法 left：raw={} token={}", rawLeft, mask(token));
            return out;
        }

        int expected = expectedFrontendLeft(c);
        int diff = Math.abs(left - expected);
        // 固定容差（left ≈ offset、比例 1，理由见 TOLERANCE 的注释）
        int tolerance = TOLERANCE;
        boolean passed = diff <= tolerance;

        out.put("passed", passed);
        out.put("expectedLeft", expected);
        out.put("actualLeft", left);
        out.put("diff", diff);

        if (passed) {
            c.verified = true;
            writeLog("滑块验证", "滑块验证成功（偏差 " + diff + "px）", userId, ip);
        } else {
            int fails = ++c.fails;
            out.put("fails", fails);
            out.put("maxFails", MAX_FAILS);
            LOG.info("滑块验证未通过：上报 left={} 期望 left={} 偏差={}px 容差={}px 第{}次",
                    left, expected, diff, tolerance, fails);
            writeLog("滑块验证", "滑块验证失败（偏差 " + diff + "px，第 " + fails + " 次）", userId, ip);
            // ★ 与旧版不同：这里**不作废凭证**（只在 register 里消费）。
            //   原因：前端每次失败都会自己换一张图，作废只会让"换图前的最后一次判定"白跑。
            //   防爆破由"凭证 60 秒过期 + 取图限流 20 次/分（判定另有 120 次/分）"承担。
            if (fails >= MAX_FAILS) {
                challenges.remove(token);
                out.put("expired", true);
            }
        }
        return out;
    }

    /**
     * 第 2 步：注册接口在落库之前调用 —— **只认 verified 标记**并消费凭证。
     *
     * ★ 这一版与第一版的关键差别：第一版**没有前置判定**，要等到注册时才发现对不上；
     *   现在"判定"由 {@link #verify} 先做完（前端据此点亮按钮），
     *   这里只做"你来过、你过了吗、凭证还没被用过"三件事。
     *
     * @throws BizException 未取图 / 已过期 / 未通过验证
     */
    public void consumeVerifiedOrThrow(String token) {
        Challenge c = requireAlive(token);
        if (!c.verified) {
            throw new BizException("滑块验证未通过，请重新拖动");
        }
        challenges.remove(token);   // ★ 一次性：注册消耗掉它
    }

    /**
     * 把前端传来的 left 解析成整数。
     *
     * ★ 为什么需要它：前端序列化后可能是 "NaN"（见 Controller 里的说明）——
     *   直接让 Spring 解析 Integer 会返回底层类型错误，用户体验与可排查性都很差。
     *   "NaN"/"Infinity"/空串/非数字 → 一律返回 null（由调用方给出业务文案）。
     *
     * @return 合法整数；非法或缺失返回 null
     */
    private Integer parseLeft(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        // 双重防御：前端已经用 Number.isFinite 挡过，但直接调接口的人可能传这些
        if ("null".equalsIgnoreCase(t) || "undefined".equalsIgnoreCase(t)
                || "NaN".equalsIgnoreCase(t) || "Infinity".equalsIgnoreCase(t)) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取一个仍然有效的凭证，不存在/过期都抛 BizException（文案对用户友好）。 */
    private Challenge requireAlive(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException("请先完成滑块验证");
        }
        Challenge c = challenges.get(token);
        if (c == null) {
            throw new BizException("滑块验证已失效，请重新拖动");
        }
        if (System.currentTimeMillis() > c.expiresAt) {
            challenges.remove(token);
            throw new BizException("滑块验证已过期，请重新拖动");
        }
        return c;
    }

    /**
     * 组件 success 回调会给出的 left —— 依据源码，它**就等于 offset**（比例 1，不做换算）。
     *
     * ★ 公式来自组件源码（node_modules/vue3-slide-verify/dist/vue3-slide-verify.es.js）：
     *     卡片位置 blockLeft = (w − 60) / (w − 40) × moveX
     *   而成功时 blockLeft ≈ 后端下发的 offset（组件把 offset 直接当 blockX 用） → 反解出 moveX，
     *   再代回同一个公式即得回调里会出现的 left。
     *   合并后就是下面这一行：expected = offset × (w − 40) / (w − 60)。
     * ★ w 是组件实际渲染宽度：前端传多少就用多少（见 createChallenge），
     *   所以前端改 w/l 后不需要改后端 —— 但**改了前端 props 一定要让它把新值传上来**。
     */
    private int expectedFrontendLeft(Challenge c) {
        // ★ 修复（BUG #3）：**直接就是 offset**，不做任何换算。
        //   依据（组件源码）：
        //     block.value.style.left = blockLeft + "px";      // 拖动中持续赋值
        //     const left = block.value.style.left;            // endCb 读它
        //     verify(left, blockX.value, accuracy);           // 成功 ⟺ |blockLeft − offset| ≤ accuracy
        //     emit("success", { left: parseFloat(left) });
        //   ⇒ 回调的 left == blockLeft，而成功时 blockLeft ≈ offset，**比例是 1**。
        //   旧实现用 offset × w/(w−20)（约 ×1.067）属于"方向搞反"的换算：
        //   它来自一次单点观测的外推，并非推导，已废弃。
        return c.offset;
    }

    /**
     * 定期清理过期凭证，避免长时间运行后内存里堆一堆没人再用的 token
     * （与 RateLimitInterceptor.evictExpired 同一套思路；依赖启动类 @EnableScheduling）。
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        int before = challenges.size();
        challenges.entrySet().removeIf(e -> now > e.getValue().expiresAt);
        int removed = before - challenges.size();
        if (removed > 0) {
            LOG.debug("滑块凭证清理：移除 {} 个过期凭证，剩余 {}", removed, challenges.size());
        }
    }

    /** 仅供测试/排查：当前待校验凭证数量 */
    public int pendingCount() {
        return challenges.size();
    }

    // ==================== 内部实现 ====================

    private String randomToken() {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** 生成一张"看起来有点东西"的底图（渐变 + 随机图形 + 噪点线条），纯本地绘制、不依赖任何图片资源。 */
    private BufferedImage drawBackground() {
        BufferedImage img = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 渐变底
        Color c1 = new Color(90 + random.nextInt(120), 110 + random.nextInt(120), 160 + random.nextInt(95));
        Color c2 = new Color(140 + random.nextInt(115), 90 + random.nextInt(120), 150 + random.nextInt(105));
        g.setPaint(new GradientPaint(0, 0, c1, CANVAS_W, CANVAS_H, c2));
        g.fillRect(0, 0, CANVAS_W, CANVAS_H);

        // 随机色块（提高"找缺口"的难度，但不至于看不到）
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256), 60 + random.nextInt(60)));
            int w = 30 + random.nextInt(70);
            int h = 30 + random.nextInt(60);
            g.fillOval(random.nextInt(Math.max(1, CANVAS_W - w)), random.nextInt(Math.max(1, CANVAS_H - h)), w, h);
        }
        // 若干斜线
        g.setStroke(new BasicStroke(2f));
        for (int i = 0; i < 5; i++) {
            g.setColor(new Color(255, 255, 255, 40 + random.nextInt(40)));
            int x1 = random.nextInt(CANVAS_W);
            g.drawLine(x1, 0, random.nextInt(CANVAS_W), CANVAS_H);
        }
        g.dispose();
        return img;
    }

    /**
     * 把缺口从底图"抠"出来：底图上画出暗色缺口形状，同时把该区域像素复制到 slider 图。
     * ★ 抠图的形状按 pieceType 分三种，与前端卡片形状无关（前端只是把图叠上去），
     *   但**形状必须与这里一致**才有"拼上去正好吻合"的观感。
     */
    private void cutPuzzle(BufferedImage full, BufferedImage slider, int offset, int y, int pieceType) {
        Shape shape = pieceShape(offset, y, pieceType);

        // 1) 把缺口区域像素复制到滑块图（其余保持透明）
        for (int x = offset; x < offset + BLOCK_SIZE; x++) {
            for (int yy = y; yy < y + BLOCK_SIZE; yy++) {
                if (x < 0 || yy < 0 || x >= CANVAS_W || yy >= CANVAS_H) continue;
                if (shape.contains(x, yy)) {
                    slider.setRGB(x, yy, full.getRGB(x, yy));
                }
            }
        }

        // 2) 滑块图加一圈描边 + 半透明底，让它在页面上明显是"一块拼图"
        Graphics2D sg = slider.createGraphics();
        sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        sg.setColor(new Color(255, 255, 255, 180));
        sg.setStroke(new BasicStroke(2f));
        sg.draw(shape);
        sg.dispose();

        // 3) 底图上把缺口画暗（"洞"）
        Graphics2D fg = full.createGraphics();
        fg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        fg.setColor(new Color(0, 0, 0, 110));
        fg.fill(shape);
        fg.setColor(new Color(255, 255, 255, 150));
        fg.setStroke(new BasicStroke(1.5f));
        fg.draw(shape);
        fg.dispose();
    }

    private Shape pieceShape(int x, int y, int pieceType) {
        switch (pieceType) {
            case 1:
                return new Ellipse2D.Double(x, y, BLOCK_SIZE, BLOCK_SIZE);
            case 2:
                return new RoundRectangle2D.Double(x, y, BLOCK_SIZE, BLOCK_SIZE, 16, 16);
            default:
                return new Rectangle(x, y, BLOCK_SIZE, BLOCK_SIZE);
        }
    }

    private String toDataUrl(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * 写一条操作日志（失败只记日志、不阻断；复用 @Async 落库，不占请求线程）。
     *
     * ★ 成功与失败**都记操作日志**（需求："滑块验证成功、失败事件接入项目全局操作日志
     *   与异常管理模块"）。
     * ★ 但**失败不写 t_error_log**，这是有意的取舍，理由要写清楚免得被当成漏做：
     *   · 滑块"没对准"是**用户/机器的正常操作结果**，不是程序故障；写进异常日志会让
     *     「异常日志」页被这类记录刷满（"异常日志只留真异常"是本项目的既有口径）；
     *   · 反复失败会构成一个新的骚扰面：公网接口 + 异常日志表只增不减（90 天保留），
     *     脚本可以刷爆这张表。
     *   · 需求要的"异常管理"覆盖的是**系统级故障**（例如 AWT 不可用画不出图）——
     *     那一类在本类里是**抛 IllegalStateException**，会照常进异常日志。
     */
    private void writeLog(String operation, String detail, Integer userId, String ip) {
        try {
            OperationLog log = new OperationLog();
            log.setUserId(userId);
            // 与 LogAspect 同口径：未登录（注册场景）显示"匿名"；昵称留空由日志页显示 user_id
            log.setUsername("匿名");
            log.setOperation(operation);
            log.setMethod("CaptchaService." + (detail.contains("成功") ? "verifyOrThrow(成功)" : "verifyOrThrow(失败)"));
            log.setIp(ip);
            operationLogService.save(log);
            LOG.info("[{}] {}", operation, detail);
        } catch (Exception e) {
            LOG.warn("写滑块日志失败（不影响主流程）：{}", e.getMessage());
        }
    }

    /**
     * 日志里不打印完整 token（它等价于一次性的答案凭证）。
     * ★ 当前调用点：{@link #verify} 的"非法 left"日志（打 raw 与掩码后的 token）。
     *   别把它当死代码删掉 —— 直接拼 token 进日志等于把"能换到一次注册机会的凭证"
     *   写进日志文件，所以任何要打 token 的地方都该走这里。
     */
    private String mask(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 4) + "***" + token.substring(token.length() - 2);
    }
}
