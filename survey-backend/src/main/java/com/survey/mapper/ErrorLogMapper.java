package com.survey.mapper;

import com.survey.entity.ErrorLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ErrorLogMapper {

    @Insert("INSERT INTO t_error_log(user_id, username, request_uri, request_method, " +
            "error_type, error_message, stack_trace, ip) " +
            "VALUES(#{userId}, #{username}, #{requestUri}, #{requestMethod}, " +
            "#{errorType}, #{errorMessage}, #{stackTrace}, #{ip})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ErrorLog errorLog);

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

    @Select("SELECT COUNT(*) FROM t_error_log")
    int count();

    @Delete("DELETE FROM t_error_log WHERE id = #{id}")
    int deleteById(Integer id);

    @Delete("DELETE FROM t_error_log")
    int clearAll();
}