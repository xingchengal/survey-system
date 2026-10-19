package com.survey.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 问卷提交 IP（t_survey_ip）—— 「IP 限制」高级设置的数据层。
 * ★ 只做两件事：判断该 IP 是否已提交过、登记一次提交；唯一键 (survey_id, ip) 是并发防线，
 *   同 IP 并发提交时 INSERT IGNORE 只有一个返回 1，其余返回 0 → 400。
 * ★ IP 只取连接来源，不要用可伪造的 X-Forwarded-For，否则换个请求头就能绕过限次。
 * 记录与答卷同一事务写（AnswerService.doSubmit），答卷没落库不会白占 IP 名额。
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

    /** 按 survey_id + ip 计数：判断该 IP 是否已提交过（提交路径用的是 insertIgnore 的返回值）。 */
    @Select("SELECT COUNT(*) FROM t_survey_ip WHERE survey_id = #{surveyId} AND ip = #{ip}")
    int countBySurveyAndIp(@Param("surveyId") Integer surveyId, @Param("ip") String ip);

    /** 彻底删除问卷时清空它的 IP 记录（问卷没了，记录只会占着唯一键）。 */
    @Delete("DELETE FROM t_survey_ip WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}
