package com.survey.mapper;

import com.survey.entity.Survey;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 问卷表（t_survey）的数据访问层 —— 全项目最核心的一张表。
 *
 * ── 表结构要点 ────────────────────────────────────────
 *   status      : 0=草稿   1=已发布   2=已结束
 *   deleted     : 0=正常   1=在回收站（配合 deleted_by 记录"谁删的"）
 *   share_code  : 唯一索引；由服务端生成 6 位码（SurveyService.generateShareCode）
 *   is_template : 0=普通问卷  1=模板（模板的 user_id 通常是 0，属于公共资产）
 *   start_time / end_time : 定时任务据此自动发布 / 自动关闭
 *
 * ── 两条贯穿全表的约定 ─────────────────────────────────
 *   1. 普通查询几乎都带 `deleted = 0` —— 回收站里的问卷不能被正常列表带出来；
 *   2. "用户自己的问卷"一律按 user_id 过滤；归属判断在 Service/Controller 层做，不在这里做。
 *
 * ── 为什么有些方法要写 @Results ────────────────────────
 *   项目开了 map-underscore-to-camel-case（share_code → shareCode 自动映射），
 *   但四个回收站查询**额外 JOIN 了 t_user 取删除者昵称**，列名 deleted_name 在实体里
 *   叫 deletedByName，自动映射对不上，所以显式指定。
 *   另外我自己加了全小写别名的批量统计（在 AnswerMapper），不必写 @Results。
 */
@Mapper
public interface SurveyMapper {

