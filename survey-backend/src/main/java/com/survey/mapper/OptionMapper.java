package com.survey.mapper;

import com.survey.entity.Option;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface OptionMapper {

    // 插入选项，获取自增ID
    @Insert("INSERT INTO t_option(question_id, content, sort_order, next_question_id) " +
            "VALUES(#{questionId}, #{content}, #{sortOrder}, #{nextQuestionId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Option option);

    // 根据题目ID查询所有选项
    @Select("SELECT * FROM t_option WHERE question_id = #{questionId} ORDER BY sort_order ASC")
    List<Option> findByQuestionId(Integer questionId);

    // 根据题目ID删除所有选项
    @Delete("DELETE FROM t_option WHERE question_id = #{questionId}")
    int deleteByQuestionId(Integer questionId);

    @Delete("DELETE o FROM t_option o " +
            "INNER JOIN t_question q ON o.question_id = q.id " +
            "WHERE q.survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}