package com.survey.service;

import com.survey.entity.Upload;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.SurveyVersionMapper;
import com.survey.mapper.UploadMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 上传登记与孤儿文件 GC（Mockito，不连库、不写真磁盘）。
 *
 * ★ 关键不变量：**被版本快照引用的图不能删**。快照是回滚的来源，
 *   只按 t_option 判断会删掉"当前没人用、但回滚要用"的图，回滚后选项图直接裂开。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UploadServiceTest {

    @Mock
    private StorageService storageService;
    @Mock
    private UploadMapper uploadMapper;
    @Mock
    private OptionMapper optionMapper;
    @Mock
    private SurveyVersionMapper surveyVersionMapper;

    @InjectMocks
    private UploadService uploadService;

    private static Upload upload(String url, int daysAgo) {
        Upload u = new Upload();
        u.setUrl(url);
        u.setCreatedTime(LocalDateTime.now().minusDays(daysAgo));
        return u;
    }

    @Test
    @DisplayName("保存配图：落盘后写 t_upload 登记，返回相对路径")
    void saveOptionImageRegistersFile() {
        when(storageService.save(any(), anyInt()))
                .thenReturn(new StorageService.StoredFile("/uploads/a.png", "a.png", 123, "image/png"));

        String url = uploadService.saveOptionImage(7,
                new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3}));

        assertEquals("/uploads/a.png", url);
        verify(uploadMapper).insert(any(Upload.class));
    }

    @Test
    @DisplayName("★ GC：选项引用、版本快照引用、未过宽限期都不删；只删登记过的孤儿")
    void gcKeepsReferencedAndYoungFiles() {
        // a 被活跃选项引用；b 只在版本快照里；c 是 40 天前的孤儿；d 是 10 天前的孤儿（宽限内）
        when(storageService.listFiles()).thenReturn(List.of(
                "/uploads/a.png", "/uploads/b.png", "/uploads/c.png", "/uploads/d.png"));
        when(optionMapper.findAllImageUrls()).thenReturn(List.of("/uploads/a.png"));
        when(surveyVersionMapper.findAllQuestionsJson()).thenReturn(List.of(
                "{\"questions\":[{\"options\":[{\"content\":\"A\",\"imageUrl\":\"/uploads/b.png\"}]}]}"));
        when(uploadMapper.findAll()).thenReturn(List.of(
                upload("/uploads/a.png", 40), upload("/uploads/b.png", 40),
                upload("/uploads/c.png", 40), upload("/uploads/d.png", 10)));

        uploadService.collectOrphanFiles();

        verify(storageService).delete("/uploads/c.png");
        verify(uploadMapper).deleteByUrl("/uploads/c.png");
        verify(storageService, never()).delete("/uploads/a.png");
        verify(storageService, never()).delete("/uploads/b.png");
        verify(storageService, never()).delete("/uploads/d.png");
        verify(uploadMapper, never()).deleteByUrl("/uploads/a.png");
        verify(uploadMapper, never()).deleteByUrl("/uploads/b.png");
        verify(uploadMapper, never()).deleteByUrl("/uploads/d.png");
    }

    @Test
    @DisplayName("GC：快照 JSON 坏掉时跳过该条，不中断整轮清理")
    void gcSkipsBrokenSnapshot() {
        when(storageService.listFiles()).thenReturn(List.of("/uploads/c.png"));
        when(optionMapper.findAllImageUrls()).thenReturn(List.of());
        when(surveyVersionMapper.findAllQuestionsJson()).thenReturn(List.of("not-json"));
        when(uploadMapper.findAll()).thenReturn(List.of(upload("/uploads/c.png", 40)));

        uploadService.collectOrphanFiles();

        verify(storageService).delete("/uploads/c.png");
    }
}
