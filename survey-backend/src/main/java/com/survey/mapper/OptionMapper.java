package com.survey.mapper;

import com.survey.entity.Option;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 选项表（t_option）的数据访问层 —— 条件跳转规则就存在这张表的 next_question_id 上。
 *
 * ── ★ next_question_id 的三种取值（理解本项目跳转功能的关键）──
 *   NULL = 不跳转，按顺序做下一题（数据库默认值，绝大多数选项都是这个）
 *   -1   = 结束问卷
 *   > 0  = 跳到**这个 id 对应的那道题**（= t_question.id）
 *
 * ── ★★ 这一列存的是**真 question.id**，不是"1 基题目序号" ──
 *   若存序号，"删题/调序"后会**静默指向错的题**（题目顺序每次保存都被重排）。
 *   现在按真 id 存：编辑器按 question.id 绑定、答题页按 id 找题、
 *   保存时校验目标归属（`QuestionService.saveQuestions`），复制问卷时做跨问卷映射
 *   （`SurveyService.copySurvey`）。完整理由与"为什么不需要迁移"写在
 *   {@link com.survey.entity.Option} 的类注释里。
 *
 * ── 删除方式 ──────────────────────────────────────────
 *   deleteByQuestionId：按题目删（QuestionService 保存时"先全删再全插"用的就是它）
 *   deleteBySurveyId  ：用 JOIN 一次删掉整份问卷的选项 ——
 *                       因为 t_option 表里没有 survey_id 字段，只能借 t_question 关联。
 *
 * ── 排序 ──────────────────────────────────────────────
 *   查询按 sort_order ASC。"多选按选项顺序取第一个命中的跳转"就依赖这个顺序。
 */
@Mapper
public interface OptionMapper {

    /** 插入选项（含跳转规则与"是否正确答案"），自增 id 回填到实体。 */
    @Insert("INSERT INTO t_option(question_id, content, sort_order, next_question_id, is_correct) " +
            "VALUES(#{questionId}, #{content}, #{sortOrder}, #{nextQuestionId}, IFNULL(#{isCorrect}, 0))")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Option option);

    /** 查某题的全部选项（按顺序）。★ 顺序会影响多选跳转的判定结果。 */
    @Select("SELECT * FROM t_option WHERE question_id = #{questionId} ORDER BY sort_order ASC")
    List<Option> findByQuestionId(Integer questionId);

    /**
     * ★ 批量查多道题的选项 —— 质控判定要在"一次提交"里同时看所有题的选项。
     * ★ 用一条 SQL 而不是逐题查：提交是热路径，N 道题各查一次就是 N 次往返。
     *
     * @param questionIds 题目 id 列表（调用方保证非空）
     */
    @Select("<script>SELECT * FROM t_option WHERE question_id IN "
            + "<foreach collection='questionIds' item='qid' open='(' separator=',' close=')'>#{qid}</foreach> "
            + "ORDER BY question_id, sort_order ASC</script>")
    List<Option> findByQuestionIds(@Param("questionIds") List<Integer> questionIds);

    /**
     * 删掉某题的全部选项。
     * ★ QuestionService.saveQuestions() 保存选择题时是"先删后插"，
     *   所以每次保存 option.id 都会变（这是有意的简化实现）。
     */
    @Delete("DELETE FROM t_option WHERE question_id = #{questionId}")
    int deleteByQuestionId(Integer questionId);

    /** 删掉整份问卷的所有选项（JOIN t_question 找 survey_id）。 */
    @Delete("DELETE o FROM t_option o " +
            "INNER JOIN t_question q ON o.question_id = q.id " +
            "WHERE q.survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);
}