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
 * 问卷举报（t_survey_review）。
 *
 * ★ 写入口只有 insert 一个：审核状态由 H1-3 的 handle 更新（届时在这里加 updateHandle）。
 * ★ status 只在服务端写死为 'PENDING'，不接受前端传 —— 否则举报人可以直接自封"已通过"。
 */
@Mapper
public interface SurveyReviewMapper {

    @Insert("INSERT INTO t_survey_review (survey_id, reporter_id, reason, status) "
            + "VALUES (#{surveyId}, #{reporterId}, #{reason}, 'PENDING')")
    int insert(@Param("surveyId") Integer surveyId,
               @Param("reporterId") Integer reporterId,
               @Param("reason") String reason);

    /** 同一人对同一问卷"待处理"的举报只能有一条（防刷屏） */
    @Select("SELECT COUNT(*) FROM t_survey_review "
            + "WHERE survey_id = #{surveyId} AND reporter_id = #{reporterId} AND status = 'PENDING'")
    int countPending(@Param("surveyId") Integer surveyId, @Param("reporterId") Integer reporterId);

    /**
     * 管理端列表：按状态筛（null = 全部），最近的在前。
     * ★ LIMIT 200 兜底：举报量级远小于答卷，不值得为它上分页器；
     *   真超过 200 时按状态筛，比"一次返回全部"安全。
     *
     * ★ 带出 surveyLifecycle：已通过的举报里，有的问卷已经被恢复（PUBLISHED）。
     *   前端据此把"已恢复"的记录从待办里隐藏（否则审核页会堆满历史，干扰处理），
     *   同时「恢复上架」按钮也只对仍处于 SUSPENDED 的记录显示 ——
     *   否则点下去就是 400「该问卷当前不是下架状态」。
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

    /** 只允许把 PENDING 改成终态：WHERE 带 status='PENDING' 保证幂等（重复处理影响 0 行） */
    @Update("UPDATE t_survey_review SET status = #{status}, action = #{action}, "
            + "handled_by = #{handledBy}, handled_at = NOW() "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int handle(@Param("id") Integer id, @Param("status") String status,
               @Param("action") String action, @Param("handledBy") Integer handledBy);

    /** 我提交过的举报（最近的在前） */
    @Select("SELECT id, survey_id AS surveyId, reason, status, action, "
            + "handled_at AS handledAt, created_time AS createdTime "
            + "FROM t_survey_review WHERE reporter_id = #{reporterId} "
            + "ORDER BY id DESC LIMIT 100")
    List<Map<String, Object>> findByReporter(@Param("reporterId") Integer reporterId);

    /**
     * 彻底删除问卷时清空它的举报记录。
     * ★ 举报是从属于问卷的记录：问卷行都没了，survey_id 再也关联不到任何东西，
     *   留着只会让审核台列表里出现"标题为空"的孤儿行。
     * ★ 若将来需要"删除后仍可审计"，正确做法是删除前把记录归档到独立的审计表，
     *   而不是在这里留半条记录。
     */
    @Delete("DELETE FROM t_survey_review WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}
