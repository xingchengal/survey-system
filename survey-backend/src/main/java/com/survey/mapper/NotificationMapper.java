package com.survey.mapper;

import com.survey.entity.Notification;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface NotificationMapper {

    @Insert("INSERT INTO t_notification(user_id, title, content, type, is_read, link) " +
            "VALUES(#{userId}, #{title}, #{content}, #{type}, 0, #{link})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Notification notification);

    @Select("SELECT * FROM t_notification WHERE user_id = #{userId} ORDER BY create_time DESC")
    List<Notification> findByUserId(Integer userId);

    @Select("SELECT COUNT(*) FROM t_notification WHERE user_id = #{userId} AND is_read = 0")
    int countUnread(Integer userId);

    // ★ 修复问题 6：带上 user_id 条件，防止越权
    @Update("UPDATE t_notification SET is_read = 1 WHERE id = #{id} AND user_id = #{userId}")
    int markReadByUser(@Param("id") Integer id, @Param("userId") Integer userId);

    @Update("UPDATE t_notification SET is_read = 1 WHERE user_id = #{userId} AND is_read = 0")
    int markAllRead(Integer userId);

    // ★ 修复问题 6：带上 user_id 条件
    @Delete("DELETE FROM t_notification WHERE id = #{id} AND user_id = #{userId}")
    int deleteByUser(@Param("id") Integer id, @Param("userId") Integer userId);

    @Delete("DELETE FROM t_notification WHERE user_id = #{userId}")
    int deleteAllByUser(Integer userId);
}