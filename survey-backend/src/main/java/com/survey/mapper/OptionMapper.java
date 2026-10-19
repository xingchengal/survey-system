package com.survey.mapper;

import com.survey.entity.Option;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 选项表（t_option）的数据访问层 —— 条件跳转规则存在 next_question_id 上。
 * next_question_id：NULL=顺序做下一题 / -1=结束问卷 / >0=跳到该 id 对应的题。
 * ★ 存的是真 question.id，不是"1 基题目序号"：序号在删题 / 调序后会静默指向错的题。
 * ★ 查询必须按 sort_order 排序：多选"取第一个命中的跳转"依赖这个顺序。
 */
@Mapper
public interface OptionMapper {

    /** 插入选项（含跳转规则与"是否正确答案"），自增 id 回填到实体。 */
    @Insert("INSERT INTO t_option(question_id, content, sort_order, next_question_id, is_correct) " +
            "VALUES(#{questionId}, #{content}, #{sortOrder}, #{nextQuestionId}, IFNULL(#{isCorrect}, 0))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Option option);

    /** 某题的全部选项（按 sort_order 顺序）。 */
    @Select("SELECT * FROM t_option WHERE question_id = #{questionId} ORDER BY sort_order ASC")
    List<Option> findByQuestionId(Integer questionId);

    /** 批量查多道题的选项：提交时质控要一次看全，一条 SQL 取代逐题往返，热路径不能 N+1。 */
    @Select("<script>SELECT * FROM t_option WHERE question_id IN "
            + "<foreach collection='questionIds' item='qid' open='(' separator=',' close=')'>#{qid}</foreach> "
            + "ORDER BY question_id, sort_order ASC</script>")
    List<Option> findByQuestionIds(@Param("questionIds") List<Integer> questionIds);

    /** 删掉某题的全部选项。保存选择题是"先删后插"，所以每次保存 option.id 都会变。 */
    @Delete("DELETE FROM t_option WHERE question_id = #{questionId}")
    int deleteByQuestionId(Integer questionId);

    /** 删掉整份问卷的所有选项（t_option 没有 survey_id，JOIN t_question 定位）。 */
    @Delete("DELETE o FROM t_option o " +
            "INNER JOIN t_question q ON o.question_id = q.id " +
            "WHERE q.survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}
