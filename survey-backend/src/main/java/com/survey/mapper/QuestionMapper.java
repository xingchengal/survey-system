package com.survey.mapper;

import com.survey.entity.Question;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface QuestionMapper {

    // 插入题目，获取自增ID
    @Insert("INSERT INTO t_question(survey_id, type, title, required, sort_order) " +
            "VALUES(#{surveyId}, #{type}, #{title}, #{required}, #{sortOrder})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Question question);

    // 根据问卷ID查询所有题目
    @Select("SELECT * FROM t_question WHERE survey_id = #{surveyId} ORDER BY sort_order ASC")
    List<Question> findBySurveyId(Integer surveyId);

    // 根据题目ID删除
    @Delete("DELETE FROM t_question WHERE id = #{id}")
    int deleteById(Integer id);

    // 更新题目
    @Update("UPDATE t_question SET type=#{type}, title=#{title}, required=#{required}, sort_order=#{sortOrder} WHERE id=#{id}")
    int update(Question question);

    @Delete("DELETE FROM t_question WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}