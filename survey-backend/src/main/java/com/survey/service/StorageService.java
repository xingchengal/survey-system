package com.survey.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件存储的唯一入口：本地磁盘与对象存储只换实现，上传端点与 GC 不动。
 * ★ url 一律返回相对路径 `/uploads/xxx.webp`：换域名 / 换云存储时只改前缀，不改存量数据。
 */
public interface StorageService {

    /** 保存结果：相对路径 + 审计元数据（原始文件名只记录，不参与存储路径）。 */
    record StoredFile(String url, String originalName, long size, String mime) {
    }

    StoredFile save(MultipartFile file, Integer userId);

    /** 删除一个由本系统保存的文件；非法路径 / 非本系统命名的文件一律忽略。 */
    void delete(String url);

    /** 已存在的文件相对路径列表（GC 用）。 */
    List<String> listFiles();
}
