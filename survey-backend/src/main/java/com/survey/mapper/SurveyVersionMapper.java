package com.survey.mapper;

import com.survey.entity.SurveyVersion;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 问卷版本快照（t_survey_version）的数据访问层。
 * version_no 由 Service 用 findMaxVersionNo + 1 算，唯一键 uk_survey_version(survey_id, version_no) 兜底并发。
 * ★ 列表不查 questions_json（MB 级大字段）：findBySurveyId 只取展示列，findById / findLatest 才带 JSON。
 */
@Mapper
public interface SurveyVersionMapper {

    @Insert("INSERT INTO t_survey_version(survey_id, version_no, title, description, question_count, "
            + "questions_json, change_summary, created_by) "
            + "VALUES(#{surveyId}, #{versionNo}, #{title}, #{description}, #{questionCount}, "
            + "#{questionsJson}, #{changeSummary}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SurveyVersion version);

    /** 版本列表（按 survey_id 查，新的在前）。不查 questions_json，理由见类注释。 */
    @Select("SELECT id, survey_id, version_no, title, question_count, change_summary, created_by, created_time "
            + "FROM t_survey_version WHERE survey_id = #{surveyId} ORDER BY version_no DESC")
    List<SurveyVersion> findBySurveyId(Integer surveyId);

    /** 读单个版本（含 JSON）：对比、回滚、查看某一版详情都用它。 */
    @Select("SELECT * FROM t_survey_version WHERE id = #{id}")
    SurveyVersion findById(Integer id);

    /** 最新一版（含 JSON）：保存题目时用它判断"内容没变就不生成新版本"。 */
    @Select("SELECT * FROM t_survey_version WHERE survey_id = #{surveyId} ORDER BY version_no DESC LIMIT 1")
    SurveyVersion findLatest(Integer surveyId);

    /** 当前最大版本号；没有版本时返回 0。 */
    @Select("SELECT COALESCE(MAX(version_no), 0) FROM t_survey_version WHERE survey_id = #{surveyId}")
    int findMaxVersionNo(Integer surveyId);

    /**
     * 删掉版本号 <= maxVersionNoToDelete 的历史（裁剪阈值由 Service 算好再传）。
     * 阈值不在 SQL 里做同表子查询：MySQL 不允许 DELETE 的子查询直接引用同一张表。
     */
    @Delete("DELETE FROM t_survey_version WHERE survey_id = #{surveyId} AND version_no <= #{maxVersionNoToDelete}")
    int deleteUpTo(@Param("surveyId") Integer surveyId, @Param("maxVersionNoToDelete") int maxVersionNoToDelete);

    /** 彻底删除问卷时清空它的版本快照：快照是完整题目 JSON，留着等于"删了但没删干净"。 */
    @Delete("DELETE FROM t_survey_version WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}
