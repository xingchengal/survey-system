package com.survey.mapper;

import com.survey.entity.Question;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 题目表（t_question）的数据访问层。
 * type：1=单选 / 2=多选 / 3=填空；required：是否必答。
 * sort_order 只决定展示顺序，逻辑跳转存的是真实 question.id，重排不会让跳转错位。
 * ★ 查询一律按 survey_id 查、按 sort_order ASC 排序；删除是按 survey 级联的一部分（deleteBySurveyId）。
 */
@Mapper
public interface QuestionMapper {

    /** 插入题目，并把自增 id 回填到实体（后面要拿它插选项）。 */
    @Insert("INSERT INTO t_question(survey_id, type, title, required, sort_order, matrix_rows, matrix_cols, score) " +
            "VALUES(#{surveyId}, #{type}, #{title}, #{required}, #{sortOrder}, #{matrixRows}, #{matrixCols}, " +
            "IFNULL(#{score}, 0))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Question question);

    /** 查某问卷的全部题目（按 sort_order 排序）。不含选项，选项另查（见 QuestionService）。 */
    @Select("SELECT * FROM t_question WHERE survey_id = #{surveyId} ORDER BY sort_order ASC")
    List<Question> findBySurveyId(Integer surveyId);

    /** 按题目 id 删除（用户删掉某道题时）。 */
    @Delete("DELETE FROM t_question WHERE id = #{id}")
    int deleteById(Integer id);

    /**
     * 更新题目内容与顺序。★ 不动 survey_id —— 题目不能换问卷；
     * matrix_rows / matrix_cols 必须一起更新，否则矩阵题改完行列再保存还是旧的。
     */
    @Update("UPDATE t_question SET type=#{type}, title=#{title}, required=#{required}, " +
            "sort_order=#{sortOrder}, matrix_rows=#{matrixRows}, matrix_cols=#{matrixCols}, " +
            "score=IFNULL(#{score}, 0) WHERE id=#{id}")
    int update(Question question);

    /** 删掉整份问卷的题目（删除问卷 / 复制失败回滚等场景）。 */
    @Delete("DELETE FROM t_question WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);

    /** 按题目 id 查（AnswerController 用它把 questionId 反查成 surveyId 做归属校验）。 */
    @Select("SELECT * FROM t_question WHERE id = #{id}")
    Question findById(Integer id);
}
