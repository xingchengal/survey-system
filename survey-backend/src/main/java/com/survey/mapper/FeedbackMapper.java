package com.survey.mapper;

import com.survey.dto.FeedbackView;
import com.survey.entity.Feedback;
import com.survey.entity.FeedbackMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 反馈 / 客服工单（t_feedback + t_feedback_message）的读写。
 *
 * ★ 用户侧所有查询都必须带 user_id：工单归属是唯一防线，不能只靠 Controller 判登录。
 * ★ 消息按 id 递增做增量（afterId），单次最多 limit 条；hasMore 由 Service 多取一条判断。
 * ★ 已读水位只进不退（GREATEST），避免并发拉取把已读打回旧值。
 */
@Mapper
public interface FeedbackMapper {

    /** 新建工单头（status 固定 OPEN，不接受外部传入）。 */
    @Insert("INSERT INTO t_feedback(user_id, type, subject, status) "
            + "VALUES(#{userId}, #{type}, #{subject}, 'OPEN')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFeedback(Feedback feedback);

    /** 新建消息；client_msg_id 由唯一键 (feedback_id, client_msg_id) 兜底幂等。 */
    @Insert("INSERT INTO t_feedback_message(feedback_id, sender_id, sender_role, content, client_msg_id, created_time) "
            + "VALUES(#{feedbackId}, #{senderId}, #{senderRole}, #{content}, #{clientMsgId}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMessage(FeedbackMessage message);

    @Select("SELECT * FROM t_feedback WHERE id = #{id}")
    Feedback findById(@Param("id") Integer id);

    /** 某用户当前未关闭的客服工单（再点客服续开同一条）。 */
    @Select("SELECT * FROM t_feedback WHERE user_id = #{userId} AND type = 'SERVICE' "
            + "AND status = 'OPEN' LIMIT 1")
    Feedback findOpenService(@Param("userId") Integer userId);

    /** 我的工单列表：未读按"用户水位 < 最新消息"算；最多 100 条（量级不需要分页器）。 */
    @Select("SELECT f.id, f.user_id AS userId, f.type, f.subject, f.status, f.handled_by AS handledBy, "
            + "f.last_message_id AS lastMessageId, f.last_message_preview AS lastMessagePreview, "
            + "f.last_message_time AS lastMessageTime, f.created_time AS createdTime, "
            + "CASE WHEN f.last_message_id IS NOT NULL AND (f.user_last_read_message_id IS NULL "
            + "     OR f.user_last_read_message_id < f.last_message_id) THEN 1 ELSE 0 END AS unread "
            + "FROM t_feedback f WHERE f.user_id = #{userId} "
            + "ORDER BY f.last_message_id DESC, f.id DESC LIMIT 100")
    List<FeedbackView> findMyList(@Param("userId") Integer userId);

    /** 用户视角的单条工单（详情用；必须带 user_id，避免读到别人的工单）。 */
    @Select("SELECT f.id, f.user_id AS userId, f.type, f.subject, f.status, f.handled_by AS handledBy, "
            + "f.last_message_id AS lastMessageId, f.last_message_preview AS lastMessagePreview, "
            + "f.last_message_time AS lastMessageTime, f.created_time AS createdTime, "
            + "CASE WHEN f.last_message_id IS NOT NULL AND (f.user_last_read_message_id IS NULL "
            + "     OR f.user_last_read_message_id < f.last_message_id) THEN 1 ELSE 0 END AS unread "
            + "FROM t_feedback f WHERE f.id = #{id} AND f.user_id = #{userId}")
    FeedbackView findMyViewById(@Param("id") Integer id, @Param("userId") Integer userId);

    /** 我的未读工单数（只算 OPEN）。 */
    @Select("SELECT COUNT(*) FROM t_feedback WHERE user_id = #{userId} AND status = 'OPEN' "
            + "AND last_message_id IS NOT NULL AND (user_last_read_message_id IS NULL "
            + "OR user_last_read_message_id < last_message_id)")
    int countUserUnread(@Param("userId") Integer userId);

    /** 管理端未读工单数（有反馈回复等新消息的 OPEN 工单）。 */
    @Select("SELECT COUNT(*) FROM t_feedback WHERE status = 'OPEN' AND last_message_id IS NOT NULL "
            + "AND (admin_last_read_message_id IS NULL OR admin_last_read_message_id < last_message_id)")
    int countAdminUnread();

    /** 增量消息：id > afterId，按正序取 limit 条。 */
    @Select("SELECT * FROM t_feedback_message WHERE feedback_id = #{feedbackId} AND id > #{afterId} "
            + "ORDER BY id ASC LIMIT #{limit}")
    List<FeedbackMessage> findMessages(@Param("feedbackId") Integer feedbackId,
                                       @Param("afterId") Long afterId,
                                       @Param("limit") int limit);

    /** 管理员回复条数：反馈只允许回复一次（回复即结单），用它判断是否已经回复过。 */
    @Select("SELECT COUNT(*) FROM t_feedback_message WHERE feedback_id = #{feedbackId} "
            + "AND sender_role = 'admin'")
    int countAdminMessages(@Param("feedbackId") Integer feedbackId);

    /** 幂等查询：同一工单同一 client_msg_id 只应有一条。 */
    @Select("SELECT * FROM t_feedback_message WHERE feedback_id = #{feedbackId} "
            + "AND client_msg_id = #{clientMsgId} LIMIT 1")
    FeedbackMessage findByClientMsgId(@Param("feedbackId") Integer feedbackId,
                                      @Param("clientMsgId") String clientMsgId);

    /** 发消息后同步工单头的"最新消息"三件套（列表排序与轮询都依赖它）。 */
    @Update("UPDATE t_feedback SET last_message_id = #{messageId}, last_message_time = NOW(), "
            + "last_message_preview = #{preview} WHERE id = #{feedbackId}")
    int updateLastMessage(@Param("feedbackId") Integer feedbackId,
                          @Param("messageId") Long messageId,
                          @Param("preview") String preview);

    /** 用户已读水位只进不退。 */
    @Update("UPDATE t_feedback SET user_last_read_message_id = "
            + "GREATEST(COALESCE(user_last_read_message_id, 0), #{messageId}) "
            + "WHERE id = #{feedbackId} AND user_id = #{userId}")
    int updateUserRead(@Param("feedbackId") Integer feedbackId,
                       @Param("userId") Integer userId,
                       @Param("messageId") Long messageId);

    /** 管理端已读水位只进不退。 */
    @Update("UPDATE t_feedback SET admin_last_read_message_id = "
            + "GREATEST(COALESCE(admin_last_read_message_id, 0), #{messageId}) "
            + "WHERE id = #{feedbackId}")
    int updateAdminRead(@Param("feedbackId") Integer feedbackId, @Param("messageId") Long messageId);

    /** 关闭工单：幂等（只有 OPEN 才改）。 */
    @Update("UPDATE t_feedback SET status = 'CLOSED' WHERE id = #{feedbackId} AND status = 'OPEN'")
    int close(@Param("feedbackId") Integer feedbackId);

    /** 记录最近处理人（回复或关闭时写）。 */
    @Update("UPDATE t_feedback SET handled_by = #{adminId} WHERE id = #{feedbackId}")
    int assignHandler(@Param("feedbackId") Integer feedbackId, @Param("adminId") Integer adminId);

    /** 管理端列表（分页交给 PageHelper）：type/status 可选，按最新消息倒序。 */
    @Select("<script>SELECT f.id, f.user_id AS userId, u.username, u.nickname, f.type, f.subject, f.status, "
            + "f.handled_by AS handledBy, h.nickname AS handledByName, f.last_message_id AS lastMessageId, "
            + "f.last_message_preview AS lastMessagePreview, f.last_message_time AS lastMessageTime, "
            + "f.created_time AS createdTime, "
            + "CASE WHEN f.last_message_id IS NOT NULL AND (f.admin_last_read_message_id IS NULL "
            + "     OR f.admin_last_read_message_id &lt; f.last_message_id) THEN 1 ELSE 0 END AS unread "
            + "FROM t_feedback f JOIN t_user u ON u.id = f.user_id "
            + "LEFT JOIN t_user h ON h.id = f.handled_by "
            + "<where>"
            + "<if test='type != null and type != \"\"'>AND f.type = #{type} </if>"
            + "<if test='status != null and status != \"\"'>AND f.status = #{status} </if>"
            + "</where> ORDER BY f.last_message_id DESC, f.id DESC</script>")
    List<FeedbackView> findForAdmin(@Param("type") String type, @Param("status") String status);

    /** 管理端单条（带发起人 / 处理人昵称）。 */
    @Select("SELECT f.id, f.user_id AS userId, u.username, u.nickname, f.type, f.subject, f.status, "
            + "f.handled_by AS handledBy, h.nickname AS handledByName, f.last_message_id AS lastMessageId, "
            + "f.last_message_preview AS lastMessagePreview, f.last_message_time AS lastMessageTime, "
            + "f.created_time AS createdTime, "
            + "CASE WHEN f.last_message_id IS NOT NULL AND (f.admin_last_read_message_id IS NULL "
            + "     OR f.admin_last_read_message_id < f.last_message_id) THEN 1 ELSE 0 END AS unread "
            + "FROM t_feedback f JOIN t_user u ON u.id = f.user_id "
            + "LEFT JOIN t_user h ON h.id = f.handled_by WHERE f.id = #{id}")
    FeedbackView findDetailForAdmin(@Param("id") Integer id);
}
