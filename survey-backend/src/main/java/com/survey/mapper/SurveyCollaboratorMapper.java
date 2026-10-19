package com.survey.mapper;

import com.survey.dto.CollaboratorView;
import com.survey.dto.InvitationView;
import com.survey.entity.Survey;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 问卷协作者（t_survey_collaborator）的读写 —— 本表是"某人在某份问卷上的局部授权"。
 * 全局能力看 t_user_role / t_role_permission，本表只管"哪份问卷能做什么"；判定时先能力、后本表。
 * ★ 邀请状态只认 ACCEPTED：PENDING / REJECTED / REVOKED 都不是协作者，任何权限查询都不得把它们算进来。
 * ★ 列表 JOIN t_user 取昵称/账号，避免 N+1；并过滤 u.deleted_at IS NULL，已注销账号不再出现在协作者列表。
 */
@Mapper
public interface SurveyCollaboratorMapper {

    /** 某人在某份问卷上**已生效**的协作者角色；不是已生效协作者返回 null（PENDING 等一律不算）。 */
    @Select("SELECT collaborator_role FROM t_survey_collaborator "
            + "WHERE survey_id = #{surveyId} AND user_id = #{userId} AND status = 'ACCEPTED' LIMIT 1")
    String findCollaboratorRole(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId);

    /**
     * 某人在某份问卷上的邀请状态（任意状态；没有记录返回 null）。
     * ★ 只用于邀请/重置判断，**不能**拿它当权限：权限一律走 {@link #findCollaboratorRole}。
     */
    @Select("SELECT status FROM t_survey_collaborator "
            + "WHERE survey_id = #{surveyId} AND user_id = #{userId} LIMIT 1")
    String findCollaboratorStatus(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId);

    /** 某份问卷的协作者名单：只列待接受 + 已生效（拒绝/撤销是历史，不再展示）。 */
    @Select("SELECT c.user_id AS userId, u.username AS username, u.nickname AS nickname, "
            + "c.collaborator_role AS collaboratorRole, c.status AS status, "
            + "c.created_by AS createdBy, c.created_time AS createdTime "
            + "FROM t_survey_collaborator c JOIN t_user u ON u.id = c.user_id "
            + "WHERE c.survey_id = #{surveyId} AND u.deleted_at IS NULL "
            + "AND c.status IN ('PENDING', 'ACCEPTED') "
            + "ORDER BY c.created_time, c.id")
    List<CollaboratorView> listBySurveyId(@Param("surveyId") Integer surveyId);

    /**
     * 共享给我的问卷（我作为协作者且问卷不在回收站）。
     * ★ 只认 ACCEPTED：待接受/被拒绝/被撤销的邀请不能提前看到问卷。
     * 只按 user_id 过滤，不接受"查别人"的参数；返回整行 Survey 含 access_password，必须由 Service 脱敏。
     * 额外 JOIN 作者昵称/账号、我的角色、共享时间，供前端按作者分组与按角色筛选。
     */
    @Select("SELECT s.*, u.username AS ownerUsername, u.nickname AS ownerNickname, "
            + "c.collaborator_role AS myCollaboratorRole, c.created_time AS sharedTime "
            + "FROM t_survey s "
            + "JOIN t_survey_collaborator c ON c.survey_id = s.id "
            + "JOIN t_user u ON u.id = s.user_id "
            + "WHERE c.user_id = #{userId} AND c.status = 'ACCEPTED' AND s.deleted = 0 "
            + "ORDER BY u.nickname, c.created_time DESC, s.id DESC")
    List<Survey> findSharedSurveys(@Param("userId") Integer userId);

    /** 我的待接受邀请（只按当前用户；问卷进回收站后不再出现）。 */
    @Select("SELECT c.id, c.user_id AS userId, c.survey_id AS surveyId, s.title AS surveyTitle, "
            + "s.user_id AS ownerId, u.username AS ownerUsername, u.nickname AS ownerNickname, "
            + "c.collaborator_role AS collaboratorRole, c.status AS status, c.created_time AS createdTime "
            + "FROM t_survey_collaborator c "
            + "JOIN t_survey s ON s.id = c.survey_id "
            + "JOIN t_user u ON u.id = s.user_id "
            + "WHERE c.user_id = #{userId} AND c.status = 'PENDING' AND s.deleted = 0 "
            + "ORDER BY c.created_time DESC, c.id DESC")
    List<InvitationView> findPendingInvitationsByUserId(@Param("userId") Integer userId);

