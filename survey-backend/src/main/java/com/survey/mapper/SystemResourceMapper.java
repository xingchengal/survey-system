package com.survey.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 系统资源维度（只读）：数据库体积、表数量、主要业务表行数、版本快照占用。
 *
 * ★ 只查 information_schema 与 COUNT(*)，不写任何数据；`TABLE_ROWS` 在 InnoDB 是估算值，
 *   所以行数用精确 COUNT（主要业务表只有 14 张，数据量级也撑得住）。
 * ★ 体积字段来自 information_schema.TABLES（DATA_LENGTH + INDEX_LENGTH），单位字节。
 */
@Mapper
public interface SystemResourceMapper {

    /** 当前库的数据 + 索引总大小（字节）。 */
    @Select("SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA = DATABASE()")
    long sumDatabaseBytes();

    /** 当前库的表数量。 */
    @Select("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()")
    int countTables();

    /**
     * 主要业务表的精确行数（统计页「资源」模块）。
     * 新增核心表时同步追加一行，否则资源视图看不到它。
     */
    @Select("SELECT 't_user' AS tableName, COUNT(*) AS rowCount FROM t_user "
            + "UNION ALL SELECT 't_survey', COUNT(*) FROM t_survey "
            + "UNION ALL SELECT 't_question', COUNT(*) FROM t_question "
            + "UNION ALL SELECT 't_option', COUNT(*) FROM t_option "
            + "UNION ALL SELECT 't_answer', COUNT(*) FROM t_answer "
            + "UNION ALL SELECT 't_answer_detail', COUNT(*) FROM t_answer_detail "
            + "UNION ALL SELECT 't_survey_version', COUNT(*) FROM t_survey_version "
            + "UNION ALL SELECT 't_survey_collaborator', COUNT(*) FROM t_survey_collaborator "
            + "UNION ALL SELECT 't_survey_review', COUNT(*) FROM t_survey_review "
            + "UNION ALL SELECT 't_notification', COUNT(*) FROM t_notification "
            + "UNION ALL SELECT 't_operation_log', COUNT(*) FROM t_operation_log "
            + "UNION ALL SELECT 't_error_log', COUNT(*) FROM t_error_log "
            + "UNION ALL SELECT 't_wallet', COUNT(*) FROM t_wallet "
            + "UNION ALL SELECT 't_wallet_transaction', COUNT(*) FROM t_wallet_transaction")
    List<Map<String, Object>> countTableRows();

    /** 版本快照占用的 JSON 字节数（长期归档要换存储的依据）。 */
    @Select("SELECT COALESCE(SUM(LENGTH(questions_json)), 0) FROM t_survey_version")
    long sumSnapshotBytes();
}
