package com.survey.mapper;

import com.survey.dto.CollaboratorView;
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
 * 列表 JOIN t_user 取昵称/账号，避免 N+1；并过滤 u.deleted_at IS NULL，已注销账号不再出现在协作者列表。
 */
@Mapper
public interface SurveyCollaboratorMapper {

    /** 某人在某份问卷上的协作者角色；不是协作者返回 null。LIMIT 1 只是防御（唯一键已保证至多一行）。 */
    @Select("SELECT collaborator_role FROM t_survey_collaborator "
            + "WHERE survey_id = #{surveyId} AND user_id = #{userId} LIMIT 1")
    String findCollaboratorRole(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId);

    /** 某份问卷的协作者列表（含昵称；已注销账号不列出） */
    @Select("SELECT c.user_id AS userId, u.username AS username, u.nickname AS nickname, "
            + "c.collaborator_role AS collaboratorRole, c.created_by AS createdBy, c.created_time AS createdTime "
            + "FROM t_survey_collaborator c JOIN t_user u ON u.id = c.user_id "
            + "WHERE c.survey_id = #{surveyId} AND u.deleted_at IS NULL "
            + "ORDER BY c.created_time, c.id")
    List<CollaboratorView> listBySurveyId(@Param("surveyId") Integer surveyId);

    /**
     * 共享给我的问卷（我作为协作者且问卷不在回收站）。
     * 只按 user_id 过滤，不接受"查别人"的参数；返回整行 Survey 含 access_password，必须由 Service 脱敏。
     * 额外 JOIN 作者昵称/账号、我的角色、共享时间，供前端按作者分组与按角色筛选。
     */
    @Select("SELECT s.*, u.username AS ownerUsername, u.nickname AS ownerNickname, "
            + "c.collaborator_role AS myCollaboratorRole, c.created_time AS sharedTime "
            + "FROM t_survey s "
            + "JOIN t_survey_collaborator c ON c.survey_id = s.id "
            + "JOIN t_user u ON u.id = s.user_id "
            + "WHERE c.user_id = #{userId} AND s.deleted = 0 "
            + "ORDER BY u.nickname, c.created_time DESC, s.id DESC")
    List<Survey> findSharedSurveys(@Param("userId") Integer userId);

    /** 批量数"每份问卷有几个协作者"（我的问卷打标）。一次 GROUP BY，避免逐条 COUNT 的 N+1。 */
    @Select("<script>SELECT survey_id AS surveyId, COUNT(*) AS total FROM t_survey_collaborator "
            + "WHERE survey_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY survey_id</script>")
    List<Map<String, Object>> countBySurveyIds(@Param("ids") List<Integer> ids);

    /**
     * 某份问卷当前协作者数（邀请前判断是否超过免费名额）。
     * 并发下两个邀请可能都读到旧值，最多多一个免费名额，不影响扣费幂等。
     */
    @Select("SELECT COUNT(*) FROM t_survey_collaborator WHERE survey_id = #{surveyId}")
    int countBySurvey(@Param("surveyId") Integer surveyId);

    @Insert("INSERT INTO t_survey_collaborator (survey_id, user_id, collaborator_role, created_by) "
            + "VALUES (#{surveyId}, #{userId}, #{collaboratorRole}, #{createdBy})")
    int insert(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId,
               @Param("collaboratorRole") String collaboratorRole, @Param("createdBy") Integer createdBy);

    @Update("UPDATE t_survey_collaborator SET collaborator_role = #{collaboratorRole} "
            + "WHERE survey_id = #{surveyId} AND user_id = #{userId}")
    int updateRole(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId,
                   @Param("collaboratorRole") String collaboratorRole);

    @Delete("DELETE FROM t_survey_collaborator WHERE survey_id = #{surveyId} AND user_id = #{userId}")
    int delete(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId);

    /** 彻底删除问卷时清空全部协作者（否则「共享给我的问卷」会残留幽灵条目）。 */
    @Delete("DELETE FROM t_survey_collaborator WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}
