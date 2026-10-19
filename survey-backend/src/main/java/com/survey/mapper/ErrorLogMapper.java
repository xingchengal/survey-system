package com.survey.mapper;

import com.survey.entity.ErrorLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 异常日志表（t_error_log）的数据访问层。
 * 写入口只有 insert（GlobalExceptionHandler 与 AiGenerateService 经 ErrorLogService @Async 调用）。
 * ★ deleteById / clearAll / deleteOlderThan 都是管理端清理动作，不可恢复；findPageByCondition / count 只读。
 */
@Mapper
public interface ErrorLogMapper {

    /** 插入异常日志。时间字段由数据库 DEFAULT CURRENT_TIMESTAMP 填，这里不传。 */
    @Insert("INSERT INTO t_error_log(user_id, username, request_uri, request_method, " +
            "error_type, error_message, stack_trace, ip) " +
            "VALUES(#{userId}, #{username}, #{requestUri}, #{requestMethod}, " +
            "#{errorType}, #{errorMessage}, #{stackTrace}, #{ip})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ErrorLog errorLog);

    /** 分页条件查询：关键字同时匹配异常信息 / 请求路径 / 异常类型，分页由调用方 PageHelper 完成。 */
    @Select("<script>" +
            "SELECT * FROM t_error_log WHERE 1=1 " +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND (error_message LIKE CONCAT('%', #{keyword}, '%') " +
            "OR request_uri LIKE CONCAT('%', #{keyword}, '%') " +
            "OR error_type LIKE CONCAT('%', #{keyword}, '%')) " +
            "</if>" +
            "ORDER BY create_time DESC" +
            "</script>")
    List<ErrorLog> findPageByCondition(@Param("keyword") String keyword);

    /** 异常总数（管理端统计卡片）。 */
    @Select("SELECT COUNT(*) FROM t_error_log")
    int count();

    /** 删除单条。管理端清理动作，不可恢复。 */
    @Delete("DELETE FROM t_error_log WHERE id = #{id}")
    int deleteById(Integer id);

    /** 清空全部。管理动作，不可恢复；管理端有二次确认，只开放给管理员。 */
    @Delete("DELETE FROM t_error_log")
    int clearAll();

    /**
     * 删除 create_time 早于 N 天的日志，返回删除行数（保留策略，由 ErrorLogService 定时任务调用）。
     * ★ 只清异常日志，不自动清 t_operation_log：后者是审计证据，何时清理由管理员决定。
     * create_time 上有索引，走范围扫描；参数是 int，不存在注入。
     */
    @Delete("DELETE FROM t_error_log WHERE create_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deleteOlderThan(@Param("days") int days);
}
