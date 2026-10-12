package com.survey.mapper;

import com.survey.entity.Announcement;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 系统公告表（t_announcement）的数据访问层。
 *
 * ── 表结构要点 ────────────────────────────────────────
 *   status      : 1=上架  0=下架（下架的不会出现在用户端）
 *   expire_time : 过期时间，可为 NULL（表示永不过期）
 *   publisher_id / publisher_name : 发布者（由 Controller 从登录身份填入）
 *
 * ── 两条查询的区别（别用错）────────────────────────────
 *   findAll    : 全部公告，含下架和过期的 —— **管理员管理页**用
 *   findActive : 只取上架且未过期的 —— **用户端弹窗**用
 */
@Mapper
public interface AnnouncementMapper {

    /** 发布公告。★ status 固定写 1（新发的公告默认上架）。 */
    @Insert("INSERT INTO t_announcement(title, content, publisher_id, publisher_name, status, expire_time) " +
            "VALUES(#{title}, #{content}, #{publisherId}, #{publisherName}, 1, #{expireTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Announcement announcement);

    /** 全部公告（管理员管理页，含已下架/已过期）。 */
    @Select("SELECT * FROM t_announcement ORDER BY create_time DESC")
    List<Announcement> findAll();

    /**
     * 有效公告（用户登录后弹窗）。
     * ★ 两个条件：status = 1（已上架）且（没有过期时间 或 还没过期）。
     */
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

    // ★ 这里**不要**再加"按 id 查单条公告"：全项目零调用点
    //   （注释自己写着"用得少"，实际是"从没用过"）。公告的上下架与删除都是**按 id 直接 UPDATE/DELETE**，
    //   不需要先查出来；要恢复这条查询一行代码的事。
}
