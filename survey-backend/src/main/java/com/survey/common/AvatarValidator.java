package com.survey.common;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Iterator;
import java.util.Set;

/**
 * 头像数据校验 —— 后端"图片大小与格式"的唯一口径（纯函数，可直接单测）。
 * 校验顺序：data URL 形式 → 声明 MIME 白名单（png/jpeg/gif）→ base64 解码 → 解码后 ≤2MB → 魔数与声明一致。
 * ★ 声明类型不能只判 `image/` 开头：会把可携带脚本的 image/svg+xml 放进来。
 * ★ 真正生效的上限是 2MB（MAX_BYTES）；MAX_CHARS=3MB 只是挡大请求体的廉价第一道闸。
 */
public final class AvatarValidator {

    private AvatarValidator() {
    }

    /** data URL 里 base64 段之前的固定后缀（`data:image/png;base64,` 的 ";base64" 那一段）。 */
    private static final String BASE64_SUFFIX = ";base64";

    /** base64 字符串长度上限：只是挡大请求体的第一道闸，不代表图片大小。 */
    public static final int MAX_CHARS = 3 * 1024 * 1024;

    /** 解码后的字节上限（2MB）—— 真正生效的界，前端提示文案按这个数写。 */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    /**
     * 允许的图片格式（同时也是允许声明的 MIME 集合）。
     * 刻意不含 webp / svg：webp 在老浏览器上显示不了，svg 是可携带脚本的 XML。
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
     * 校验头像 data URL。
     * @param avatar 前端提交的 `data:image/png;base64,iVBORw0...`
     * @throws IllegalArgumentException message 是给用户看的原因（UserService 翻成 BizException → 400）
     */
    public static void validate(String avatar) {
        if (avatar == null || avatar.isEmpty()) {
            throw new IllegalArgumentException("头像不能为空");
        }
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

        String expectedFormat = declaredMimeToFormat(declaredMime);
        if (expectedFormat == null || !ALLOWED_FORMATS.contains(expectedFormat)) {
            throw new IllegalArgumentException("头像仅支持 PNG / JPG / GIF 格式");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(avatar.substring(commaIndex + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("头像内容不是合法的 Base64 数据，请重新选择图片");
        }
        if (decoded.length > MAX_BYTES) {
            throw new IllegalArgumentException("图片过大，请选择 2MB 以内的图片");
        }

        if (!expectedFormat.equals(detectFormat(decoded))) {
            throw new IllegalArgumentException(
                    "头像内容与声明的图片格式不一致，请重新选择图片（不支持仅改扩展名）");
        }
    }

    /**
     * 用 JDK 自带的 ImageIO 嗅探图片格式（零新增依赖；会解析容器结构，能挡住"前几个字节对、后面是垃圾"）。
     * @return 规范化格式名（"png" / "jpeg" / "gif" …）；认不出时返回 null
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
            // 必须显式关：ImageReader 持有 native 解码资源，只关流不关 reader 会慢慢堆积
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
