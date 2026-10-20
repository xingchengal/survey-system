package com.survey.service;

import com.survey.common.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本地磁盘存储：文件写到 `app.upload.dir`（默认 ./uploads），由 WebMvcConfig 映射到 /uploads/**。
 *
 * ★ 不信任 Content-Type 与原始文件名：按**文件头魔数**判类型，扩展名由探测结果决定，文件名用 UUID。
 *   只认 JPG / PNG / WebP；SVG 能执行脚本，一律不收。
 * ★ 删除只处理本系统命名的 UUID 文件，且必须落在 upload 根目录内（防 ../ 路径穿越）。
 * ★ 以后上对象存储：新增一个 StorageService 实现，url 前缀仍用 /uploads/（或改成 CDN 域名），调用方不动。
 */
@Service
public class LocalStorageService implements StorageService {

    private static final Logger LOG = LoggerFactory.getLogger(LocalStorageService.class);

    /** 单张图片硬上限：与前端提示、spring.servlet.multipart.max-file-size 保持一致。 */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 只接受本服务生成的文件名：UUID + 允许的扩展名。 */
    private static final Pattern SAFE_NAME =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");

    private static final String URL_PREFIX = "/uploads/";

    @Value("${app.upload.dir:./uploads}")
    private String uploadDir;

    @Override
    public StoredFile save(MultipartFile file, Integer userId) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException("图片不能超过 2MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取图片失败，请重试");
        }
        String ext = detectExtension(bytes);
        if (ext == null) {
            throw new BizException("只支持 JPG / PNG / WebP 图片");
        }
        String name = UUID.randomUUID() + "." + ext;
        Path root = root();
        try {
            Files.createDirectories(root);
            Files.write(root.resolve(name), bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            LOG.error("保存上传图片失败：dir={} name={}", root, name, e);
            throw new BizException("图片保存失败，请稍后重试");
        }
        String mime = "png".equals(ext) ? "image/png" : "webp".equals(ext) ? "image/webp" : "image/jpeg";
        return new StoredFile(URL_PREFIX + name, file.getOriginalFilename(), bytes.length, mime);
    }

    @Override
    public void delete(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return;
        }
        String name = url.substring(URL_PREFIX.length());
        if (!SAFE_NAME.matcher(name).matches()) {
            return;
        }
        Path root = root();
        Path target = root.resolve(name).normalize();
        // 双保险：即使文件名通过正则，也必须落在根目录内
        if (!target.startsWith(root)) {
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            LOG.warn("删除上传图片失败：url={}", url, e);
        }
    }

    @Override
    public List<String> listFiles() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> URL_PREFIX + p.getFileName())
                    .toList();
        } catch (IOException e) {
            LOG.warn("列出上传目录失败：dir={}", root, e);
            return List.of();
        }
    }

    private Path root() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    /**
     * 按文件头判断真实类型：JPEG FFD8FF / PNG 89504E47 / WebP RIFF....WEBP。
     * 返回 null 表示不支持；调用方直接 400，不落盘。
     */
    static String detectExtension(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A
                && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
            return "png";
        }
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
