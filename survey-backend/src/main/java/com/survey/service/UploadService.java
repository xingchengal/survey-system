package com.survey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.survey.entity.Upload;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.SurveyVersionMapper;
import com.survey.mapper.UploadMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 选项配图上传 + 孤儿文件清理。
 *
 * ★ 清理不做「删选项就删文件」：同一张图会被**版本快照**和**复制出来的问卷**引用，
 *   同步删必然误删。改为每天扫一次引用集合，超过 30 天仍没被引用才删。
 * ★ 引用集合 = 活跃选项 t_option.image_url ∪ 所有版本快照 JSON 里的 imageUrl。
 *   漏掉快照会导致"回滚后图裂"。
 */
@Service
public class UploadService {

    private static final Logger LOG = LoggerFactory.getLogger(UploadService.class);

    /** 孤儿文件宽限期：保留 30 天，给回滚 / 复制 / 误删恢复留窗口。 */
    private static final int GC_RETENTION_DAYS = 30;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private StorageService storageService;

    @Autowired
    private UploadMapper uploadMapper;

    @Autowired
    private OptionMapper optionMapper;

    @Autowired
    private SurveyVersionMapper surveyVersionMapper;

    /** 保存一张选项配图并登记；返回相对路径，前端直接写进选项的 imageUrl。 */
    @Transactional
    public String saveOptionImage(Integer userId, MultipartFile file) {
        StorageService.StoredFile stored = storageService.save(file, userId);
        Upload upload = new Upload();
        upload.setUrl(stored.url());
        upload.setOriginalName(stored.originalName());
        upload.setSizeBytes((int) stored.size());
        upload.setMime(stored.mime());
        upload.setCreatedBy(userId);
        uploadMapper.insert(upload);
        return stored.url();
    }

    /**
     * 每天 03:30 清理孤儿文件：只有「已登记、30 天前、仍在磁盘、且没人引用」的才删。
     * 版本快照里的 imageUrl 也算引用——回滚会把它们写回选项。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void collectOrphanFiles() {
        Set<String> referenced = new HashSet<>();
        referenced.addAll(optionMapper.findAllImageUrls());
        for (String snapshot : surveyVersionMapper.findAllQuestionsJson()) {
            try {
                JsonNode root = JSON.readTree(snapshot);
                for (String url : root.findValuesAsText("imageUrl")) {
                    if (url != null && !url.isBlank()) {
                        referenced.add(url);
                    }
                }
            } catch (Exception e) {
                // 单条快照坏掉不能中断整轮清理，也不能因此把图全删了：跳过该条即可
                LOG.warn("解析版本快照失败，跳过其图片引用：{}", e.getMessage());
            }
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(GC_RETENTION_DAYS);
        Set<String> onDisk = new HashSet<>(storageService.listFiles());
        int removed = 0;
        List<Upload> uploads = uploadMapper.findAll();
        for (Upload upload : uploads) {
            String url = upload.getUrl();
            if (url == null || referenced.contains(url) || !onDisk.contains(url)) {
                continue;
            }
            if (upload.getCreatedTime() != null && upload.getCreatedTime().isAfter(cutoff)) {
                continue;
            }
            storageService.delete(url);
            uploadMapper.deleteByUrl(url);
            removed++;
        }
        if (removed > 0) {
            LOG.info("上传文件 GC：清理 {} 个不再被引用的文件（宽限 {} 天）", removed, GC_RETENTION_DAYS);
        }
    }
}
