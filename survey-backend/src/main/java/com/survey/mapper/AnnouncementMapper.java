package com.survey.mapper;

import com.survey.entity.Announcement;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AnnouncementMapper {

    @Insert("INSERT INTO t_announcement(title, content, publisher_id, publisher_name, status, expire_time) " +
            "VALUES(#{title}, #{content}, #{publisherId}, #{publisherName}, 1, #{expireTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Announcement announcement);

    @Select("SELECT * FROM t_announcement ORDER BY create_time DESC")
    List<Announcement> findAll();

    @Select("SELECT * FROM t_announcement WHERE status = 1 " +
            "AND (expire_time IS NULL OR expire_time > NOW()) " +
            "ORDER BY create_time DESC")
    List<Announcement> findActive();

    @Update("UPDATE t_announcement SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    @Delete("DELETE FROM t_announcement WHERE id = #{id}")
    int deleteById(Integer id);

    @Select("SELECT * FROM t_announcement WHERE id = #{id}")
    Announcement findById(Integer id);
}