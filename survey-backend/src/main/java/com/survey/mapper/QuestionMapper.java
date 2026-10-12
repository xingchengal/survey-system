package com.survey.mapper;

import com.survey.entity.Question;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 题目表（t_question）的数据访问层。
 *
 * ── 表结构要点 ────────────────────────────────────────
 *   type       : 1=单选  2=多选  3=填空
 *   sort_order : 题目顺序（每次保存按数组重排）。★ 它与逻辑跳转的语义**无关** ——
 *                跳转存的是真 question.id，所以重排不会让跳转错位。
 *   required   : 是否必答（前端答题时的校验依据）
 *
 * ── 排序约定 ──────────────────────────────────────────
 *   查询一律 ORDER BY sort_order ASC，保证前端拿到的顺序与编辑器里一致。
 *
 * ── 删除方式 ──────────────────────────────────────────
 *   按单题删（deleteById）或按整份问卷删（deleteBySurveyId，级联删除用）。
 */
@Mapper
public interface QuestionMapper {

    /** 插入题目，并把自增 id 回填到实体（后面要拿它插选项）。 */
    @Insert("INSERT INTO t_question(survey_id, type, title, required, sort_order) " +
            "VALUES(#{surveyId}, #{type}, #{title}, #{required}, #{sortOrder})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Question question);

    /** 查某问卷的全部题目（按顺序）。★ 不含选项，选项要另外查（见 QuestionService）。 */
    @Select("SELECT * FROM t_question WHERE survey_id = #{surveyId} ORDER BY sort_order ASC")
    List<Question> findBySurveyId(Integer surveyId);

    /** 按题目 id 删除（用户删掉某道题时）。 */
    @Delete("DELETE FROM t_question WHERE id = #{id}")
    int deleteById(Integer id);

    /** 更新题目内容与顺序。★ 不动 survey_id —— 题目不能换问卷。 */
    @Update("UPDATE t_question SET type=#{type}, title=#{title}, required=#{required}, sort_order=#{sortOrder} WHERE id=#{id}")
    int update(Question question);

    /** 删掉整份问卷的题目（删除用户/复制失败回滚等场景）。 */
    @Delete("DELETE FROM t_question WHERE survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);

    /** 按题目 id 查（AnswerController 用它把 questionId 反查成 surveyId 做归属校验）。 */
    @Select("SELECT * FROM t_question WHERE id = #{id}")
    Question findById(Integer id);
}