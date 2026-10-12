package com.survey.common;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Iterator;
import java.util.Set;

/**
 * 头像数据校验 —— 「限制图片文件大小与格式」在后端的**唯一口径**。
 *
 * ── 为什么单独抽成一个类（而不是留在 UserService 里）────────
 *   与 {@link TextLimits}、{@link SqlLikeEscaper} 同一个理由：
 *   ① 这套判定是**纯函数**（输入一个字符串，输出"合法/为什么不合法"），
 *      抽出来才能被**纯单测**覆盖 —— 留在 Service 里就得起 Spring 容器 + 连库才能测；
 *   ② 数值与白名单集中一处：将来要放开 WebP，只改这里的 {@link #ALLOWED_FORMATS}
 *      与 {@link #MAX_BYTES}，不会漏掉散在别处的判断。
 *
 * ── 校验顺序（每一步失败都有能照着改的文案）────────────────
 *   ① 必须是 `data:image/...;base64,` 形式（http(s) 外链 / 裸 base64 一律拒绝）；
 *   ② 声明类型必须是白名单里的（png / jpeg / gif）——
 *      **不是**"以 image/ 开头就算"（那会把 image/svg+xml 放进来，SVG 可携带脚本）；
 *   ③ base64 必须能解码，且**解码后** ≤ {@link #MAX_BYTES}；
 *   ④ 解码出的字节必须是真图片，且**与声明的类型一致**（{@link #detectFormat}）。
 *   ★ 第 ④ 步是"改名绕过"的唯一解：`accept="image/*"` 与 `file.type` 都由客户端说了算，
 *     a.exe 改名成 a.png 能骗过前端；解码后的魔数骗不了。
 *
 * ── 两个数值分工不同（别再当成写错，也别以为它们等价）──────
 *   · {@link #MAX_CHARS} = 3MB：**base64 字符串长度**，只是**廉价的第一道闸** ——
 *     它拦的是"50MB 级请求体"，不表达"图片多大"；
 *   · {@link #MAX_BYTES} = 2MB：**解码后**的字节数，**这才是真正的界**。
 *   ★ 二者**不等价**：3MB 的 base64 解码后约 2.25MB，已经 > 2MB，会被第 ③ 步拒掉。
 *     所以真实生效的上限是 **2MB**，对外文案也只说 2MB（前端按 {@link #MAX_BYTES} 对齐）。
 */
public final class AvatarValidator {

    private AvatarValidator() {
    }

    /** data URL 里 base64 段之前的固定后缀（`data:image/png;base64,` 的 ";base64" 那一段）。 */
    private static final String BASE64_SUFFIX = ";base64";

    /** base64 字符串长度上限（t_user.avatar 是 mediumtext，容量够；这个数是"接口体量"的取舍）。 */
    public static final int MAX_CHARS = 3 * 1024 * 1024;

    /** 解码后的字节上限（2MB）。前端的提示文案按这个数写。 */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    /**
     * 允许的图片格式（同时也是允许声明的 MIME 集合）。
     * ★ 用 Set 而不是数组：contains 的语义就是"白名单"，不会被读成"有序列表"。
     * ★ 刻意**不含 webp / svg**：webp 在老浏览器上显示不了，svg 是可携带脚本的 XML
     *   （存进 avatar 再被 &lt;img src&gt; 加载虽不执行脚本，但没有理由为它开一个口子）。
     */
    private static final Set<String> ALLOWED_FORMATS = Set.of("png", "jpeg", "gif");

    /** 声明类型 → ImageIO 的格式名（两者写法不同，必须显式映射，不能靠字符串相等）。 */
    private static String declaredMimeToFormat(String mime) {
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpeg";
            case "image/gif" -> "gif";
            default -> null;
        };
    }

    /**
     * 校验一个头像字符串。
     *
     * @param avatar 前端提交的 data URL（如 `data:image/png;base64,iVBORw0...`）
     * @throws IllegalArgumentException 校验不通过，message 就是**给用户看的**原因
     *                                  （由 UserService 翻成 BizException → 400 + message）
     */
    public static void validate(String avatar) {
        if (avatar == null || avatar.isEmpty()) {
            throw new IllegalArgumentException("头像不能为空");
        }
        // ① 先看长度：一个 50MB 的字符串在这里就被挡住，不必先做查找与解码
        if (avatar.length() > MAX_CHARS) {
            throw new IllegalArgumentException("图片过大，请选择 3MB 以内的图片");
        }

        int commaIndex = avatar.indexOf(',');
        String prefix = commaIndex < 0 ? avatar : avatar.substring(0, commaIndex);
        if (!prefix.startsWith("data:") || !prefix.endsWith(BASE64_SUFFIX)) {
            throw new IllegalArgumentException(
                    "头像格式不正确：请使用 data:image/...;base64,... 形式的图片数据");
        }
        String declaredMime = prefix.substring("data:".length(), prefix.length() - BASE64_SUFFIX.length());

        // ② 声明类型白名单
        String expectedFormat = declaredMimeToFormat(declaredMime);
        if (expectedFormat == null || !ALLOWED_FORMATS.contains(expectedFormat)) {
            throw new IllegalArgumentException("头像仅支持 PNG / JPG / GIF 格式");
        }

        // ③ 能解码 + 解码后的真实体积
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(avatar.substring(commaIndex + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("头像内容不是合法的 Base64 数据，请重新选择图片");
        }
        if (decoded.length > MAX_BYTES) {
            throw new IllegalArgumentException("图片过大，请选择 2MB 以内的图片");
        }

        // ④ 魔数：真的是图片，且与声明一致
        if (!expectedFormat.equals(detectFormat(decoded))) {
            throw new IllegalArgumentException(
                    "头像内容与声明的图片格式不一致，请重新选择图片（不支持仅改扩展名）");
        }
    }

    /**
     * 用 JDK 自带的 ImageIO 嗅探图片格式（零新增依赖）。
     *
     * ★ 为什么不用"读前 8 个字节比对魔数"的土办法：JPEG 的魔数只有 3 字节（FF D8 FF）、
     *   PNG 要 8 字节、GIF 是 ASCII 头 —— 每种长度都不同，自己写就是一堆特判，
     *   而且**越界要自己判**（一个 4 字节的"图片"能让 substring 直接抛异常）。
     *   ImageIO 会真的解析容器结构，还能顺带挡住"前几个字节对、后面是垃圾"的伪造。
     *
     * @return 规范化格式名（"png" / "jpeg" / "gif" / "bmp" …）；认不出时返回 null
     */
    public static String detectFormat(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        ImageInputStream in = null;
        ImageReader reader = null;
        try {
            in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes));
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            reader = readers.next();
            String format = reader.getFormatName();
            return format == null ? null : format.toLowerCase();
        } catch (Exception e) {
            // 解析失败 = "这不是一张图片"，不是程序故障 —— 返回 null 让调用方给业务文案
            return null;
        } finally {
            // ★ 必须显式关：ImageReader 持有 native 解码资源，只关流不关 reader 会慢慢堆积
            if (reader != null) reader.dispose();
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响判定结果
                }
            }
        }
    }
}
