package com.survey.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 问卷举报（t_survey_review）的数据访问层。
 * ★ status 只由服务端写：insert 固定 'PENDING'，handle 只允许 PENDING → 终态；
 *   接受前端传 status 等于让举报人自己批"已通过"。
 */
@Mapper
public interface SurveyReviewMapper {

    /** 提交举报。status 固定写 'PENDING'，不接受外部传入。 */
    @Insert("INSERT INTO t_survey_review (survey_id, reporter_id, reason, status) "
            + "VALUES (#{surveyId}, #{reporterId}, #{reason}, 'PENDING')")
    int insert(@Param("surveyId") Integer surveyId,
               @Param("reporterId") Integer reporterId,
               @Param("reason") String reason);

    /** 同一人对同一问卷的待处理举报只能有一条（防刷屏）。 */
    @Select("SELECT COUNT(*) FROM t_survey_review "
            + "WHERE survey_id = #{surveyId} AND reporter_id = #{reporterId} AND status = 'PENDING'")
    int countPending(@Param("surveyId") Integer surveyId, @Param("reporterId") Integer reporterId);

    /**
     * 管理端列表：按状态筛（null = 全部），最近的在前，LIMIT 200 兜底（举报量级不值得上分页器）。
     * 带出 surveyLifecycle：已恢复的记录前端要隐藏，且「恢复上架」只对仍 SUSPENDED 的显示，否则点下去就是 400。
     */
    @Select("<script>SELECT r.id, r.survey_id AS surveyId, r.reporter_id AS reporterId, "
            + "r.reason, r.status, r.action, r.handled_by AS handledBy, r.handled_at AS handledAt, "
            + "r.created_time AS createdTime, s.title AS surveyTitle, s.lifecycle AS surveyLifecycle "
            + "FROM t_survey_review r LEFT JOIN t_survey s ON s.id = r.survey_id "
            + "<where><if test='status != null'>r.status = #{status}</if></where> "
            + "ORDER BY r.id DESC LIMIT 200</script>")
    List<Map<String, Object>> findForAdmin(@Param("status") String status);

    @Select("SELECT id, survey_id AS surveyId, reporter_id AS reporterId, reason, status "
            + "FROM t_survey_review WHERE id = #{id}")
    Map<String, Object> findById(@Param("id") Integer id);

    /** 处理举报。WHERE 带 status='PENDING' 保证幂等：重复处理影响 0 行，不会覆盖已处理结果。 */
    @Update("UPDATE t_survey_review SET status = #{status}, action = #{action}, "
            + "handled_by = #{handledBy}, handled_at = NOW() "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int handle(@Param("id") Integer id, @Param("status") String status,
               @Param("action") String action, @Param("handledBy") Integer handledBy);

    /** 我提交过的举报（最近的在前）。 */
    @Select("SELECT id, survey_id AS surveyId, reason, status, action, "
            + "handled_at AS handledAt, created_time AS createdTime "
            + "FROM t_survey_review WHERE reporter_id = #{reporterId} "
            + "ORDER BY id DESC LIMIT 100")
    List<Map<String, Object>> findByReporter(@Param("reporterId") Integer reporterId);

    /** 全站待处理举报数（管理端待办卡片；不含已通过 / 已驳回）。 */
    @Select("SELECT COUNT(*) FROM t_survey_review WHERE status = 'PENDING'")
    int countPendingAll();

    /** 全站举报按状态分组（PENDING / APPROVED / REJECTED），统计页用。 */
    @Select("SELECT status, COUNT(*) AS cnt FROM t_survey_review GROUP BY status")
    List<Map<String, Object>> countByStatus();

    /**
     * 彻底删除问卷时清空它的举报记录。
     * 问卷行没了，留下的只是审核台里"标题为空"的孤儿行；要保留审计价值应在删除前归档到独立审计表。
     */
    @Delete("DELETE FROM t_survey_review WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}
