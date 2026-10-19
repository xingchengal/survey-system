package com.survey.mapper;

import com.survey.entity.Answer;
import com.survey.dto.MyAnswerItem;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 答卷主表（t_answer）的数据访问层 —— 一次提交一行，每题明细在 t_answer_detail。
 * 匿名提交时 user_id 为 NULL；统计"收到多少份"用本表，"某题选了什么"用明细表。
 * ★ 批量统计必须走 countBySurveyIds：Java 里逐份调用单条版就是 N+1（200 份问卷 = 200+ 条 SQL）。
 */
@Mapper
public interface AnswerMapper {

    /** 提交答卷。判分结果与表头一起插入：分数字段为 null 表示这份问卷不判分。 */
    @Insert("INSERT INTO t_answer(survey_id, user_id, total_score, earned_score, is_passed) " +
            "VALUES(#{surveyId}, #{userId}, #{totalScore}, #{earnedScore}, #{isPassed})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Answer answer);

    /** 某问卷的答卷数（只查一份时用；多份走 {@link #countBySurveyIds}）。 */
    @Select("SELECT COUNT(*) FROM t_answer WHERE survey_id = #{surveyId}")
    int countBySurveyId(Integer surveyId);

    /**
     * 批量统计多份问卷各自的答卷数，用来消除 N+1：一次 GROUP BY，SQL 条数恒为 1。
     * 返回 { sid, cnt }，别名全小写以避开 JDBC 对列标签大小写的差异。
     * @param ids 问卷 id 列表（不能为空，空列表会让 IN () 变语法错误）
     */
    @Select("<script>" +
            "SELECT survey_id AS sid, COUNT(*) AS cnt FROM t_answer " +
            "WHERE survey_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "GROUP BY survey_id" +
            "</script>")
    List<Map<String, Object>> countBySurveyIds(@Param("ids") List<Integer> ids);

    /**
     * 某用户近 7 天每天的答卷数（个人看板趋势）。
     * 只算其名下非模板问卷；日期用 DATE_FORMAT 成字符串，避开驱动对 DATE 映射不一致。没有答卷的日期不出现，由 Service 补 7 天。
     */
    @Select("SELECT DATE_FORMAT(a.submit_time, '%Y-%m-%d') AS d, COUNT(*) AS cnt " +
            "FROM t_answer a JOIN t_survey s ON s.id = a.survey_id " +
            "WHERE s.user_id = #{userId} AND (s.is_template IS NULL OR s.is_template = 0) " +
            "AND s.deleted = 0 " +
            "AND a.submit_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) " +
            "GROUP BY DATE_FORMAT(a.submit_time, '%Y-%m-%d')")
    List<Map<String, Object>> countDailyByUserId(@Param("userId") Integer userId);

    /** 今日全站答卷数（普通问卷、未删除；与系统总览的 responses 同口径） */
    @Select("SELECT COUNT(*) FROM t_answer a JOIN t_survey s ON s.id = a.survey_id " +
            "WHERE s.deleted = 0 AND (s.is_template IS NULL OR s.is_template = 0) " +
            "AND a.submit_time >= CURDATE()")
    int countToday();

    /** 全站近 7 天每天答卷数。与 countDailyByUserId 同口径（普通问卷、未删除），只是不限 user_id。 */
    @Select("SELECT DATE_FORMAT(a.submit_time, '%Y-%m-%d') AS d, COUNT(*) AS cnt " +
            "FROM t_answer a JOIN t_survey s ON s.id = a.survey_id " +
            "WHERE s.deleted = 0 AND (s.is_template IS NULL OR s.is_template = 0) " +
            "AND a.submit_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) " +
            "GROUP BY DATE_FORMAT(a.submit_time, '%Y-%m-%d')")
    List<Map<String, Object>> countDailyAll();

    /**
     * 回写质控标记（提交时同一事务内算好）。
     * 只算一次、不重算历史：规则以后调整时老答卷标记不变，否则历史统计会随规则升级忽高忽低。
     */
    @Update("UPDATE t_answer SET duration_ms = #{durationMs}, quality_flag = #{flag}, quality_reasons = #{reasons} "
            + "WHERE id = #{id}")
    int updateQuality(@Param("id") Integer id,
                      @Param("durationMs") Integer durationMs,
                      @Param("flag") int flag,
                      @Param("reasons") String reasons);

    /** 某问卷的可疑答卷数（质控摘要用）。 */
    @Select("SELECT COUNT(*) FROM t_answer WHERE survey_id = #{surveyId} AND quality_flag = 1")
    int countSuspiciousBySurveyId(Integer surveyId);

    /** 可疑答卷样本（最近的在前，最多 50 条）。 */
    @Select("SELECT * FROM t_answer WHERE survey_id = #{surveyId} AND quality_flag = 1 "
            + "ORDER BY submit_time DESC LIMIT 50")
    List<Answer> findSuspiciousBySurveyId(Integer surveyId);

    /** 按原因组合分组统计（质控摘要的原因分布）。整串 reasons 分组，拆单个码由 Java 做。 */
    @Select("SELECT quality_reasons AS reasons, COUNT(*) AS cnt FROM t_answer "
            + "WHERE survey_id = #{surveyId} AND quality_flag = 1 "
            + "GROUP BY quality_reasons")
    List<Map<String, Object>> countByReasons(@Param("surveyId") Integer surveyId);

    /**
     * 某问卷的答卷分页（提交时间倒序）。
     * 排序必须带 id DESC：同秒提交的答卷只按时间排不稳定，翻页会重复/漏条。
     * LIMIT 上限由调用方 PageParams 夹紧，Mapper 不重复校验。
     */
    @Select("SELECT * FROM t_answer WHERE survey_id = #{surveyId} "
            + "ORDER BY submit_time DESC, id DESC LIMIT #{pageSize} OFFSET #{offset}")
    List<Answer> findPageBySurveyId(@Param("surveyId") Integer surveyId,
                                    @Param("offset") long offset,
                                    @Param("pageSize") int pageSize);

    /** 按 id 查答卷。 */
    @Select("SELECT * FROM t_answer WHERE id = #{id}")
    Answer findById(Integer id);

    /**
     * 登录用户对某问卷最近一次答卷（"我的成绩"）。
     * 正常只会有一条（登录侧有 uk_survey_user）；仍 LIMIT 1 是为了历史脏数据存在时也有确定结果。
     */
    @Select("SELECT * FROM t_answer WHERE survey_id = #{surveyId} AND user_id = #{userId} " +
            "ORDER BY id DESC LIMIT 1")
    Answer findLatestBySurveyAndUser(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId);

    /**
     * 「我答过的问卷」分页：一条 JOIN 取回答卷 + 问卷标题/分数/状态，避免逐条再查问卷。
     * 排序带 id DESC 保证同秒提交时顺序确定；包含已删除/已结束问卷，由前端显示并禁用"去填写"。
     * @param offset 由 common.PageParams.offset 算好传入（防溢出/深翻页）
     */
    @Select("SELECT a.id AS answer_id, a.survey_id, s.title AS survey_title, a.submit_time, " +
            "a.total_score, a.earned_score, a.is_passed, s.pass_score, s.grade_enabled, " +
            "s.share_code, s.status, s.deleted " +
            "FROM t_answer a JOIN t_survey s ON s.id = a.survey_id " +
            "WHERE a.user_id = #{userId} " +
            "ORDER BY a.submit_time DESC, a.id DESC " +
            "LIMIT #{pageSize} OFFSET #{offset}")
    List<MyAnswerItem> pageByUser(@Param("userId") Integer userId,
                                  @Param("offset") long offset,
                                  @Param("pageSize") int pageSize);

    /** 我答过的问卷总数（分页用）。与 pageByUser 同一 WHERE 口径，否则总数与列表对不上。 */
    @Select("SELECT COUNT(*) FROM t_answer WHERE user_id = #{userId}")
    int countByUser(Integer userId);

    /**
     * 签到 / 取消签到。checked_in_at 用 IF 一句写完：取消时同时清空时间，避免"未签到却有签到时间"。
     * 只改这一行，不动答卷内容与分数。
     */
    @Update("UPDATE t_answer SET checked_in = #{checkedIn}, " +
            "checked_in_at = IF(#{checkedIn} = 1, NOW(), NULL) WHERE id = #{id}")
    int updateCheckedIn(@Param("id") Integer id, @Param("checkedIn") int checkedIn);

    /** 报名名单：按接龙顺序（提交时间正序）取前 N 份。与答卷列表的倒序相反，要的是"谁先报的"。 */
    @Select("SELECT * FROM t_answer WHERE survey_id = #{surveyId} " +
            "ORDER BY submit_time ASC, id ASC LIMIT #{limit}")
    List<Answer> findFirstBySurveyId(@Param("surveyId") Integer surveyId, @Param("limit") int limit);

    /** 批量取某题在若干答卷里的回答（报名名单用，避免 N+1）。返回 { aid, content }。 */
    @Select("<script>SELECT answer_id AS aid, content FROM t_answer_detail " +
            "WHERE question_id = #{questionId} AND answer_id IN " +
            "<foreach collection='answerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Map<String, Object>> findContentsByAnswerIds(@Param("questionId") Integer questionId,
                                                      @Param("answerIds") List<Integer> answerIds);

    /**
     * 判分摘要（平均分 / 及格率）。只统计 earned_score IS NOT NULL 的答卷 ——
     * 普通问卷与判分上线前的历史答卷不能被算成"考了 0 分"，否则平均分被拉垮。
     */
    @Select("<script>SELECT COUNT(*) AS cnt, AVG(earned_score) AS avg_score, " +
            "SUM(CASE WHEN is_passed = 1 THEN 1 ELSE 0 END) AS pass_cnt " +
            "FROM t_answer WHERE survey_id = #{surveyId} AND earned_score IS NOT NULL " +
            "<if test='excludeInvalid'>AND quality_flag = 0</if>" +
            "</script>")
    Map<String, Object> selectScoreSummary(@Param("surveyId") Integer surveyId,
                                           @Param("excludeInvalid") boolean excludeInvalid);

    /**
     * 查该用户对这份问卷已交过几份 —— 登录态防重用（AnswerService.submit 只在 userId 非空时调用）。
     * user_id = NULL 永远不成立，所以匿名分支必须写 IS NULL，不能拿 0 代替；匿名本身不查重。
     * @param userId 答题人 id；null = 匿名（此时按 user_id IS NULL 统计）
     * @return 已有答卷份数（0 = 还没答过）
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM t_answer WHERE survey_id = #{surveyId} " +
            "<choose>" +
            "<when test='userId != null'>AND user_id = #{userId}</when>" +
            "<otherwise>AND user_id IS NULL</otherwise>" +
            "</choose>" +
            "</script>")
    int countBySurveyAndUser(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId);

    /** 删除某问卷的全部答卷（级联删除用）。 */
    @Delete("DELETE FROM t_answer WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}