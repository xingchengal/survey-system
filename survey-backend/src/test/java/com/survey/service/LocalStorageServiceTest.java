package com.survey.service;

import com.survey.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地存储的类型 / 大小 / 路径安全（真实临时目录，不连库）。
 *
 * ★ 这里钉的是「不信任客户端」：扩展名与 Content-Type 都能伪造，只认文件头魔数；
 *   删除只处理本系统生成的 UUID 文件名，避免被拿来删任意文件。
 */
class LocalStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalStorageService();
        ReflectionTestUtils.setField(storage, "uploadDir", tempDir.toString());
    }

    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }

    private static byte[] webp() {
        byte[] b = new byte[16];
        b[0] = 'R';
        b[1] = 'I';
        b[2] = 'F';
        b[3] = 'F';
        b[8] = 'W';
        b[9] = 'E';
        b[10] = 'B';
        b[11] = 'P';
        return b;
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "image/png", bytes);
    }

    @Test
    @DisplayName("★ 只认文件头：JPG / PNG / WebP 放行，扩展名用探测结果而不是原始文件名")
    void saveAcceptsRealImageHeaders() {
        var jpg = storage.save(file("fake.png", jpeg()), 7);
        var png = storage.save(file("x.html", png()), 7);
        var webp = storage.save(file("photo", webp()), 7);

        assertTrue(jpg.url().endsWith(".jpg"), "扩展名必须来自魔数，不能信任文件名");
        assertTrue(png.url().endsWith(".png"));
        assertTrue(webp.url().endsWith(".webp"));
        assertTrue(Files.exists(tempDir.resolve(jpg.url().substring("/uploads/".length()))));
    }

    @Test
    @DisplayName("★ SVG / 文本 / 空文件一律 400，不落盘")
    void saveRejectsNonRasterImages() {
        assertTrue(assertThrows(BizException.class,
                () -> storage.save(file("x.svg", "<svg onload=alert(1)>".getBytes()), 7))
                .getMessage().contains("JPG"));
        assertTrue(assertThrows(BizException.class,
                () -> storage.save(file("x.txt", "hello world!".getBytes()), 7))
                .getMessage().contains("JPG"));
        assertTrue(assertThrows(BizException.class,
                () -> storage.save(file("empty.png", new byte[0]), 7))
                .getMessage().contains("请选择"));
        assertEquals(0, tempDir.toFile().listFiles() == null ? 0 : tempDir.toFile().listFiles().length);
    }

    @Test
    @DisplayName("超过 2MB → 400")
    void saveRejectsOversizedFile() {
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(png(), 0, big, 0, png().length);
        assertTrue(assertThrows(BizException.class,
                () -> storage.save(file("big.png", big), 7)).getMessage().contains("2MB"));
    }

    @Test
    @DisplayName("删除只认本系统 UUID 文件名；非法路径忽略")
    void deleteOnlyTouchesGeneratedFiles() throws Exception {
        var saved = storage.save(file("a.png", png()), 7);
        String name = saved.url().substring("/uploads/".length());

        storage.delete("/uploads/../../etc/passwd");
        storage.delete("/uploads/not-uuid.png");
        assertTrue(Files.exists(tempDir.resolve(name)), "非法删除不能影响已上传文件");

        storage.delete(saved.url());
        assertFalse(Files.exists(tempDir.resolve(name)));
    }

    @Test
    @DisplayName("listFiles 只返回上传目录下的文件")
    void listFilesReturnsUploadedUrls() throws Exception {
        var saved = storage.save(file("a.png", png()), 7);
        Files.writeString(tempDir.resolve("manual.txt"), "keep me");

        List<String> files = storage.listFiles();

        assertTrue(files.contains(saved.url()));
        assertTrue(files.contains("/uploads/manual.txt"));
    }
}
