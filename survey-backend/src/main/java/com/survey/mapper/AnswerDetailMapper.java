package com.survey.mapper;

import com.survey.entity.AnswerDetail;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 答卷明细表（t_answer_detail）的数据访问层 —— 一题一行的原始答案。
 *
 * ── 表结构要点 ────────────────────────────────────────
 *   answer_id   : 属于哪一份答卷（t_answer.id）
 *   question_id : 答的是哪道题（t_question.id）
 *   content     : 答案本体。★ 不论单选/多选/填空，统一存字符串 ——
 *                 多选存的是选项文本，统计时按 content 分组计数即可。
 *
 * ── 删除方式 ──────────────────────────────────────────
 *   只保留按整份问卷删（deleteBySurveyId，JOIN t_answer）。
 *   ★ 它是整条数据链最底层的一张表，级联删除时必须最先删它。
 *
 * ── 统计相关 ──────────────────────────────────────────
 *   countByQuestionId 返回 [{content, count}]，是单选/多选题图表的数据源；
 *   findTextAnswers 只取填空题的非空文本。
 */
@Mapper
public interface AnswerDetailMapper {

    /** 插入一条答案（一次提交里每题一行）。 */
    @Insert("INSERT INTO t_answer_detail(answer_id, question_id, content) VALUES(#{answerId}, #{questionId}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnswerDetail detail);

    /**
     * 单题的选项分布：按 content 分组计数。
     * ★ 返回的 Map key 是 SQL 里的列名 content / count（不是实体属性）。
     */
    @Select("SELECT content, COUNT(*) AS count FROM t_answer_detail " +
            "WHERE question_id = #{questionId} GROUP BY content")
    List<Map<String, Object>> countByQuestionId(Integer questionId);

    /**
     * 填空题的所有非空答案文本（前端做列表/词云展示）。
     *
     * ★ 加 **LIMIT 2000**（OOM 防护）：
     *   若没有上限，一份收了两万份答卷的问卷，每个填空题就是两万条 ×
     *   最多 1000 字（★ 单题内容上限已统一到 common/TextLimits.ANSWER_CONTENT = 1000，
     *   与 t_answer_detail.content 的列宽 varchar(1000) 一致），
     *   一次性读进堆再序列化成 JSON，足以把服务压垮。而这条接口是**单题**级别的，
     *   前端只拿来做列表/词云，2000 条完全够用。
     *   对照：Excel 导出早就有 MAX_EXPORT_RESPONSES = 5000 的预检保护，
     *   这个更轻量、更容易被点到的接口反而漏了 —— 属于"防护漏了一处"。
     */
    @Select("SELECT content FROM t_answer_detail WHERE question_id = #{questionId} " +
            "AND content IS NOT NULL AND content != '' LIMIT 2000")
    List<String> findTextAnswers(Integer questionId);

    /**
     * 整份问卷的全部明细（JOIN t_answer 按 survey_id 取）。
     *
     * ★ 这条**有意不加 LIMIT**：
     *   它唯一的调用方是 AnswerController 的 Excel 导出，而那条路径在取数之前
     *   已经做过**两道**预检：答卷份数 ≤ MAX_EXPORT_RESPONSES(5000)
     *   且明细行数 ≤ MAX_EXPORT_DETAILS(50000)（见 AnswerController.export）。
     *   如果在这里再加一个行数上限，一旦问卷题目数较多（5000 份 × 10 题 = 5 万行明细），
     *   导出就会被**静默截断** —— 那是比 OOM 更糟的问题（用户拿到一份缺数据的表格还不知道）。
     *   要收紧的话应该收紧上面那两个上限，而不是在这里砍行数。
     */
    @Select("SELECT d.* FROM t_answer_detail d " +
            "JOIN t_answer a ON d.answer_id = a.id " +
            "WHERE a.survey_id = #{surveyId}")
    List<AnswerDetail> findBySurveyId(Integer surveyId);

    /**
     * 整份问卷的**明细行数**（= 答卷份数 × 每题行数）—— 导出前的内存预检用它。
     * ★ 导出预检必须数**明细行数**（不是答卷份数，见 AnswerMapper.countBySurveyId）：
     *   真正进堆的是明细行数：5000 份答卷 × 300 题 = **150 万行**，
     *   先整份读进 List，再被 answerMap 与 rows 各复制一份 —— 预检"通过"给的是假安全感。
     */
    @Select("SELECT COUNT(*) FROM t_answer_detail d " +
            "JOIN t_answer a ON d.answer_id = a.id WHERE a.survey_id = #{surveyId}")
    int countBySurveyId(Integer surveyId);

    /** 某一份答卷的全部明细（点开单条答卷看详情）。 */
    @Select("SELECT * FROM t_answer_detail WHERE answer_id = #{answerId}")
    List<AnswerDetail> findByAnswerId(Integer answerId);

    /** 删掉整份问卷的所有明细（级联删除用）。 */
    @Delete("DELETE d FROM t_answer_detail d " +
            "INNER JOIN t_answer a ON d.answer_id = a.id " +
            "WHERE a.survey_id = #{surveyId}")
    int deleteBySurveyId(@Param("surveyId") Integer surveyId);

    // ★ 这里**不要**再加"按单份答卷删明细"的接口：
    //   全仓 grep 确认**零调用点** —— 级联删除早已统一走上面的 deleteBySurveyId
    //   （一条 JOIN DELETE 删完整份问卷的明细，见 SurveyService.cascadeDeleteSurvey）。
    //   留着它会让后来者以为"删除有第二条入口"，实际两条件路径会造成不同的孤儿数据形态。
}
