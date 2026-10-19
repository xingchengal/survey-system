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
 * 滑块验证码（本地 AWT 绘制，零新增依赖）：生成底图 + 缺口位置 + 一次性校验凭证，并做后端二次校验。
 * 两步式：/captcha/slider 出图与 offset 并存凭证 → /captcha/verify 只比对并标记 → 注册时才消费。
 * ★ token 由服务端 SecureRandom 签发，缺口位置与判定也都在后端；凭证 60 秒过期、一次性，注册时才消费。
 * 失败计数：同一 token 连续错 {@value #MAX_FAILS} 次即作废（前端需重新取图），防固定 token 试探。
 * 凭证存内存（单实例部署）：多实例必须换 Redis，否则 A 实例发的图 B 实例验不过。
 * AWT 依赖 headless（application.yaml 与启动类双保险）；失败只记操作日志，不写 t_error_log。
 */
@Service
public class CaptchaService {

    private static final Logger LOG = LoggerFactory.getLogger(CaptchaService.class);
    /** 服务端画布尺寸（只用于出图；组件的渲染尺寸以它上报的 w/l 为准）。 */
    public static final int CANVAS_W = 320;
    public static final int CANVAS_H = 160;
    /** 缺口方块的像素边长（服务端切图用） */
    public static final int BLOCK_SIZE = 46;
    /**
     * 判定容差（px），固定值，不随 offset 缩放。
     * ★ 组件 success 回调的 left 就是 blockLeft，成功时 blockLeft ≈ offset（比例 1），无需换算。
     *   12px 只兜底像素取整与拖动抖动，不影响安全性：绕过 /captcha/verify 直接注册会因没有 verified 标记被拒。
     */
    public static final int TOLERANCE = 12;

    /** 组件默认值：仅当调用方没带 w/l 时兜底；用错尺寸会算出越界的 offset，组件直接 throw、缺口不画。 */
    private static final int COMPONENT_W = 310;
    private static final int COMPONENT_L = 42;
    /** 组件圆角半径：前端显式传 :r="10"，这里与组件同式（L = l + 2r + 3）。 */
    private static final int COMPONENT_R = 10;
    /** 同一凭证最多允许失败几次（超过即作废，前端需重新取图） */
    public static final int MAX_FAILS = 3;

    private static final long TTL_MS = 60_000L;
    /**
     * 缺口垂直位置的上下留边（水平合法区间见 createChallenge，随组件 w/l 现算）。
     */
    private static final int MIN_Y = 10;

    private final SecureRandom random = new SecureRandom();

    @Autowired
    private OperationLogService operationLogService;

    /**
     * 一张待校验的滑块凭证；只放内存，单实例部署的取舍见类注释。
     */
    private static final class Challenge {
        final int offset;
        final int y;
        final long expiresAt;
        final int pieceType;
        /** 前端上报的组件宽 / 缺口边长：用于算 offset 合法区间 [L+10, w−(L+10)]，必须与组件同式。 */
        final int componentW;
        final int componentL;
        volatile int fails;
        /**
         * 是否已通过 {@code /api/captcha/verify} 的校验。
         * 注册只认这个标记：只看"凭证还在"等于谁拿到 token 都能注册。
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
     * @return {token, bgImage, sliderImage, canvasWidth, canvasHeight, blockSize, offset, tolerance, expiresIn, expectedLeft}
     *         bgImage 是未挖洞的纯底图（组件自己按 offset 切图），sliderImage 仅服务端留档。
     */
    public Map<String, Object> createChallenge(Integer frontendW, Integer frontendL) {
        boolean prevHeadless = GraphicsEnvironment.isHeadless();
        // 兜底：即使 yaml 漏配 headless，也不会因为"没有显示器"而崩
        System.setProperty("java.awt.headless", "true");

        // 调用方带上它实际用的组件宽 / 缺口边长（越界一律按默认值）：判定必须在组件的坐标系里做。
        int cw = (frontendW != null && frontendW >= 200 && frontendW <= 500) ? frontendW : COMPONENT_W;
        int cl = (frontendL != null && frontendL >= 20 && frontendL <= 100) ? frontendL : COMPONENT_L;

        // ★ offset 必须落在组件的合法区间内：组件在 img.onload 里硬校验，越界直接 throw、缺口不画。
        //   区间随组件的 l/r/w 变化，所以与组件同式（L = l + 2r + 3）在这里现算。
        int blockLen = cl + COMPONENT_R * 2 + 3;          // 与组件的 L 完全同式
        int minOffset = blockLen + 10;
        int maxOffset = cw - (blockLen + 10);
        if (maxOffset < minOffset) {
            // 尺寸不合法是调用方传错参数：立刻抛，不能悄悄用越界值继续（那正是缺口画不出的来源）。
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
            // 先把"没画洞"的纯底图序列化出去，再在这张图上挖洞：组件会自己按 offset 切图，
            // 喂有洞的背景会出现"两个洞"；full 之后才挖洞，仅用于留档/排查。
            result.put("bgImage", toDataUrl(full));

            BufferedImage slider = new BufferedImage(CANVAS_W, CANVAS_H, BufferedImage.TYPE_INT_ARGB);
            cutPuzzle(full, slider, offset, y, pieceType);
            // sliderImage 暂无前端消费者（组件自己切图），保留用于排查与将来自研 Canvas 滑块。
            result.put("sliderImage", toDataUrl(slider));
        } catch (Exception e) {
            // AWT 不可用属系统级故障：抛出去进异常日志，不能因为画不出图就静默放行（滑块是注册前置）
            challenges.remove(token);
            throw new IllegalStateException("验证码生成失败（AWT 不可用）", e);
        } finally {
            if (!prevHeadless) {
                // 不还原也无碍（headless 只影响 AWT），仅保持环境干净
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
        // 额外告诉前端"在这个组件里拖到缺口处时，success 回调的 left 大约是多少"
        // —— 仅供排查与展示，判定仍以后端重算为准（前端改这个值没有任何用）
        result.put("expectedLeft", expectedFrontendLeft(challenge));
        return result;
    }

    /**
     * 第 1 步：判定这次拖动是否对准缺口，对准则标记 verified（不消费凭证）。
     * 判定与消费分离：验证通过后用户还可能改表单或被 @Valid 驳回，此时不该要求重拖。
     *
     * @param rawLeft 组件 success 回调给的 left（组件坐标系，不是后端的 offset）
     * @return passed / expectedLeft / actualLeft / diff，以及 invalidLeft / fails / maxFails / expired 等可选键
     * @throws BizException 凭证不存在 / 已过期（必须明确报错，不能让前端以为只是没拖准）
     */
    public Map<String, Object> verify(String token, String rawLeft, Integer userId, String ip) {
        Challenge c = requireAlive(token);

        Map<String, Object> out = new LinkedHashMap<>();
        Integer left = parseLeft(rawLeft);
        if (left == null) {
            // 给业务文案：组件在渲染时序不巧时会给出 NaN，那不是"用户拖错了"，而是这次拖动没被正确记录，
            // 应让前端换图重来，而不是把 Spring 的类型错误原样暴露出去。
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
            // 这里不作废凭证（只在 register 消费）：前端每次失败都会自己换图，作废等于让最后一次判定白跑。
            // 防爆破由"凭证 60 秒过期 + 取图/判定限流"承担。
            if (fails >= MAX_FAILS) {
                challenges.remove(token);
                out.put("expired", true);
            }
        }
        return out;
    }

    /**
     * 第 2 步：注册落库前调用 —— 只认 verified 标记并消费凭证（一次性）。
     *
     * @throws BizException 未取图 / 已过期 / 未通过验证
     */
    public void consumeVerifiedOrThrow(String token) {
        Challenge c = requireAlive(token);
        if (!c.verified) {
            throw new BizException("滑块验证未通过，请重新拖动");
        }
        challenges.remove(token);   // 一次性：注册消耗掉它
    }

    /**
     * 把前端传来的 left 解析成整数：NaN / Infinity / 空串 / 非数字一律返回 null（由调用方给业务文案）。
     *
     * @return 合法整数；非法或缺失返回 null
     */
    private Integer parseLeft(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        // 双重防御：前端已用 Number.isFinite 挡过，但直接调接口的人可能传这些
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
     * 组件 success 回调会给出的 left —— 依据组件源码它**就等于 offset**（比例 1，不做换算）。
     * 组件公式 blockLeft = (w−60)/(w−40) × moveX，成功时 blockLeft ≈ offset，反解后代回即得。
     * w 取前端上报的组件宽度：改前端 props 时务必让它把新值传上来。
     */
    private int expectedFrontendLeft(Challenge c) {
        return c.offset;
    }

    /**
     * 定期清理过期凭证，避免内存里堆积无人再用的 token（依赖启动类 @EnableScheduling）。
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
     * 把缺口从底图抠出来：底图上画出暗色缺口，同时把该区域像素复制到 slider 图。
     * 形状按 pieceType 分三种（方形/圆形/圆角），必须与前端卡片形状一致，才有"拼上去正好吻合"的观感。
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
     * 成功与失败都记操作日志，但**失败不写 t_error_log**：没对准是正常操作结果，
     * 写进异常页会把它刷满，公网接口还能被脚本刷爆这张只增不减的表；
     * 真正的系统级故障（如 AWT 不可用）在本类是抛 IllegalStateException，会照常进异常日志。
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
     * 日志里不打印完整 token（它等价于一次性的答案凭证）：任何要打 token 的地方都走这里。
     */
    private String mask(String token) {
        if (token == null || token.length() < 8) return "***";
        return token.substring(0, 4) + "***" + token.substring(token.length() - 2);
    }
}
