package com.survey.mapper;

import com.survey.entity.Answer;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 答卷主表（t_answer）的数据访问层 —— 一次提交对应一行。
 *
 * ── 表结构要点 ────────────────────────────────────────
 *   survey_id  : 答的哪份问卷
 *   user_id    : 答题人。★ 匿名答题时为 NULL（凭分享码提交，不要求登录）
 *   submit_time: 提交时间，数据库默认值填充
 *
 * ── 与 t_answer_detail 的关系 ─────────────────────────
 *   t_answer      = 一次提交（表头）
 *   t_answer_detail = 这次提交里每题一行（表体）
 *   所以统计"答卷数"用这张表，统计"某题选了什么"用明细表。
 *
 * ── ★ 单条统计 vs 批量统计（性能关键）──────────────────
 *   countBySurveyId  : 只查一份问卷时用（/api/answer/count/{surveyId}）
 *   countBySurveyIds : **批量版**，一次 GROUP BY 拿到多份问卷的答卷数。
 *     若在 Java 里 for 循环调用单条版就是 N+1（200 份问卷 = 200+ 条 SQL）；
 *     统一走批量版后，SQL 条数恒为 1。
 */
@Mapper
public interface AnswerMapper {

    // 提交答卷
    @Insert("INSERT INTO t_answer(survey_id, user_id) VALUES(#{surveyId}, #{userId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Answer answer);

    // 统计某问卷的答卷数量（只查一份问卷时用这个）
    @Select("SELECT COUNT(*) FROM t_answer WHERE survey_id = #{surveyId}")
    int countBySurveyId(Integer surveyId);

    /**
     * ★ 批量统计多份问卷各自的答卷数 —— 专门用来消除 N+1 查询。
     *
     * 背景：getUserStatistics() / getSystemOverview() 若在 Java 里逐份调用
     *      countBySurveyId(s.getId())，200 份问卷就是 200+ 条 SQL。
     *      改成一次 GROUP BY 之后，SQL 条数恒为 1。
     *
     * 返回：List<Map>，每个 Map 形如 { sid: 问卷id, cnt: 答卷数 }
     *      别名故意用全小写的 sid / cnt，避免不同 JDBC 驱动对列标签大小写处理不一致。
     *
     * @param ids 问卷 id 列表（调用方需保证非空，空列表会让 IN () 变成语法错误）
     */
    @Select("<script>" +
            "SELECT survey_id AS sid, COUNT(*) AS cnt FROM t_answer " +
            "WHERE survey_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY survey_id" +
            "</script>")
    List<Map<String, Object>> countBySurveyIds(@Param("ids") List<Integer> ids);

    // 根据问卷ID查询所有答卷
    @Select("SELECT * FROM t_answer WHERE survey_id = #{surveyId} ORDER BY submit_time DESC")
    List<Answer> findBySurveyId(Integer surveyId);

    // 根据答卷ID查询
    @Select("SELECT * FROM t_answer WHERE id = #{id}")
    Answer findById(Integer id);

    /**
     * 查"这个（登录）用户对这份问卷已经交过几份答卷"—— 用于**答卷防重**，
     * 见 {@code AnswerService.submit} 里那道守卫。
     *
     * ── 为什么 user_id 要分两条分支写 ────────────────────
     *   SQL 里 `user_id = NULL` **永远是 NULL（不成立）**，不是"等于空"。
     *   所以匿名（userId 为 null）不能走 `= #{userId}`，必须写 `IS NULL`。
     *   这里用 MyBatis 的 `<choose>` 显式分成两条 —— 而不是在 Java 里把 null 换成 0：
     *   库里匿名答卷的 user_id 就是 **NULL**（见 t_answer 的表结构注释），
     *   若拿 0 去查会永远查不到，防重对匿名侧会静默失效（那种"看起来加了守卫其实没生效"最难查）。
     *
     * @param surveyId 问卷 id
     * @param userId   答题人 id；**null = 匿名**（此时按 `user_id IS NULL` 统计）
     * @return 已有的答卷份数（0 = 还没答过）
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM t_answer WHERE survey_id = #{surveyId} " +
            "<choose>" +
            "<when test='userId != null'>AND user_id = #{userId}</when>" +
            "<otherwise>AND user_id IS NULL</otherwise>" +
            "</choose>" +
            "</script>")
    int countBySurveyAndUser(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId);

    // 根据问卷ID删除所有答卷
    @Delete("DELETE FROM t_answer WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}