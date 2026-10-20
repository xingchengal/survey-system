package com.survey.mapper;

import com.survey.entity.Upload;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 上传文件登记（t_upload）的数据访问层。
 * 文件本体不在这里；本表只用于 GC 判断「哪些文件是系统上传的」与容量审计。
 */
@Mapper
public interface UploadMapper {

    @Insert("INSERT INTO t_upload(url, original_name, size_bytes, mime, created_by) "
            + "VALUES(#{url}, #{originalName}, #{sizeBytes}, #{mime}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Upload upload);

    /** 全部上传记录（GC 用：url + created_time）。 */
    @Select("SELECT url, created_time FROM t_upload")
    List<Upload> findAll();

    @Delete("DELETE FROM t_upload WHERE url = #{url}")
    int deleteByUrl(String url);
}
