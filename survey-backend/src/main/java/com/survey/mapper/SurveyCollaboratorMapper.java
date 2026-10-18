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
 * 问卷协作者（t_survey_collaborator）的读写。
 *
 * ── 权限口径 ──────────────────────────────────────────
 *   本表是"某人对某份问卷的**局部**授权"，与 t_role 那套全局角色不是一回事：
 *   · 谁能在**哪份**问卷上做什么 → 这里（collaborator_role）；
 *   · 这个账号**全局**能做什么 → t_user_role / t_role_permission。
 *   判定顺序：先能力（@RequirePermission / SurveyAccessService），再看这张表。
 *
 * ── 为什么读列表要 JOIN t_user ────────────────────────
 *   前端要显示"谁被邀请了"（昵称/账号），只存 user_id 就得 N+1 次查询。
 *   ★ 过滤 `u.deleted_at IS NULL`：已注销账号不再出现在协作者列表里。
 *     （已注销的人本来就过不了 JwtAuthFilter，留着他的授权只会让列表看起来脏。）
 */
@Mapper
public interface SurveyCollaboratorMapper {

    /**
     * 取某人在某份问卷上的协作者角色；不是协作者返回 null。
     * ★ LIMIT 1：唯一键 uk_survey_user 已保证最多一行，这里只是防御性写法。
     */
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
     * 共享给我的问卷（我作为协作者参与、且不在回收站）。
     * ★ 只按 user_id 过滤，不接受"查别人"的参数 —— 这类"我的"列表永远不该能越权查。
     * ★ 返回整行 Survey（含 access_password），**由 Service 负责脱敏**（置空并给出 hasPassword）。
     */
    @Select("SELECT s.* FROM t_survey s "
            + "JOIN t_survey_collaborator c ON c.survey_id = s.id "
            + "WHERE c.user_id = #{userId} AND s.deleted = 0 "
            + "ORDER BY c.created_time DESC, s.id DESC")
    List<Survey> findSharedSurveys(@Param("userId") Integer userId);

    /**
     * 批量数"每份问卷有几个协作者"（给"我的问卷"列表打标）。
     * ★ 一次 GROUP BY 而不是逐条 COUNT：列表里可能有几十份问卷，N+1 会拖慢首屏。
     */
    @Select("<script>SELECT survey_id AS surveyId, COUNT(*) AS total FROM t_survey_collaborator "
            + "WHERE survey_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY survey_id</script>")
    List<Map<String, Object>> countBySurveyIds(@Param("ids") List<Integer> ids);

    /**
     * 某份问卷当前有几个协作者（邀请前判断是否已超过免费名额）。
     * ★ 只用于"名额收费"的判定；并发下两个邀请可能都读到 2，属于可接受的轻微超发
     *   （最多多一个免费名额），不影响扣费幂等。
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
}
