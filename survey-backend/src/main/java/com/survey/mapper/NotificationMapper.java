package com.survey.mapper;

import com.survey.entity.Notification;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 站内通知表（t_notification）的数据访问层。
 * user_id 为接收者；is_read：0=未读 / 1=已读；link 是点击后跳转的前端路由。
 * ★ 按 id 操作（标记已读 / 删除）必须同时带 user_id：只按 id 的话，伪造别人的通知 id 就能改到别人的数据。
 */
@Mapper
public interface NotificationMapper {

    /** 插入通知。is_read 固定写 0（新通知必然未读）。 */
    @Insert("INSERT INTO t_notification(user_id, title, content, type, is_read, link) " +
            "VALUES(#{userId}, #{title}, #{content}, #{type}, 0, #{link})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Notification notification);

    /**
     * 我的通知列表（顶栏铃铛）。LIMIT 200 兜底：通知只增不减，不设上限单次响应会越来越大；
     * 未读数走 countUnread，不受这个截断影响。
     */
    @Select("SELECT * FROM t_notification WHERE user_id = #{userId} ORDER BY create_time DESC LIMIT 200")
    List<Notification> findByUserId(Integer userId);

    /** 我的未读数量（顶栏红点，前端轮询）。 */
    @Select("SELECT COUNT(*) FROM t_notification WHERE user_id = #{userId} AND is_read = 0")
    int countUnread(Integer userId);

    /** 标记单条已读。WHERE 带 user_id，防越权标记别人的通知（见类注释）。 */
    @Update("UPDATE t_notification SET is_read = 1 WHERE id = #{id} AND user_id = #{userId}")
    int markReadByUser(@Param("id") Integer id, @Param("userId") Integer userId);

    /** 一键全部已读（只影响自己的）。 */
    @Update("UPDATE t_notification SET is_read = 1 WHERE user_id = #{userId} AND is_read = 0")
    int markAllRead(Integer userId);

    /** 删除单条。WHERE 同样带 user_id，理由见类注释。 */
    @Delete("DELETE FROM t_notification WHERE id = #{id} AND user_id = #{userId}")
    int deleteByUser(@Param("id") Integer id, @Param("userId") Integer userId);

    /** 清空我的全部通知。只按 user_id 删，只能由带登录身份的入口调用；级联删用户时传被删用户 id。 */
    @Delete("DELETE FROM t_notification WHERE user_id = #{userId}")
    int deleteAllByUser(Integer userId);

    /**
     * 删除指向某份问卷的通知（问卷彻底删除时清孤儿）。
     * ★ 必须按完整路径段匹配：子串匹配时 id=1 会把 /my/surveys/11/... 一起删掉；
     *   也不能放宽成 `/my/surveys%`，协作者邀请等通知的 link 正是 /my/surveys。
     */
    @Delete("DELETE FROM t_notification "
            + "WHERE link = CONCAT('/my/surveys/', #{surveyId}) "
            + "OR link LIKE CONCAT('/my/surveys/', #{surveyId}, '/%')")
    int deleteBySurveyLink(@Param("surveyId") Integer surveyId);
}
