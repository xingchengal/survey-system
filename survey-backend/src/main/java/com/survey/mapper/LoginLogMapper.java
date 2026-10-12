package com.survey.mapper;

import com.survey.entity.LoginLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 登录日志表（t_login_log）的数据访问层。
 *
 * ── 表结构要点 ────────────────────────────────────────
 *   ip          : varchar(50)，与 t_operation_log.ip 同列宽 —— 调用方必须**先截断**
 *   user_agent  : varchar(500) —— 调用方必须**先截断**（浏览器 UA 常见 120~200 字符，
 *                 但客户端可以随便发一个超长的，直接 insert 会因"数据过长"失败）
 *   idx_user_id : (user_id, create_time) 复合索引，正好覆盖"某用户最近 N 次登录"的查询
 *                 （ORDER BY create_time DESC LIMIT n 走这条索引，不会全表扫）
 *   status      : 1=成功 / 0=失败 —— 本 Mapper 的**写入方当前固定传 1**（见 LoginLog 类注释）
 *
 * ── 两个注意点 ────────────────────────────────────────
 *   1. 这里**没有** update/delete：登录日志与 t_operation_log / t_error_log 一样是
 *      "只追加"的审计数据，不提供单条修改与删除，避免事后篡改（整表清理在 DB 层做）；
 *   2. 查询一律 `WHERE user_id = ?` —— 个人中心的"最近登录"**只能看自己的**，
 *      没有"按用户名查别人登录记录"的接口（管理端也不需要，见端点审计表）。
 */
@Mapper
public interface LoginLogMapper {

    /**
     * 插一条登录日志。create_time 由数据库默认值 CURRENT_TIMESTAMP 填，
     * 所以这里不写它（少一列就少一处"应用时区与数据库时区不一致"的隐患）。
     */
    @Insert("INSERT INTO t_login_log(user_id, username, role, ip, user_agent, status) "
            + "VALUES(#{userId}, #{username}, #{role}, #{ip}, #{userAgent}, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LoginLog log);

    /**
     * 查某个用户最近的登录记录（个人中心「最近登录时间 / IP」用）。
     * ★ LIMIT 由调用方给定并**在 Service 里夹紧**（不能把 limit 直接交给 HTTP 参数）。
     */
    @Select("SELECT * FROM t_login_log WHERE user_id = #{userId} "
            + "ORDER BY create_time DESC, id DESC LIMIT #{limit}")
    List<LoginLog> findRecentByUser(@Param("userId") Integer userId, @Param("limit") int limit);
}
