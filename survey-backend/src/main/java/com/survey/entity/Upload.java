package com.survey.entity;

import java.time.LocalDateTime;

/**
 * 上传文件登记 —— 表 t_upload。
 * 只存元数据，文件本体在 `app.upload.dir`；url 与 t_option.image_url 同口径（相对路径 /uploads/xxx.webp）。
 * ★ 登记是每日 GC 的依据：没有登记的文件不会被清理，也不该被清理（可能是手工放进去的）。
 */
public class Upload {

    private Integer id;
    private String url;
    private String originalName;
    private Integer sizeBytes;
    private String mime;
    private Integer createdBy;
    private LocalDateTime createdTime;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }

    public Integer getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Integer sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }

    public Integer getCreatedBy() { return createdBy; }
    public void setCreatedBy(Integer createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
