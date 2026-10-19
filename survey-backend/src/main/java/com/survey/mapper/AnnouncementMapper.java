package com.survey.mapper;

import com.survey.entity.Announcement;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 系统公告表（t_announcement）的数据访问层。
 * status：1=上架 / 0=下架；expire_time 可为 NULL（永不过期）。
 * findAll 含下架与过期（管理端），findActive 只取上架且未过期的（用户端）。
 * ★ findActive 必须同时过滤 status = 1 与 expire_time，漏一个就会把已下架/已过期公告弹给用户。
 */
@Mapper
public interface AnnouncementMapper {

    /** 发布公告。status 固定写 1（新发的公告默认上架）。 */
    @Insert("INSERT INTO t_announcement(title, content, publisher_id, publisher_name, status, expire_time) " +
            "VALUES(#{title}, #{content}, #{publisherId}, #{publisherName}, 1, #{expireTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Announcement announcement);

    /** 全部公告（管理端，含已下架 / 已过期）。 */
    @Select("SELECT * FROM t_announcement ORDER BY create_time DESC")
    List<Announcement> findAll();

    /** 有效公告（登录后弹窗）：status = 1 且未过期，见类注释。 */
    @Select("SELECT * FROM t_announcement WHERE status = 1 " +
            "AND (expire_time IS NULL OR expire_time > NOW()) " +
            "ORDER BY create_time DESC")
    List<Announcement> findActive();

    /** 上下架（status：1=上架 0=下架）。 */
    @Update("UPDATE t_announcement SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Integer id, @Param("status") Integer status);

    /** 删除公告。 */
    @Delete("DELETE FROM t_announcement WHERE id = #{id}")
    int deleteById(Integer id);

    // 不提供"按 id 查单条"：上下架/删除都按 id 直接 UPDATE/DELETE，全项目零调用点。
}
