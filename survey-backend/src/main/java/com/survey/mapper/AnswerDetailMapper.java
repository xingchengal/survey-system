package com.survey.mapper;

import com.survey.entity.AnswerDetail;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface AnswerDetailMapper {

    @Insert("INSERT INTO t_answer_detail(answer_id, question_id, content) VALUES(#{answerId}, #{questionId}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnswerDetail detail);

    @Select("SELECT content, COUNT(*) AS count FROM t_answer_detail " +
            "WHERE question_id = #{questionId} GROUP BY content")
    List<Map<String, Object>> countByQuestionId(Integer questionId);

    @Select("SELECT content FROM t_answer_detail WHERE question_id = #{questionId} AND content IS NOT NULL AND content != ''")
    List<String> findTextAnswers(Integer questionId);

    @Select("SELECT d.* FROM t_answer_detail d " +
            "JOIN t_answer a ON d.answer_id = a.id " +
            "WHERE a.survey_id = #{surveyId}")
    List<AnswerDetail> findBySurveyId(Integer surveyId);

    // 根据答卷ID查询明细
    @Select("SELECT * FROM t_answer_detail WHERE answer_id = #{answerId}")
    List<AnswerDetail> findByAnswerId(Integer answerId);

    @Delete("DELETE d FROM t_answer_detail d " +
            "INNER JOIN t_answer a ON d.answer_id = a.id " +
            "WHERE a.survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);

    // 根据答卷ID删除明细
    @Delete("DELETE FROM t_answer_detail WHERE answer_id = #{answerId}")
    int deleteByAnswerId(@Param("answerId") Integer answerId);
}