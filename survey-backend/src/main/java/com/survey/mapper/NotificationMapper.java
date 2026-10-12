package com.survey.mapper;

import com.survey.entity.Notification;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 站内通知表（t_notification）的数据访问层。
 *
 * ── 表结构要点 ────────────────────────────────────────
 *   user_id  : 接收者
 *   type     : 来源标记（如 "answer"），前端可据此显示不同图标
 *   is_read  : 0=未读 1=已读（新插入的固定为 0，见 insert 里的字面量）
 *   link     : 点击后跳转的前端路由（如 /my/surveys/98/statistics）
 *
 * ── ★ 越权防护（改这里时务必保留）──────────────────────
 *   markReadByUser / deleteByUser 的 WHERE **同时带 id 和 user_id** ——
 *   这是"只能操作自己的通知"的数据库级保证。
 *   即使有人伪造请求改别人的通知 id，SQL 也匹配不到任何行（返回 0）。
 *   配套的 Service 层也要求传 userId，两层一起构成双保险。
 *
 * ── 写入 ──────────────────────────────────────────────
 *   只有 insert 一个写入口，由 NotificationService.send()（@Async）调用。
 */
@Mapper
public interface NotificationMapper {

    /** 插入通知。★ is_read 固定写 0（新通知必然未读）。 */
    @Insert("INSERT INTO t_notification(user_id, title, content, type, is_read, link) " +
            "VALUES(#{userId}, #{title}, #{content}, #{type}, 0, #{link})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Notification notification);

    /**
     * 我的通知列表（顶栏铃铛点开时加载）。
     *
     * ★ 必须有上限：通知是**只增不减**的（每次有人提交答卷就给作者发一条），
     *   若查询不带任何上限，用久了单次响应会越来越大、内存也不可控。
     *   这里加 LIMIT 200：顶栏只需要展示最近的通知，而"未读数"走的是另一条 COUNT 查询
     *   （见 countUnread）—— 所以**加了上限不会让未读数变得不准**。
     *   ★ 以后要做"查看全部通知"页面，再单独加一个带 PageParams 的分页端点即可。
     */
    @Select("SELECT * FROM t_notification WHERE user_id = #{userId} ORDER BY create_time DESC LIMIT 200")
    List<Notification> findByUserId(Integer userId);

    /** 我的未读数量（顶栏铃铛红点，前端 30 秒轮询一次）。 */
    @Select("SELECT COUNT(*) FROM t_notification WHERE user_id = #{userId} AND is_read = 0")
    int countUnread(Integer userId);

    /** 标记单条已读。★ WHERE 带 user_id，防止越权标记别人的通知。 */
    @Update("UPDATE t_notification SET is_read = 1 WHERE id = #{id} AND user_id = #{userId}")
    int markReadByUser(@Param("id") Integer id, @Param("userId") Integer userId);

    /** 一键全部已读（只影响自己的）。 */
    @Update("UPDATE t_notification SET is_read = 1 WHERE user_id = #{userId} AND is_read = 0")
    int markAllRead(Integer userId);

    /** 删除单条。★ WHERE 同样带 user_id。 */
    @Delete("DELETE FROM t_notification WHERE id = #{id} AND user_id = #{userId}")
    int deleteByUser(@Param("id") Integer id, @Param("userId") Integer userId);

    /**
     * 清空我的全部通知。
     * ★ 这个方法只按 user_id 删，所以**只能**由带登录身份的入口调用；
     *   deleteUser() 级联删除用户时也用它，那时传的是被删用户的 id。
     */
    @Delete("DELETE FROM t_notification WHERE user_id = #{userId}")
    int deleteAllByUser(Integer userId);
}
