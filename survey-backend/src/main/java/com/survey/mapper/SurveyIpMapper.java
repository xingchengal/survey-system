package com.survey.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 问卷提交 IP（t_survey_ip）—— 「IP 限制」高级设置的数据层。
 *
 * ★ 只做两件事：判断"这个 IP 是否已经交过"与"登记一次提交"。
 *   **唯一键 (survey_id, ip)** 才是并发防线：
 *   两个同 IP 请求同时提交时，INSERT IGNORE 只有一个返回 1，另一个返回 0 → 400。
 * ★ 记录与答卷在同一个事务里写（见 AnswerService.doSubmit），
 *   所以"答卷没落库"时不会白占一个 IP 名额。
 */
@Mapper
public interface SurveyIpMapper {

    /**
     * 登记一次提交（幂等）。
     *
     * @return 1 = 这个 IP 首次提交；0 = 已经提交过（被唯一键挡下）
     */
    @Insert("INSERT IGNORE INTO t_survey_ip (survey_id, ip) VALUES (#{surveyId}, #{ip})")
    int insertIgnore(@Param("surveyId") Integer surveyId, @Param("ip") String ip);

    /** 这个 IP 是否已提交过该问卷（只读；提交路径用的是 insertIgnore 的返回值） */
    @Select("SELECT COUNT(*) FROM t_survey_ip WHERE survey_id = #{surveyId} AND ip = #{ip}")
    int countBySurveyAndIp(@Param("surveyId") Integer surveyId, @Param("ip") String ip);
}
