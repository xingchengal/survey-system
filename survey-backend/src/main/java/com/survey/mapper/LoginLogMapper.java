package com.survey.mapper;

import com.survey.entity.LoginLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 登录日志表（t_login_log）的数据访问层。
 * ip / user_agent 为 varchar(50) / varchar(500)：调用方必须先按列宽截断，否则 insert 报"数据过长"。
 * 只追加的审计数据：不提供单条 update / delete，唯一例外是按保留期整段清理；查询只按 user_id 取自己。
 * ★ 实体字段名必须叫 createTime：列 create_time 靠下划线转驼峰映射，写成 createdTime 时 MyBatis 静默不填。
 */
@Mapper
public interface LoginLogMapper {

    /** 插一条登录日志。create_time 由数据库默认值 CURRENT_TIMESTAMP 填，这里不写。 */
    @Insert("INSERT INTO t_login_log(user_id, username, role, ip, user_agent, status) "
            + "VALUES(#{userId}, #{username}, #{role}, #{ip}, #{userAgent}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LoginLog log);

    /**
     * 某用户最近的登录记录（个人中心「最近登录时间 / IP」）。
     * ★ limit 必须由 Service 夹紧，不能把 HTTP 参数直接透传成 LIMIT。
     */
    @Select("SELECT * FROM t_login_log WHERE user_id = #{userId} "
            + "ORDER BY create_time DESC, id DESC LIMIT #{limit}")
    List<LoginLog> findRecentByUser(@Param("userId") Integer userId, @Param("limit") int limit);

    /**
     * 删除早于 now - days 天的登录记录，返回删除行数（保留策略，由 LoginLogService 定时任务调用）。
     * 只清本表、不碰 t_operation_log：后者是审计证据删了查不回来，本表历史行没有读取方。
     * 走 idx_create_time 范围扫描；参数是 int，Service 已保证 > 0，不存在注入。
     */
    @Delete("DELETE FROM t_login_log WHERE create_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deleteOlderThan(@Param("days") int days);
}
