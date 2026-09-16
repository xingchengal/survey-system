package com.survey.mapper;

import com.survey.entity.OperationLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface OperationLogMapper {

    @Insert("INSERT INTO t_operation_log(user_id, username, role, operation, method, ip) " +
            "VALUES(#{userId}, #{username}, #{role}, #{operation}, #{method}, #{ip})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OperationLog log);

    // 分页查询（支持按用户名 / 操作类型筛选）
    @Select("<script>" +
            "SELECT * FROM t_operation_log WHERE 1=1 " +
            "<if test='username != null and username != \"\"'>" +
            "AND username LIKE CONCAT('%', #{username}, '%') " +
            "</if>" +
            "<if test='operation != null and operation != \"\"'>" +
            "AND operation LIKE CONCAT('%', #{operation}, '%') " +
            "</if>" +
            "ORDER BY create_time DESC" +
            "</script>")
    List<OperationLog> findPageByCondition(@Param("username") String username,
                                           @Param("operation") String operation);

}