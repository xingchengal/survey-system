package com.survey.mapper;

import com.survey.entity.OperationLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 操作日志表（t_operation_log）的数据访问层。
 *
 * ── 写入方 ────────────────────────────────────────────
 *   只有 insert 一个入口，由 OperationLogService.save()（@Async）调用；
 *   触发者是 aspect/LogAspect.java —— 它拦截所有标了 @OperationLog 注解的方法，
 *   在方法**正常返回后**记录一条。
 *
 * ── 记录了什么 ────────────────────────────────────────
 *   user_id / username / role : 谁。
 *   ★ 取值方式：X-User-Id 与 X-User-Role 来自请求头（JwtAuthFilter 注入）；
 *     但 **username 不是从 X-Username 取的** —— 它是拿 userId 反查 t_user 得到的**昵称**
 *     （见 aspect/LogAspect 的取值逻辑；X-Username 虽被注入，但从未被读取）。
 *   operation                 : 注解里的业务描述，如"发布问卷"
 *   method                    : 类名.方法名，如 SurveyController.publish
 *   ip                        : 来源 IP（优先取 X-Forwarded-For，见 LogAspect）
 *
 * ── ★ 审计用途的设计取舍 ───────────────────────────────
 *   这张表**只提供 insert 和分页查询**，没有单条删除/修改接口 ——
 *   审计日志只追加，避免有人事后篡改记录。
 *   （Service 层也一样，只有 save 和 page 两个方法。）
 */
@Mapper
public interface OperationLogMapper {

    /** 插入操作日志。★ 时间字段交给数据库默认值，这里不传。 */
    @Insert("INSERT INTO t_operation_log(user_id, username, role, operation, method, ip) " +
            "VALUES(#{userId}, #{username}, #{role}, #{operation}, #{method}, #{ip})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OperationLog log);

    /**
     * 分页条件查询（动态 SQL）：按用户名、操作类型模糊筛选，按角色精确筛选，
     * 以及**操作时间范围**（闭区间）。
     * 配合 PageHelper 使用（调用方先 startPage，且紧跟这次查询）。
     *
     * ★ 时间范围用 `create_time >= / <=` 直接比列（表上有 `idx_create_time`），
     *   不要包 `DATE(create_time)` —— 那样索引失效，日志表越大越慢。
     *   起止值由 {@link com.survey.common.DateRanges} 归一成 `yyyy-MM-dd 00:00:00` /
     *   `yyyy-MM-dd 23:59:59`。
     * ★ role 只接受 admin / user（Service 侧归一），空 = 不限。
     */
    @Select("<script>" +
            "SELECT * FROM t_operation_log WHERE 1=1 " +
            "<if test='username != null and username != \"\"'>" +
            "AND username LIKE CONCAT('%', #{username}, '%') " +
            "</if>" +
            "<if test='operation != null and operation != \"\"'>" +
            "AND operation LIKE CONCAT('%', #{operation}, '%') " +
            "</if>" +
            "<if test='role != null and role != \"\"'>AND role = #{role} </if>" +
            "<if test='startTime != null'>AND create_time &gt;= #{startTime} </if>" +
            "<if test='endTime != null'>AND create_time &lt;= #{endTime} </if>" +
            "ORDER BY create_time DESC" +
            "</script>")
    List<OperationLog> findPageByCondition(@Param("username") String username,
                                           @Param("operation") String operation,
                                           @Param("role") String role,
                                           @Param("startTime") java.time.LocalDateTime startTime,
                                           @Param("endTime") java.time.LocalDateTime endTime);

    /**
     * 导出用：与 {@link #findPageByCondition} 同一套筛选条件，但**不分页**、固定截断 10001 条。
     *
     * ★ 为什么不共用一条 SQL：PageHelper 只能影响"紧随其后的那一次查询"，
     *   而导出要全量；给公共查询加可空 LIMIT 会让分页那条也变成动态 SQL，风险更大。
     * ★ 10001 = 10000 + 1：多取一条只为判断"是否超过导出上限"，
     *   调用方（OperationLogService.exportLogs）见到 10001 条就明确拒绝，而不是静默截断。
     */
    @Select("<script>" +
            "SELECT * FROM t_operation_log WHERE 1=1 " +
            "<if test='username != null and username != \"\"'>" +
            "AND username LIKE CONCAT('%', #{username}, '%') " +
            "</if>" +
            "<if test='operation != null and operation != \"\"'>" +
            "AND operation LIKE CONCAT('%', #{operation}, '%') " +
            "</if>" +
            "<if test='role != null and role != \"\"'>AND role = #{role} </if>" +
            "<if test='startTime != null'>AND create_time &gt;= #{startTime} </if>" +
            "<if test='endTime != null'>AND create_time &lt;= #{endTime} </if>" +
            "ORDER BY create_time DESC LIMIT 10001" +
            "</script>")
    List<OperationLog> findForExport(@Param("username") String username,
                                     @Param("operation") String operation,
                                     @Param("role") String role,
                                     @Param("startTime") java.time.LocalDateTime startTime,
                                     @Param("endTime") java.time.LocalDateTime endTime);
}