    /** 按邀请行 id 查（接受/拒绝用；问卷已进回收站视为失效 → null）。 */
    @Select("SELECT c.id, c.user_id AS userId, c.survey_id AS surveyId, s.title AS surveyTitle, "
            + "s.user_id AS ownerId, u.username AS ownerUsername, u.nickname AS ownerNickname, "
            + "c.collaborator_role AS collaboratorRole, c.status AS status, c.created_time AS createdTime "
            + "FROM t_survey_collaborator c "
            + "JOIN t_survey s ON s.id = c.survey_id AND s.deleted = 0 "
            + "JOIN t_user u ON u.id = s.user_id "
            + "WHERE c.id = #{id}")
    InvitationView findInvitationById(@Param("id") Integer id);

    /** 批量数"每份问卷有几个**已生效**协作者"（我的问卷打标）。一次 GROUP BY，避免逐条 COUNT 的 N+1。 */
    @Select("<script>SELECT survey_id AS surveyId, COUNT(*) AS total FROM t_survey_collaborator "
            + "WHERE status = 'ACCEPTED' AND survey_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY survey_id</script>")
    List<Map<String, Object>> countBySurveyIds(@Param("ids") List<Integer> ids);

    /**
     * 某份问卷当前**已生效**协作者数（接受邀请前判断是否超过免费名额）。
     * 只数 ACCEPTED：待接受不占名额，拒绝/撤销后自然释放。
     */
    @Select("SELECT COUNT(*) FROM t_survey_collaborator WHERE survey_id = #{surveyId} AND status = 'ACCEPTED'")
    int countBySurvey(@Param("surveyId") Integer surveyId);

    /** 新邀请一律 PENDING；接受后才变 ACCEPTED。 */
    @Insert("INSERT INTO t_survey_collaborator (survey_id, user_id, collaborator_role, status, created_by) "
            + "VALUES (#{surveyId}, #{userId}, #{collaboratorRole}, 'PENDING', #{createdBy})")
    int insert(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId,
               @Param("collaboratorRole") String collaboratorRole, @Param("createdBy") Integer createdBy);

    /**
     * 再次邀请被拒绝/已撤销的人：把同一行重置为 PENDING（不新增行，唯一键不变）。
     * 条件里带 status：并发下已被接受/处理的邀请不会被覆盖。
     */
    @Update("UPDATE t_survey_collaborator SET collaborator_role = #{collaboratorRole}, status = 'PENDING', "
            + "created_by = #{createdBy}, created_time = NOW() "
            + "WHERE survey_id = #{surveyId} AND user_id = #{userId} AND status IN ('REJECTED', 'REVOKED')")
    int resetToPending(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId,
                       @Param("collaboratorRole") String collaboratorRole, @Param("createdBy") Integer createdBy);

    /** 条件更新状态：只有 fromStatus 才改，防止重复接受/拒绝互相覆盖。 */
    @Update("UPDATE t_survey_collaborator SET status = #{toStatus} "
            + "WHERE id = #{id} AND status = #{fromStatus}")
    int updateStatus(@Param("id") Integer id, @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus);

    /** 作者撤销未接受的邀请：只改 PENDING → REVOKED。 */
    @Update("UPDATE t_survey_collaborator SET status = 'REVOKED' "
            + "WHERE survey_id = #{surveyId} AND user_id = #{userId} AND status = 'PENDING'")
    int revokePending(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId);

    @Update("UPDATE t_survey_collaborator SET collaborator_role = #{collaboratorRole} "
            + "WHERE survey_id = #{surveyId} AND user_id = #{userId} AND status = 'ACCEPTED'")
    int updateRole(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId,
                   @Param("collaboratorRole") String collaboratorRole);

    @Delete("DELETE FROM t_survey_collaborator WHERE survey_id = #{surveyId} AND user_id = #{userId}")
    int delete(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId);

    /** 彻底删除问卷时清空全部协作者（否则「共享给我的问卷」会残留幽灵条目）。 */
    @Delete("DELETE FROM t_survey_collaborator WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}
