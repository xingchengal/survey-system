package com.survey.mapper;

import com.survey.entity.Survey;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 问卷表（t_survey）的数据访问层。
 * status：0=草稿（NULL 同义）/ 1=已发布 / 2=已结束；deleted：0=正常 / 1=回收站。
 * ★ 普通查询一律带 `deleted = 0`：回收站里的问卷不能被正常列表带出来；"我的问卷"另按 user_id 过滤。
 * 回收站查询 JOIN t_user 取删除者昵称，deleted_name 与实体 deletedByName 自动映射对不上，故显式 @Results。
 */
@Mapper
public interface SurveyMapper {

    /**
     * 新建问卷。useGeneratedKeys 回填 id，调用方拿到后可接着插题目；view_count 用 IFNULL 兜底 0。
     * ★ 签到核销 / 公开报名名单也要在这里落库：场景模板（接龙、报名签到）创建时就要带上开关，
     *   只靠 update 的话新建问卷永远是关闭状态。
     */
    @Insert("INSERT INTO t_survey(user_id, title, description, status, share_code, deleted, " +
            "start_time, end_time, is_template, template_category, view_count, " +
            "checkin_enabled, show_participants) " +
            "VALUES(#{userId}, #{title}, #{description}, #{status}, #{shareCode}, 0, " +
            "#{startTime}, #{endTime}, #{isTemplate}, #{templateCategory}, IFNULL(#{viewCount}, 0), " +
            "IFNULL(#{checkinEnabled}, 0), IFNULL(#{showParticipants}, 0))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Survey survey);

    /** 移入回收站：deleted=1 并记下操作人。deleted_at 必须显式写 NOW()，不靠 updated_time 推断。 */
    @Update("UPDATE t_survey SET deleted = 1, deleted_by = #{userId}, deleted_at = NOW(), lifecycle = 'ARCHIVED' " +
            "WHERE id = #{id}")
    int moveToRecycle(@Param("id") Integer id, @Param("userId") Integer userId);

    /** 从回收站恢复：deleted=0，deleted_by / deleted_at 一并清空；lifecycle 按 status 还原。 */
    @Update("UPDATE t_survey SET deleted = 0, deleted_by = NULL, deleted_at = NULL, "
            + "lifecycle = CASE WHEN status = 1 THEN 'PUBLISHED' WHEN status = 2 THEN 'ENDED' ELSE 'DRAFT' END "
            + "WHERE id = #{id}")
    int restoreFromRecycle(Integer id);

    /** 物理删除，不级联删题目/答卷 —— 需要连带清数据时走回收站路径。 */
    @Delete("DELETE FROM t_survey WHERE id = #{id}")
    int deleteById(Integer id);

    /**
     * 更新可编辑字段（标题/描述/起止时间/问卷设置）。
     * ★ SET 列表故意不含 user_id / status / share_code / deleted / is_template / view_count：
     *   静态 UPDATE 会把未赋值列写成 NULL，覆盖 status / share_code 会让问卷再也答不了。
     * 传 null 即落成 NULL —— 起止时间与密码等"清空"依赖此语义；布尔项用 IFNULL 兜底 0。
     */
    @Update("UPDATE t_survey SET title=#{title}, description=#{description}, " +
            "start_time=#{startTime}, end_time=#{endTime}, " +
            "access_password=#{accessPassword}, max_responses=#{maxResponses}, " +
            "thank_text=#{thankText}, redirect_url=#{redirectUrl}, " +
            "public_result=IFNULL(#{publicResult}, 0), ip_limit=IFNULL(#{ipLimit}, 0), " +
            "grade_enabled=IFNULL(#{gradeEnabled}, 0), pass_score=#{passScore}, " +
            "time_limit_seconds=#{timeLimitSeconds}, " +
            "show_answer_after_submit=IFNULL(#{showAnswerAfterSubmit}, 0), " +
            "require_login=IFNULL(#{requireLogin}, 0), " +
            "checkin_enabled=IFNULL(#{checkinEnabled}, 0), " +
            "show_participants=IFNULL(#{showParticipants}, 0) WHERE id=#{id}")
    int update(Survey survey);

    /** 只把 updated_time 推到当前时刻（保存题目、版本回滚这类不改本表字段的入口用）。 */
    @Update("UPDATE t_survey SET updated_time = NOW() WHERE id = #{id}")
    int touchUpdatedTime(@Param("id") Integer id);

    /** 只改业务生命周期（举报下架用；status/share_code 都不动） */
    @Update("UPDATE t_survey SET lifecycle = #{lifecycle} WHERE id = #{id}")
    int updateLifecycle(@Param("id") Integer id, @Param("lifecycle") String lifecycle);

    /** 只更新状态与分享码 —— 发布/自动关闭专用入口；update() 刻意不带这两列，状态只能从这里改。 */
    @Update("UPDATE t_survey SET status = #{status}, share_code = #{shareCode}, "
            + "lifecycle = CASE WHEN #{status} = 1 THEN 'PUBLISHED' WHEN #{status} = 2 THEN 'ENDED' ELSE 'DRAFT' END "
            + "WHERE id = #{id}")
    int updateStatusAndShareCode(@Param("id") Integer id,
                                 @Param("status") Integer status,
                                 @Param("shareCode") String shareCode);

    /** 直接设置访问量。update() 刻意不含 view_count，回填演示数据只能走这里。 */
    @Update("UPDATE t_survey SET view_count = #{viewCount} WHERE id = #{id}")
    int updateViewCount(@Param("id") Integer id, @Param("viewCount") Integer viewCount);

    /** 统计口径：与 getSystemOverview() 同源，排除模板，否则答卷量分布/趋势会混进模板。 */
    @Select("SELECT * FROM t_survey WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0) " +
            "ORDER BY created_time DESC")
    List<Survey> findNormal();

    /** 某用户的问卷（我的问卷，不含回收站）。必须与个人统计同口径排除模板，否则两处数字对不上。 */
    @Select("SELECT * FROM t_survey WHERE user_id = #{userId} AND deleted = 0 " +
            "AND (is_template IS NULL OR is_template = 0) ORDER BY created_time DESC")
    List<Survey> findByUserId(Integer userId);

    /**
     * 按 id 查，不过滤 deleted（回收站/恢复/彻底删除需要读到已删行）。
     * 调用方必须自己判 deleted：moveToRecycle 只改 deleted、不改 status，只看"已发布"会把回收站问卷放出去。
     */
    @Select("SELECT * FROM t_survey WHERE id = #{id}")
    Survey findById(Integer id);

    /** 按分享码查（匿名打开）。必须带 deleted = 0：回收站问卷即使码还在也不能被打开。 */
    @Select("SELECT * FROM t_survey WHERE share_code = #{shareCode} AND deleted = 0")
    Survey findByShareCode(String shareCode);

    /**
     * 管理员问卷列表：分页 + 关键字/状态/创建时间范围筛选（动态 SQL），排除模板。
     * status = 0 时必须同时收 status IS NULL：全项目口径是"NULL 视为草稿"，否则那批数据查不到。
     * 时间范围左闭右闭，直接用 >= / <= 比列（列上有索引，包 DATE() 会全表扫）；分页靠 PageHelper。
     */
    @Select("<script>" +
            "SELECT * FROM t_survey WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0) " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (title LIKE CONCAT('%', #{keyword}, '%') " +
            "OR description LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='status != null'>" +
            // status = 0 时必须同时收 status IS NULL：`NULL = 0` 不为真，否则"NULL 视为草稿"那批查不到
            "AND (status = #{status} " +
            "<if test='status == 0'>OR status IS NULL</if>) " +
            "</if>" +
            "<if test='startTime != null'>AND created_time &gt;= #{startTime} </if>" +
            "<if test='endTime != null'>AND created_time &lt;= #{endTime} </if>" +
            "ORDER BY created_time DESC" +
            "</script>")
    List<Survey> findPageByCondition(@Param("keyword") String keyword,
                                     @Param("status") Integer status,
                                     @Param("startTime") java.time.LocalDateTime startTime,
                                     @Param("endTime") java.time.LocalDateTime endTime);

    /**
     * 导出用：与 {@link #findPageByCondition} 同条件，不分页，固定截断 10001 条。
     * 10001 = 10000 + 1：多取一条只为判断超限，调用方见到 10001 条就明确拒绝，不静默截断。
     */
    @Select("<script>" +
            "SELECT * FROM t_survey WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0) " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (title LIKE CONCAT('%', #{keyword}, '%') " +
            "OR description LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='status != null'>" +
            "AND (status = #{status} " +
            "<if test='status == 0'>OR status IS NULL</if>) " +
            "</if>" +
            "<if test='startTime != null'>AND created_time &gt;= #{startTime} </if>" +
            "<if test='endTime != null'>AND created_time &lt;= #{endTime} </if>" +
            "ORDER BY created_time DESC LIMIT 10001" +
            "</script>")
    List<Survey> findForExport(@Param("keyword") String keyword,
                               @Param("status") Integer status,
                               @Param("startTime") java.time.LocalDateTime startTime,
                               @Param("endTime") java.time.LocalDateTime endTime);

    /** 某用户的全部问卷，含回收站。删除用户时用：否则会留下"用户没了、回收站还挂着他问卷"的孤儿数据。 */
    @Select("SELECT * FROM t_survey WHERE user_id = #{userId}")
    List<Survey> findByUserIdIncludeDeleted(@Param("userId") Integer userId);

    // 排除模板的口径集中在 SurveyService，不要再加专用 SQL，否则两处口径迟早只改一处。

    // ===== 模板 =====

    /**
     * 模板广场列表：is_template = 1，可按标题/分类搜索；按使用次数倒序，次数相同按 id 保证顺序稳定。
     * 它也被 TemplateSeederService 用于幂等判断（那里判"是否已有 user_id = 0 的预置模板"）。
     */
    @Select("<script>" +
            "SELECT * FROM t_survey WHERE is_template = 1 AND deleted = 0 " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (title LIKE CONCAT('%', #{keyword}, '%') " +
            "OR template_category LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "ORDER BY template_use_count DESC, id ASC" +
            "</script>")
    List<Survey> findTemplates(@Param("keyword") String keyword);

    /** 模板被使用一次。用数据库自增而非读出来 +1 写回，并发套用同一模板不会互相覆盖；取消模板后计数保留。 */
    @Update("UPDATE t_survey SET template_use_count = template_use_count + 1 WHERE id = #{id}")
    int incrementTemplateUseCount(@Param("id") Integer id);

    /** 设为模板 / 取消模板，并写模板分类（管理员操作，Controller 已校验角色）。 */
    @Update("UPDATE t_survey SET is_template = #{isTemplate}, template_category = #{category} WHERE id = #{id}")
    int setTemplate(@Param("id") Integer id,
                    @Param("isTemplate") Integer isTemplate,
                    @Param("category") String category);

    // ===== 访问次数 =====

    /** 访客打开分享链接时访问量 +1。用数据库自增，避免"读出来再写回"的并发覆盖。 */
    @Update("UPDATE t_survey SET view_count = IFNULL(view_count, 0) + 1 " +
            "WHERE share_code = #{shareCode} AND deleted = 0 " +
            "AND (is_template IS NULL OR is_template = 0)")
    int incrementViewCount(String shareCode);

    /** 全站访客次数合计。必须排除模板：只写 deleted = 0 会把模板的 view_count 也算进来。 */
    @Select("SELECT IFNULL(SUM(view_count), 0) FROM t_survey " +
            "WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0)")
    int sumViewCount();

    // ===== 管理端系统总览的补充计数 =====

    /** 模板总数（模板广场在架模板） */
    @Select("SELECT COUNT(*) FROM t_survey WHERE is_template = 1 AND deleted = 0")
    int countTemplates();

    /** 模板使用榜：按「从模板创建」的次数倒序，同分按 id 保证顺序稳定。 */
    @Select("SELECT title, template_use_count AS useCount FROM t_survey "
            + "WHERE is_template = 1 AND deleted = 0 "
            + "ORDER BY template_use_count DESC, id LIMIT #{limit}")
    List<Map<String, Object>> findTopTemplates(@Param("limit") int limit);

    /** 回收站里的问卷数（含模板；管理员看板要看到"待清理"的量） */
    @Select("SELECT COUNT(*) FROM t_survey WHERE deleted = 1")
    int countRecycled();

    /** 今日新建的普通问卷数（不含模板、不含回收站） */
    @Select("SELECT COUNT(*) FROM t_survey WHERE deleted = 0 " +
            "AND (is_template IS NULL OR is_template = 0) AND created_time >= CURDATE()")
    int countCreatedToday();

    /** 全站近 7 天每天新建的普通问卷数。返回 { d, cnt }，没有新建的日期不出现，补齐 7 天由 Service 负责。 */
    @Select("SELECT DATE_FORMAT(created_time, '%Y-%m-%d') AS d, COUNT(*) AS cnt " +
            "FROM t_survey WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0) " +
            "AND created_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) " +
            "GROUP BY DATE_FORMAT(created_time, '%Y-%m-%d')")
    List<Map<String, Object>> countDailyCreated();

    // ===== 定时任务用的两个查询 =====

    /**
     * 该自动发布的：仍是草稿且已到开始时间。
     * 条件必须是 (status IS NULL OR status = 0)：只认 0 会让 status 为 NULL 的问卷永远不被自动发布（静默失效）。
     */
    @Select("SELECT * FROM t_survey WHERE (status IS NULL OR status = 0) AND deleted = 0 " +
            "AND start_time IS NOT NULL AND start_time <= NOW()")
    List<Survey> findPendingPublish();

    /** 该自动关闭的：已发布(status=1) 且已过结束时间。 */
    @Select("SELECT * FROM t_survey WHERE status = 1 AND deleted = 0 " +
            "AND end_time IS NOT NULL AND end_time <= NOW()")
    List<Survey> findPendingClose();

    // ===== 回收站分页 =====

    /** 某用户回收站分页（Recycle.vue 普通用户视角），带删除者昵称 + 标题搜索。 */
    @Select("<script>" +
            "SELECT s.*, u.nickname AS deleted_name FROM t_survey s " +
            "LEFT JOIN t_user u ON s.deleted_by = u.id " +
            "WHERE s.deleted = 1 AND s.user_id = #{userId} " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND s.title LIKE CONCAT('%', #{keyword}, '%') " +
            "</if>" +
            "ORDER BY s.created_time DESC" +
            "</script>")
    @Results({
            @Result(property = "deletedByName", column = "deleted_name"),
            @Result(property = "deletedBy", column = "deleted_by"),
            @Result(property = "shareCode", column = "share_code"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "startTime", column = "start_time"),
            @Result(property = "endTime", column = "end_time"),
            @Result(property = "viewCount", column = "view_count"),
            @Result(property = "deletedAt", column = "deleted_at"),
            @Result(property = "createdTime", column = "created_time"),
            @Result(property = "updatedTime", column = "updated_time")
    })
    List<Survey> findRecyclePageByUser(@Param("userId") Integer userId,
                                       @Param("keyword") String keyword);

    /** 全部回收站分页（Recycle.vue 管理员视角），带删除者昵称 + 标题搜索。 */
    @Select("<script>" +
            "SELECT s.*, u.nickname AS deleted_name FROM t_survey s " +
            "LEFT JOIN t_user u ON s.deleted_by = u.id " +
            "WHERE s.deleted = 1 " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND s.title LIKE CONCAT('%', #{keyword}, '%') " +
            "</if>" +
            "ORDER BY s.created_time DESC" +
            "</script>")
    @Results({
            @Result(property = "deletedByName", column = "deleted_name"),
            @Result(property = "deletedBy", column = "deleted_by"),
            @Result(property = "shareCode", column = "share_code"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "startTime", column = "start_time"),
            @Result(property = "endTime", column = "end_time"),
            @Result(property = "viewCount", column = "view_count"),
            @Result(property = "deletedAt", column = "deleted_at"),
            @Result(property = "createdTime", column = "created_time"),
            @Result(property = "updatedTime", column = "updated_time")
    })
    List<Survey> findRecyclePageAll(@Param("keyword") String keyword);
}
