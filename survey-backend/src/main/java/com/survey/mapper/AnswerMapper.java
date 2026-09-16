package com.survey.mapper;

import com.survey.entity.Answer;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AnswerMapper {

    // 提交答卷
    @Insert("INSERT INTO t_answer(survey_id, user_id) VALUES(#{surveyId}, #{userId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Answer answer);

    // 统计某问卷的答卷数量
    @Select("SELECT COUNT(*) FROM t_answer WHERE survey_id = #{surveyId}")
    int countBySurveyId(Integer surveyId);

    // 统计某用户所有问卷的答卷总数
    @Select("SELECT COUNT(*) FROM t_answer a " +
            "JOIN t_survey s ON a.survey_id = s.id WHERE s.user_id = #{userId}")
    int countByOwnerId(Integer userId);

    // 根据问卷ID查询所有答卷
    @Select("SELECT * FROM t_answer WHERE survey_id = #{surveyId} ORDER BY submit_time DESC")
    List<Answer> findBySurveyId(Integer surveyId);

    // 根据答卷ID查询
    @Select("SELECT * FROM t_answer WHERE id = #{id}")
    Answer findById(Integer id);

    // 根据问卷ID删除所有答卷
    @Delete("DELETE FROM t_answer WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}