    /**
     * 新建问卷。
     * ★ user_id / view_count 的兜底：view_count 用 IFNULL 防止传 null 进库；
     *   deleted 固定写 0（新问卷必然不在回收站）。
     * ★ useGeneratedKeys：把自增主键回填到 survey.id，调用方拿到后可以接着插题目。
     */
    @Insert("INSERT INTO t_survey(user_id, title, description, status, share_code, deleted, " +
            "start_time, end_time, is_template, template_category, view_count) " +
            "VALUES(#{userId}, #{title}, #{description}, #{status}, #{shareCode}, 0, " +
            "#{startTime}, #{endTime}, #{isTemplate}, #{templateCategory}, " +
            "IFNULL(#{viewCount}, 0))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Survey survey);

    /**
     * 移入回收站（逻辑删除）：deleted=1，记下操作人和删除时间。
     * ★ deleted_at 是回收站列表要展示的"什么时候删的"；显式写 NOW()，
     *   不依赖 updated_time 的 ON UPDATE 推断（虽然此刻两者相等）。
     */
    @Update("UPDATE t_survey SET deleted = 1, deleted_by = #{userId}, deleted_at = NOW(), lifecycle = 'ARCHIVED' " +
            "WHERE id = #{id}")
    int moveToRecycle(@Param("id") Integer id, @Param("userId") Integer userId);

    /** 从回收站恢复：deleted=0，清掉操作人信息与删除时间。 */
    @Update("UPDATE t_survey SET deleted = 0, deleted_by = NULL, deleted_at = NULL, "
            + "lifecycle = CASE WHEN status = 1 THEN 'PUBLISHED' WHEN status = 2 THEN 'ENDED' ELSE 'DRAFT' END "
            + "WHERE id = #{id}")
    int restoreFromRecycle(Integer id);

    /** 物理删除。★ 不级联删题目/答卷，优先走回收站那条路径。 */
    @Delete("DELETE FROM t_survey WHERE id = #{id}")
    int deleteById(Integer id);

    /**
     * 更新问卷的**可编辑字段**：标题 / 描述 / 开始时间 / 结束时间 + 问卷设置
     * （答题密码 / 限量 / 感谢语 / 跳转链接 / 公开结果）。
     *
     * ★★ SET 列表里**故意不含**这些字段（不变量由 SQL 本身保证）：
     *     · user_id    → 归属只在创建时确定，任何更新都不该改它（连"转移问卷"都不提供）
     *     · status     → 状态机由 publish() / updateStatusAndShareCode() / 定时任务 / 回收站专用 SQL 管
     *     · share_code → 由 generateShareCode() 与发布路径独占
     *     · deleted / is_template / view_count → 各有专用入口
     *
     * ★ 为什么必须这么写：静态 SQL 会把"没赋值的字段写成 NULL"——
     *   若 UPDATE 覆盖 status / share_code，"只改标题"的局部提交就能把 status 写成 NULL，
     *   那份问卷从此**谁也答不了**。靠"在 Controller 里手工回填旧值"补救是不牢靠的：
     *   那条约束只写在注释里，谁忘了写、或以后新加一个调用方，就会重演一次事故。
     *   现在从 SQL 层就碰不到这几个字段 —— **忘了回填也不会出错**，约束由代码保证，不再依赖谁记得看注释。
     *
     * ★ 它仍是静态 SQL（不是动态更新）：标题/描述/起止时间/设置传 null 会写成 NULL。
     *   这是**有意保留**的语义 —— 起止时间需要能被清空（前端把时间输入框清掉时传 null）；
     *   答题密码 / 限量 / 感谢语 / 跳转链接同理（清空 = 关闭该功能）。
     *   public_result 用 IFNULL 兜底成 0，避免旧客户端不传时把"公开结果"写成 NULL。
     */
    @Update("UPDATE t_survey SET title=#{title}, description=#{description}, " +
            "start_time=#{startTime}, end_time=#{endTime}, " +
            "access_password=#{accessPassword}, max_responses=#{maxResponses}, " +
            "thank_text=#{thankText}, redirect_url=#{redirectUrl}, " +
            "public_result=IFNULL(#{publicResult}, 0), ip_limit=IFNULL(#{ipLimit}, 0), " +
            "grade_enabled=IFNULL(#{gradeEnabled}, 0), pass_score=#{passScore} WHERE id=#{id}")
    int update(Survey survey);

    /**
     * 只把「最后更新时间」推到当前时刻。
     * ★ 用于"没改 t_survey 字段、但问卷内容变了"的入口：保存题目、版本回滚
     *   （updated_time 是 ON UPDATE CURRENT_TIMESTAMP，不碰那一行就不会变）。
     */
    @Update("UPDATE t_survey SET updated_time = NOW() WHERE id = #{id}")
    int touchUpdatedTime(@Param("id") Integer id);

    /**
     * 只更新**状态与分享码** —— 供发布路径使用（publish / 自动发布 / 自动关闭）。
     *
     * ★ 为什么单独开一个方法：{@link #update(Survey)} 已从 SQL 层拿掉 status 与 share_code，
     *   而状态机确实需要改它们。把"谁能改状态"收敛到这一个方法后，
     *   调用点一眼可数（grep updateStatusAndShareCode 即可），
     *   比"人人都能通过 update 顺手改状态"安全得多。
     */
    /** 只改业务生命周期（举报下架用；status/share_code 都不动） */
    @Update("UPDATE t_survey SET lifecycle = #{lifecycle} WHERE id = #{id}")
    int updateLifecycle(@Param("id") Integer id, @Param("lifecycle") String lifecycle);

    @Update("UPDATE t_survey SET status = #{status}, share_code = #{shareCode}, "
            + "lifecycle = CASE WHEN #{status} = 1 THEN 'PUBLISHED' WHEN #{status} = 2 THEN 'ENDED' ELSE 'DRAFT' END "
            + "WHERE id = #{id}")
    int updateStatusAndShareCode(@Param("id") Integer id,
                                 @Param("status") Integer status,
                                 @Param("shareCode") String shareCode);

    /**
     * 直接设置某份问卷的访问量。
     * ★ 为什么需要单独一个方法：上面的 update() **刻意不包含 view_count**
     *   （归属和计数不能被一次普通更新改掉），所以演示数据生成完毕后
     *   想回填访问量只能走这里。
     * ★ 关联：DemoDataController.init() —— 造完答卷后按"答卷数 + 少量未提交访问"回填。
     */
    @Update("UPDATE t_survey SET view_count = #{viewCount} WHERE id = #{id}")
    int updateViewCount(@Param("id") Integer id, @Param("viewCount") Integer viewCount);

    /** 全部正常问卷（含模板），管理员统计用。 */
    @Select("SELECT * FROM t_survey WHERE deleted = 0 ORDER BY created_time DESC")
    List<Survey> findAll();

    /**
     * 某个用户的问卷（「我的问卷」页，不含回收站）。
     * ★ **排除模板** —— 与 SurveyService.getUserStatistics() 口径一致。
     *   若这里不过滤 is_template，而统计侧排除了模板，就会出现
     *   "我的问卷"里能看到自己设成模板的那几份、个人统计的 total 却不含它们，两处数字对不上。
     */
    @Select("SELECT * FROM t_survey WHERE user_id = #{userId} AND deleted = 0 " +
            "AND (is_template IS NULL OR is_template = 0) ORDER BY created_time DESC")
    List<Survey> findByUserId(Integer userId);

    // 注：不分页的回收站查询及其 Service 调用方已删除，回收站统一走下面两个**分页版**方法
    //   （keyword 传 null 时等价于不分页，SQL 的 <if> 会跳过模糊条件）。

    /**
     * 按 id 查问卷（★ **不限 deleted**：回收站里的问卷也能查到）。
     *
     * ★ 归属校验的基础：拿到 user_id 才能和登录用户比对。
     * ★★ 调用方**必须自己处理 deleted** —— 这是本方法最容易踩的坑：
     *    · 回收站 / 恢复 / 彻底删除这三个入口**需要**读到 deleted = 1 的行，所以这里不能加过滤；
     *    · 但"让用户读问卷内容"的路径（GET /api/survey/{id}、GET /api/question/list/{id}）
     *      就必须判 deleted：因为 moveToRecycle **只改 deleted、不改 status**，
     *      若只看"已发布即可读"，一份已发布问卷被删进回收站后仍会对所有登录用户可见。
     *      这条口径现在由 common/OwnershipGuard.requireVisible 统一兜住。
     *    ★ 对照：findByShareCode 是"给外部打开"的路径，所以它自己就带了 deleted = 0。
     */
    @Select("SELECT * FROM t_survey WHERE id = #{id}")
    Survey findById(Integer id);

    /**
     * 按分享码查（匿名访客打开分享链接）。
     * ★ 带 deleted = 0：回收站里的问卷即使分享码还在，也不该能被打开。
     */
    @Select("SELECT * FROM t_survey WHERE share_code = #{shareCode} AND deleted = 0")
    Survey findByShareCode(String shareCode);

    /**
     * 管理员问卷列表：分页 + 关键字 + 状态 + **创建时间范围**筛选（动态 SQL）。
     * ★ 排除模板（is_template 为 1 的不出现在"全部问卷"里）。
     * ★ <if> 里判断 keyword 非空才拼 LIKE，避免传空串时全表匹配。
     * ★ 时间范围是**左闭右闭**，且用 `>= / <=` 直接比 datetime 列（不用 DATE(created_time)）：
     *   列上有索引（`t_survey(created_time)`），包一层函数会让索引失效、变成全表扫。
     *   起止值由 SurveyService 归一成 `yyyy-MM-dd 00:00:00` / `yyyy-MM-dd 23:59:59`。
     * ★ 分页靠 PageHelper：调用方必须先 startPage，且紧跟这次查询。
     * ★ 参数超过 4 个之后不再用位置顺序，全部显式 @Param —— 少一个名字就会在运行期报绑定错误。
     */
    @Select("<script>" +
            "SELECT * FROM t_survey WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0) " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (title LIKE CONCAT('%', #{keyword}, '%') " +
            "OR description LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "<if test='status != null'>" +
            // ★ status = 0（草稿）时必须**同时收 status IS NULL**：
            //   全项目口径是"NULL 视为草稿"（common/SurveyStatus.isDraft），而 SQL 里
            //   `NULL = 0` 的结果是 NULL（非真），严格相等会把那批历史数据漏掉：
            //   列表页把它显示成"草稿"，点"草稿"筛选却查不到。
            //   （同款修法见本文件的 findPendingPublish：定时任务那条早已放宽为 IS NULL OR = 0。）
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
     * 导出用：与 {@link #findPageByCondition} 同一套筛选条件，但**不分页**、固定截断 10001 条。
     *
     * ★ 为什么不共用一条 SQL：PageHelper 只能影响"紧随其后的那一次查询"，
     *   而导出要全量；给公共查询加可空 LIMIT 会让分页那条也变成动态 SQL，风险更大。
     * ★ 10001 = 10000 + 1：多取一条只为判断"是否超过导出上限"，
     *   调用方（SurveyService.exportSurveys）见到 10001 条就明确拒绝，而不是静默截断。
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

    /**
     * 某用户的全部问卷，**含回收站里的**。
     * ★ 专门给 deleteUser() 用 —— 删用户时要连他回收站里的问卷一起清掉，
     *   否则会出现"用户没了、回收站还挂着他的问卷"的孤儿数据。
     */
    @Select("SELECT * FROM t_survey WHERE user_id = #{userId}")
    List<Survey> findByUserIdIncludeDeleted(@Param("userId") Integer userId);

    // ★ 注意：这里**没有**"排除模板"的专用查询，别再加 ——
    //   "排除模板"的口径集中在 SurveyService（先取全量、再在 Java 里按 is_template 过滤，
    //   见 countByStatus 附近）。多一份 SQL 口径就迟早只改一处。

    // ===== 模板 =====

    /**
     * 模板广场列表：is_template = 1，可按标题/分类搜索。
     * ★ 它同时被 TemplateSeederService 用来做幂等判断 —— 那里判的是
     *   **"是否已有预置模板（user_id = 0）"**，而不是"是否存在任意模板"。
     * ★ ORDER BY template_use_count DESC, id ASC：热门模板排在前面（模板广场与模板管理共用），
     *   使用次数相同的按插入顺序，保证列表稳定。
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

    /**
     * 模板被使用一次（从模板创建成功时调用）。
     * ★ 用数据库自增而不是"读出来 +1 再写回"：两个用户同时套用同一模板时不会互相覆盖。
     * ★ 取消模板后再设回模板，计数**保留**：历史热度不应该因为身份变化而清零。
     */
    @Update("UPDATE t_survey SET template_use_count = template_use_count + 1 WHERE id = #{id}")
    int incrementTemplateUseCount(@Param("id") Integer id);

    /** 设为模板 / 取消模板，并写模板分类（管理员操作，Controller 已校验角色）。 */
    @Update("UPDATE t_survey SET is_template = #{isTemplate}, template_category = #{category} WHERE id = #{id}")
    int setTemplate(@Param("id") Integer id,
                    @Param("isTemplate") Integer isTemplate,
                    @Param("category") String category);

    // ===== 访问次数 =====

    /**
     * 访客打开分享链接时访问量 +1。
     * ★ 用 view_count = view_count + 1 让数据库自己加，避免"读出来再写回去"的并发覆盖问题。
     */
    @Update("UPDATE t_survey SET view_count = IFNULL(view_count, 0) + 1 " +
            "WHERE share_code = #{shareCode} AND deleted = 0 " +
            "AND (is_template IS NULL OR is_template = 0)")
    int incrementViewCount(String shareCode);

    /**
     * 全站访客次数合计（管理员看板「访客次数」卡片）。
     * ★ 必须排除模板：若只写 deleted = 0，模板的 view_count 也会被算进来。
     *   当前模板的 view_count 恰好是 0 所以看不出问题，但这属于"碰巧对"，
     *   一旦以后给模板写入访问量（或复制问卷时带过来），数字就会莫名偏高。
     */
    @Select("SELECT IFNULL(SUM(view_count), 0) FROM t_survey " +
            "WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0)")
    int sumViewCount();

    // ===== 管理端系统总览的补充计数 =====

    /** 模板总数（模板广场在架模板） */
    @Select("SELECT COUNT(*) FROM t_survey WHERE is_template = 1 AND deleted = 0")
    int countTemplates();

    /** 回收站里的问卷数（含模板；管理员看板要看到"待清理"的量） */
    @Select("SELECT COUNT(*) FROM t_survey WHERE deleted = 1")
    int countRecycled();

    /** 今日新建的普通问卷数（不含模板、不含回收站） */
    @Select("SELECT COUNT(*) FROM t_survey WHERE deleted = 0 " +
            "AND (is_template IS NULL OR is_template = 0) AND created_time >= CURDATE()")
    int countCreatedToday();

    /**
     * 全站近 7 天**每天新建的普通问卷数**（看板趋势图）。
     * ★ 返回 { d, cnt }，没有新建的那天不出现在结果里，补齐 7 天由 Service 负责。
     */
    @Select("SELECT DATE_FORMAT(created_time, '%Y-%m-%d') AS d, COUNT(*) AS cnt " +
            "FROM t_survey WHERE deleted = 0 AND (is_template IS NULL OR is_template = 0) " +
            "AND created_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) " +
            "GROUP BY DATE_FORMAT(created_time, '%Y-%m-%d')")
    List<Map<String, Object>> countDailyCreated();

    // ===== 定时任务用的两个查询 =====

    /**
     * 该自动发布的：还是草稿 且已到开始时间。
     * ★ 条件必须是 `(status IS NULL OR status = 0)`，不能只认 0。
     *   项目口径是"**NULL 视为草稿**"（SurveyStatus.isDraft）：若这里只认 0，
     *   库里那些 status 为 NULL 的问卷**永远不会被定时任务自动发布**（静默失效，没有任何报错）。
     *   （NULL 的来源：旧版 update 是无条件覆盖 status 的静态 UPDATE，一次"只改标题"的
     *   局部提交就会把它写成 NULL；新产生的已在 Controller 侧堵住。）
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
