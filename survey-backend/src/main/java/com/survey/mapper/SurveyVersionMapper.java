package com.survey.mapper;

import com.survey.entity.SurveyVersion;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 问卷版本快照（`t_survey_version`）的数据访问层。
 *
 * ── 为什么列表与详情分成两条 SQL ──────────────────────
 *   `questions_json` 是 MB 级的大字段（200 题 × 50 选项的极端情况），而版本列表动辄几十条 ——
 *   列表若 `SELECT *`，一次请求就能传出几十 MB。
 *   所以 `findBySurveyId` **只查展示需要的列**，`findById` / `findLatest` 才查 JSON。
 *
 * ── 版本号的分配 ──────────────────────────────────────
 *   `version_no` 由 Service 用 `findMaxVersionNo + 1` 算出再插入，配合
 *   `UNIQUE KEY uk_survey_version(survey_id, version_no)` 兜底：
 *   并发保存同一份问卷时，后插入的那条会撞唯一键 —— Service 捕获后重试一次即可
 *   （同一份问卷被两个人同时编辑的概率极低，不值得为此引入分布式锁）。
 */
@Mapper
public interface SurveyVersionMapper {

    @Insert("INSERT INTO t_survey_version(survey_id, version_no, title, description, question_count, "
            + "questions_json, change_summary, created_by) "
            + "VALUES(#{surveyId}, #{versionNo}, #{title}, #{description}, #{questionCount}, "
            + "#{questionsJson}, #{changeSummary}, #{createdBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SurveyVersion version);

    /**
     * 版本列表（新的在前）。★ 不查 `questions_json`，理由见类注释。
     */
    @Select("SELECT id, survey_id, version_no, title, question_count, change_summary, created_by, created_time "
            + "FROM t_survey_version WHERE survey_id = #{surveyId} ORDER BY version_no DESC")
    List<SurveyVersion> findBySurveyId(Integer surveyId);

    /** 读单个版本（**含 JSON**）：对比、回滚、查看某一版详情都用它。 */
    @Select("SELECT * FROM t_survey_version WHERE id = #{id}")
    SurveyVersion findById(Integer id);

    /** 最新一版（**含 JSON**）：保存题目时用它判断"内容没变就不生成新版本"。 */
    @Select("SELECT * FROM t_survey_version WHERE survey_id = #{surveyId} ORDER BY version_no DESC LIMIT 1")
    SurveyVersion findLatest(Integer surveyId);

    /** 当前最大版本号；没有版本时返回 0。 */
    @Select("SELECT COALESCE(MAX(version_no), 0) FROM t_survey_version WHERE survey_id = #{surveyId}")
    int findMaxVersionNo(Integer surveyId);

    /**
     * 裁剪：删掉版本号小于等于 `maxVersionNoToDelete` 的历史。
     * ★ 由 Service 算好阈值再调用，SQL 里不做 `INSERT/DELETE` 同表子查询
     *   （MySQL 不允许在 DELETE 的子查询里直接引用同一张表，需要再包一层派生表，容易写错）。
     */
    @Delete("DELETE FROM t_survey_version WHERE survey_id = #{surveyId} AND version_no <= #{maxVersionNoToDelete}")
    int deleteUpTo(@Param("surveyId") Integer surveyId, @Param("maxVersionNoToDelete") int maxVersionNoToDelete);

    /**
     * 彻底删除问卷时清空它的全部版本快照。
     * ★ 快照里是**完整题目 JSON**：问卷都彻底删了还留着它，等于"删了但没删干净"，
     *   而且这些行永远不会再被任何正常查询读到（只能靠手工 SQL 发现）。
     */
    @Delete("DELETE FROM t_survey_version WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}
