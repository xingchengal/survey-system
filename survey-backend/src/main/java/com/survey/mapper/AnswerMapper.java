package com.survey.mapper;

import com.survey.entity.Answer;
import com.survey.dto.MyAnswerItem;
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

    // 提交答卷（★ 判分结果与表头一起插入：分数字段为 null 表示这份问卷不判分）
    @Insert("INSERT INTO t_answer(survey_id, user_id, total_score, earned_score, is_passed) " +
            "VALUES(#{surveyId}, #{userId}, #{totalScore}, #{earnedScore}, #{isPassed})")
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

    /**
     * 某个用户**近 7 天**每天的答卷数（个人看板的迷你趋势）。
     *
     * ★ 口径：只算这个用户名下、非模板的问卷收到的答卷；按 t_answer.submit_time 的自然日聚合。
     * ★ 返回：List<Map>，每个 Map 形如 { d: '2026-10-18', cnt: 3 }。
     *   日期用 DATE_FORMAT 直接格式化成字符串，避免 JDBC 把 DATE 映射成 java.sql.Date /
     *   LocalDate 时不同驱动返回类型不一致（调用方只需 toString）。
     * ★ 没有答卷的那天不会出现在结果里 —— 补齐 7 天由调用方（Service）负责。
     *
     * @param userId 当前登录用户 id（问卷归属）
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

    /**
     * 全站近 7 天**每天答卷数**（管理端看板趋势图）。
     * ★ 与 countDailyByUserId 同口径（普通问卷、未删除），只是不限 user_id。
     */
    @Select("SELECT DATE_FORMAT(a.submit_time, '%Y-%m-%d') AS d, COUNT(*) AS cnt " +
            "FROM t_answer a JOIN t_survey s ON s.id = a.survey_id " +
            "WHERE s.deleted = 0 AND (s.is_template IS NULL OR s.is_template = 0) " +
            "AND a.submit_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) " +
            "GROUP BY DATE_FORMAT(a.submit_time, '%Y-%m-%d')")
    List<Map<String, Object>> countDailyAll();

    /**
     * 写入质控标记（提交那一刻算好，在**同一事务**里回写）。
     *
     * ★ 只算一次、不重算历史：规则以后调整（比如把"每题 1.5 秒"改成 2 秒）时，
     *   老答卷的标记**保持不变**。否则历史数据会随规则升级"忽有效忽无效"，
     *   用户看到的图表会莫名其妙地变，而且无法解释"为什么上周的统计和这周不一样"。
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

    /**
     * 按"原因组合"分组统计（质控摘要里的原因分布）。
     * ★ 按整串 reasons 分组（而不是拆成单个码）：一条 SQL 就够，
     *   拆成单个码的聚合在 Java 里做（见 `AnswerQualityService.summarize`）。
     */
    @Select("SELECT quality_reasons AS reasons, COUNT(*) AS cnt FROM t_answer "
            + "WHERE survey_id = #{surveyId} AND quality_flag = 1 "
            + "GROUP BY quality_reasons")
    List<Map<String, Object>> countByReasons(@Param("surveyId") Integer surveyId);

    /**
     * 某问卷的答卷列表（**分页**，提交时间倒序）。
     *
     * ★ 排序用 {@code submit_time DESC, id DESC}：同一秒提交的多份答卷
     *   （压测/脚本灌数据时很常见）光按时间排序不稳定，翻页会出现"同一条出现两次 / 漏一条"；
     *   补上自增主键做第二排序键，顺序才是确定的。
     * ★ 走 {@code idx_survey_submit_time (survey_id, submit_time)}：等值过滤 + 排序列在同一个索引里，
     *   不需要回表前的 filesort，也不需要为深度翻页扫全表。
     * ★ 上限由调用方夹紧（{@link com.survey.common.PageParams#MAX_PAGE_SIZE} = 100），
     *   Mapper 不重复校验 —— 唯一入口已有一处口径。
     */
    @Select("SELECT * FROM t_answer WHERE survey_id = #{surveyId} "
            + "ORDER BY submit_time DESC, id DESC LIMIT #{pageSize} OFFSET #{offset}")
    List<Answer> findPageBySurveyId(@Param("surveyId") Integer surveyId,
                                    @Param("offset") long offset,
                                    @Param("pageSize") int pageSize);

    // 根据答卷ID查询
    @Select("SELECT * FROM t_answer WHERE id = #{id}")
    Answer findById(Integer id);

    /**
     * 查登录用户对某问卷**最近一次**答卷（"我的成绩"用）。
     *
     * ★ 为什么取最近一条而不是唯一一条：匿名侧不防重、登录侧有 uk_survey_user 约束
     *   （同一 survey_id + user_id 只能一行），所以正常只会有一条；这里仍按 id DESC 取一条，
     *   是为了在历史脏数据（唯一键上线前）存在时也有确定结果，不抛 TooManyResults。
     */
    @Select("SELECT * FROM t_answer WHERE survey_id = #{surveyId} AND user_id = #{userId} " +
            "ORDER BY id DESC LIMIT 1")
    Answer findLatestBySurveyAndUser(@Param("surveyId") Integer surveyId, @Param("userId") Integer userId);

    /**
     * 「我答过的问卷」**分页列表** —— 一条 JOIN 取回答卷 + 问卷标题/分数/状态。
     *
     * ★ 为什么 JOIN 而不是只查 t_answer：列表要显示问卷标题与"还能不能进"，
     *   只查答卷就得让前端对每条再查一次问卷（N+1）。
     * ★ 排序 `submit_time DESC, id DESC`：同一秒提交多条时顺序才确定（与答卷列表同口径）。
     * ★ 包含已删除/已结束的问卷：历史记录不该因为作者删了问卷就消失，
     *   由前端按 deleted/status 显示并禁用"去填写"。
     * ★ 偏移量由 {@link com.survey.common.PageParams#offset} 算好传入（防溢出/深翻页）。
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

    /** 我答过的问卷总数（分页用）。★ 与 pageByUser 同一个 WHERE，避免总数与列表口径不一致。 */
    @Select("SELECT COUNT(*) FROM t_answer WHERE user_id = #{userId}")
    int countByUser(Integer userId);

    /**
     * 判分摘要（统计页的"平均分 / 及格率"）。
     *
     * ★ 口径：只统计 {@code earned_score IS NOT NULL} 的答卷 —— 普通问卷与判分上线前的历史答卷
     *   不能被算成"考了 0 分"，否则平均分会被历史数据拉垮。
     * ★ 用一条聚合 SQL（走 idx_survey_submit_time 的等值前缀），返回
     *   { cnt / avg_score / pass_cnt }；无已判分答卷时 cnt=0、avg_score=null、pass_cnt=null。
     */
    @Select("<script>SELECT COUNT(*) AS cnt, AVG(earned_score) AS avg_score, " +
            "SUM(CASE WHEN is_passed = 1 THEN 1 ELSE 0 END) AS pass_cnt " +
            "FROM t_answer WHERE survey_id = #{surveyId} AND earned_score IS NOT NULL " +
            "<if test='excludeInvalid'>AND quality_flag = 0</if>" +
            "</script>")
    Map<String, Object> selectScoreSummary(@Param("surveyId") Integer surveyId,
                                           @Param("excludeInvalid") boolean excludeInvalid);

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