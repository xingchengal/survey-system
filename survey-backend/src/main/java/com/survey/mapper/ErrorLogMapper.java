package com.survey.mapper;

import com.survey.entity.ErrorLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 异常日志表（t_error_log）的数据访问层。
 *
 * ── 写入方 ────────────────────────────────────────────
 *   只有 insert 一个入口，由 ErrorLogService.save()（@Async）调用。调用方有两处：
 *     config/GlobalExceptionHandler  → 所有 Controller 抛出的异常
 *     service/AiGenerateService.saveAiErrorLog → AI 调用失败
 *       （注意：AI 失败会先被转成 BizException，所以它**不会**再经全局处理器记第二遍）
 *
 * ── 记录了什么（排查问题用）────────────────────────────
 *   request_uri / request_method : 哪个请求出的错
 *   error_type / error_message   : 异常类名与消息
 *   stack_trace                  : 堆栈（写入前截断到 5000 字符，防止一条日志撑爆表）
 *   user_id / username / ip      : 谁、从哪来的（IP 取自请求头，见 GlobalExceptionHandler）
 *
 * ── 查询 ──────────────────────────────────────────────
 *   findPageByCondition 支持在 异常信息 / 请求路径 / 异常类型 三个字段里模糊搜，
 *   配合 PageHelper 分页（调用方先 startPage）。
 *
 * ── ★ 一个需要注意的联动 ───────────────────────────────
 *   GlobalExceptionHandler 会跳过文档类路径（/doc.html、/v3/api-docs 等）不记录，
 *   否则"文档 404"会每访问一次记一条，形成日志循环。
 */
@Mapper
public interface ErrorLogMapper {

    /** 插入异常日志。★ 时间字段由数据库 DEFAULT CURRENT_TIMESTAMP 填，这里不传。 */
    @Insert("INSERT INTO t_error_log(user_id, username, request_uri, request_method, " +
            "error_type, error_message, stack_trace, ip) " +
            "VALUES(#{userId}, #{username}, #{requestUri}, #{requestMethod}, " +
            "#{errorType}, #{errorMessage}, #{stackTrace}, #{ip})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ErrorLog errorLog);

    /** 分页条件查询（动态 SQL）：关键字同时匹配异常信息 / 请求路径 / 异常类型。 */
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

    /** 删除单条。 */
    @Delete("DELETE FROM t_error_log WHERE id = #{id}")
    int deleteById(Integer id);

    /** 清空全部。★ 不可恢复；管理端有二次确认，只开放给管理员。 */
    @Delete("DELETE FROM t_error_log")
    int clearAll();

    /**
     * 删除 create_time 早于 N 天的日志，返回删除行数。
     *
     * ★ 为什么需要它：t_error_log 单条 stack_trace 最多 5000 字符，若只靠 save() 持续写入、
     *   只有管理员手动 clearAll() —— 长期运行必然膨胀，而且
     *   findPageByCondition 的 LIKE 全表模糊会随之越来越慢。
     *   create_time 上已有索引 idx_create_time（已核对 SHOW INDEX），
     *   所以这条 DELETE 走的是索引范围扫描，不会变成全表扫描。
     *
     * ★ 只清异常日志、**不自动清操作日志（t_operation_log）**：
     *   后者是审计数据（谁在什么时候做了什么），保留价值高于存储成本，
     *   由管理员在日志页面自行决定何时清理。
     *
     * @param days 保留天数（早于 now - days 的记录会被删除）
     */
    @Delete("DELETE FROM t_error_log WHERE create_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int deleteOlderThan(@Param("days") int days);
}
