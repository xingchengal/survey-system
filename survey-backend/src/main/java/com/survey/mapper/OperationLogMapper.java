package com.survey.mapper;

import com.survey.entity.OperationLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 操作日志表（t_operation_log）的数据访问层 —— 审计用途。
 * 写入只有 insert 一个入口（OperationLogService.save，@Async，由 LogAspect 在方法正常返回后调用）。
 * ★ 只提供 insert 与查询，不补 update / delete：审计日志只追加，避免事后篡改记录。
 */
@Mapper
public interface OperationLogMapper {

    /** 插入操作日志。★ 时间字段交给数据库默认值，这里不传。 */
    @Insert("INSERT INTO t_operation_log(user_id, username, role, operation, method, ip) " +
            "VALUES(#{userId}, #{username}, #{role}, #{operation}, #{method}, #{ip})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OperationLog log);

    /**
     * 分页条件查询：用户名/操作类型模糊、角色精确、操作时间范围（闭区间）；分页靠 PageHelper。
     * 时间范围直接用 >= / <= 比列（有 idx_create_time），不要包 DATE()，否则索引失效。
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
     * 导出用：与 {@link #findPageByCondition} 同条件，不分页，固定截断 10001 条。
     * 10001 多取一条只为判断超限，调用方见到就明确拒绝，不静默截断。
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

    /**
     * 总览「最近动态」：只取最近 N 条，不做分页。
     * limit 由调用方写死（不来自请求参数），避免被当成翻页入口。
     */
    @Select("SELECT * FROM t_operation_log ORDER BY create_time DESC LIMIT #{limit}")
    List<OperationLog> findRecent(@Param("limit") int limit);

    /** 操作日志总条数（统计页「日志」模块）。 */
    @Select("SELECT COUNT(*) FROM t_operation_log")
    int count();

    /** 今日操作条数。 */
    @Select("SELECT COUNT(*) FROM t_operation_log WHERE create_time >= CURDATE()")
    int countToday();

    /** 近 7 天每日操作条数；数据库只返回有数据的日子，由 Service 补齐。 */
    @Select("SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d, COUNT(*) AS cnt FROM t_operation_log "
            + "WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) "
            + "GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')")
    List<java.util.Map<String, Object>> countDaily();
}
