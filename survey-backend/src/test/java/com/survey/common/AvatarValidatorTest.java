package com.survey.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AvatarValidator} 的纯单元测试 —— 对应「个人中心改版」需求 二.2
 * （"限制图片文件大小与格式，**后端增加文件校验**"）。
 *
 * ── 测试范围与原则 ────────────────────────────────────
 * 只测这个**纯静态**类：不启动 Spring 容器、不连数据库、不访问网络。
 * 真正的图片字节由 ImageIO **现场画一张**（见 {@link #pngDataUrl}），
 * 所以这里验的是"真图能过、假图被拒"，而不是"我 mock 了一个 magics 数组"。
 *
 * ── 为什么这些用例值得逐个钉住 ────────────────────────
 *   头像这条链路改造前只判"非空 + 字符串长度"，任何文本都能存进去 ——
 *   而前端 el-avatar 会把它当 URL 加载。下面每一类"坏输入"都对应一种真实的绕过手法：
 *   http 外链、裸 base64、改名（声明 png 实为 jpeg）、SVG、超长、空。
 */
class AvatarValidatorTest {

    /** 画一张 w×h 的纯色 PNG，返回 base64（不含 data URL 前缀）。 */
    private static String pngBase64(int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, w, h);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("测试用的 PNG 生成失败", e);
        }
    }

    private static String pngDataUrl(int w, int h) {
        return "data:image/png;base64," + pngBase64(w, h);
    }

    /** 画一张 JPEG 并包成 data URL（用来构造"声明 png、实际 jpeg"的错配）。 */
    private static String jpegDataUrl() {
        try {
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "jpeg", out);
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("测试用的 JPEG 生成失败", e);
        }
    }

    // ==================== 1. 正常路径 ====================

    @Test
    @DisplayName("合法的 PNG data URL 必须通过（这是前端裁剪组件实际会提交的形态）")
    void validate_shouldAcceptRealPng() {
        assertDoesNotThrow(() -> AvatarValidator.validate(pngDataUrl(64, 64)));
        // 前端裁剪组件输出的就是 320×320 的 PNG，尺寸本身不参与校验，但这条保证"不是只认小图"
        assertDoesNotThrow(() -> AvatarValidator.validate(pngDataUrl(320, 320)));
    }

    @Test
    @DisplayName("合法的 JPEG data URL 必须通过（image/jpeg 与 image/jpg 两种声明都认）")
    void validate_shouldAcceptRealJpeg() {
        String jpeg = jpegDataUrl();
        assertDoesNotThrow(() -> AvatarValidator.validate(jpeg));
        // image/jpg 不是标准 MIME，但确实有客户端这么发 —— 白名单里显式认它
        assertDoesNotThrow(() -> AvatarValidator.validate(jpeg.replace("image/jpeg", "image/jpg")));
    }

    // ==================== 2. 空值 / 超长 ====================

    @Test
    @DisplayName("null / 空串 → 报「头像不能为空」")
    void validate_shouldRejectNullOrEmpty() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate(null));
        assertEquals("头像不能为空", e1.getMessage());

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate(""));
        assertEquals("头像不能为空", e2.getMessage());
    }

    @Test
    @DisplayName("长度超过 MAX_CHARS → 报「3MB 以内」（且不看内容，先按长度挡住）")
    void validate_shouldRejectTooLongString() {
        // 造一个"长度超限但前缀合法"的字符串：证明长度检查发生在解码之前
        String tooLong = "data:image/png;base64," + "A".repeat(AvatarValidator.MAX_CHARS);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate(tooLong));
        assertEquals("图片过大，请选择 3MB 以内的图片", e.getMessage());
    }

    // ==================== 3. 形式与前缀 ====================

    @Test
    @DisplayName("http(s) 外链、裸 base64、缺 ;base64 后缀 → 都报「请使用 data:image/...;base64,... 形式」")
    void validate_shouldRejectNonDataUrl() {
        String expected = "头像格式不正确：请使用 data:image/...;base64,... 形式的图片数据";

        // 改造前这些**全都能存进库**（只判非空与长度），而 el-avatar 会把它当 src 去请求
        assertEquals(expected, assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate("https://example.com/a.png")).getMessage());
        assertEquals(expected, assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate(pngBase64(8, 8))).getMessage());
        // 缺 ";base64" 段
        assertEquals(expected, assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate("data:image/png," + pngBase64(8, 8))).getMessage());
    }

    @Test
    @DisplayName("声明 image/svg+xml → 被白名单拒绝（SVG 是可携带脚本的 XML，不该放进来）")
    void validate_shouldRejectSvg() {
        String svg = "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate(svg));
        assertEquals("头像仅支持 PNG / JPG / GIF 格式", e.getMessage());
    }

    // ==================== 4. base64 与体积 ====================

    @Test
    @DisplayName("base64 段不是合法 Base64 → 报「不是合法的 Base64 数据」")
    void validate_shouldRejectBrokenBase64() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate("data:image/png;base64,@@@not-base64@@@"));
        assertEquals("头像内容不是合法的 Base64 数据，请重新选择图片", e.getMessage());
    }

    @Test
    @DisplayName("解码后超过 MAX_BYTES → 报「2MB 以内」（与字符串长度是两条不同的闸）")
    void validate_shouldRejectTooManyBytes() {
        // ★ 造"真 PNG + 尾部垃圾"：
        //   ① 尾部垃圾是**合法 Base64 且长度是 4 的倍数**，所以不会被 ③ 的"base64 非法"先拦下；
        //   ② PNG 的 IEND 之后补字节是合法 PNG 文件（ImageIO 只读图像结构、忽略尾部数据），
        //      所以这条用例确实停在"体积"这一道闸上，而不是停在魔数上。
        //      —— 这个构造方式本身也值得记：**声明与实际**的校验必须按"先体积、后魔数"的固定顺序来，
        //      否则用例会红在一个与它名字无关的分支上（第一版就是这么写错的）。
        byte[] png = Base64.getDecoder().decode(pngBase64(8, 8));
        int padBytes = AvatarValidator.MAX_BYTES - png.length + 16;   // 恰好越过上限一点
        assertTrue(padBytes % 3 == 0, "补丁长度需能整除 3，才能拼出长度是 4 的倍数的 Base64");
        String padded = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(png)
                + "A".repeat(padBytes / 3 * 4);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate(padded));
        assertEquals("图片过大，请选择 2MB 以内的图片", e.getMessage());
    }

    @Test
    @DisplayName("正好在体积上限内的小图必须通过（证明体积闸不会误伤正常图片）")
    void validate_shouldAcceptUnderByteLimit() {
        // 8×8 的纯色 PNG 只有几十字节，离两个上限都很远
        assertDoesNotThrow(() -> AvatarValidator.validate(pngDataUrl(8, 8)));
    }

    // ==================== 5. 魔数（反"改名绕过"）====================

    @Test
    @DisplayName("声明 png 但内容是 jpeg → 被魔数识破（前端 file.type / 扩展名都拦不住这类伪造）")
    void validate_shouldRejectMimeMismatch() {
        String sneaky = jpegDataUrl().replace("image/jpeg", "image/png");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate(sneaky));
        assertEquals("头像内容与声明的图片格式不一致，请重新选择图片（不支持仅改扩展名）", e.getMessage());
    }

    @Test
    @DisplayName("声明 png 但内容是纯文本（改名成 .png 的可执行文件/脚本）→ 被魔数识破")
    void validate_shouldRejectNonImagePayload() {
        String text = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString("MZ\u0000\u0000this is an exe renamed to png".getBytes());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AvatarValidator.validate(text));
        assertEquals("头像内容与声明的图片格式不一致，请重新选择图片（不支持仅改扩展名）", e.getMessage());
    }

    // ==================== 6. detectFormat 自身的边界 ====================

    @Test
    @DisplayName("detectFormat：null / 空数组 / 短字节 / 垃圾字节一律返回 null，且不抛异常")
    void detectFormat_shouldBeRobust() {
        assertNull(AvatarValidator.detectFormat(null));
        assertNull(AvatarValidator.detectFormat(new byte[0]));
        // ★ 这一条是"土办法读魔数"最容易崩的地方：只有 4 个字节
        assertNull(AvatarValidator.detectFormat(new byte[]{0x00, 0x01, 0x02, 0x03}));
        assertNull(AvatarValidator.detectFormat("not an image at all".getBytes()));
    }

    @Test
    @DisplayName("detectFormat：真 PNG 返回 \"png\"（小写），与白名单里的写法一致")
    void detectFormat_shouldReturnLowercaseName() {
        byte[] png = Base64.getDecoder().decode(pngBase64(4, 4));
        assertEquals("png", AvatarValidator.detectFormat(png));
    }

    @Test
    @DisplayName("两个体积上限的关系固定为 MAX_CHARS(3MB) > MAX_BYTES(2MB)，且前端提示按 MAX_BYTES")
    void constants_shouldKeepDocumentedRelation() {
        // 防回归：这两个数如果被人改成相等或反了，前端提示与后端判定就会对不上
        assertEquals(3 * 1024 * 1024, AvatarValidator.MAX_CHARS);
        assertEquals(2 * 1024 * 1024, AvatarValidator.MAX_BYTES);
        assertTrue(AvatarValidator.MAX_CHARS > AvatarValidator.MAX_BYTES,
                "base64 字符串上限必须大于解码后字节上限（base64 会放大约 4/3 倍）");
    }
}
