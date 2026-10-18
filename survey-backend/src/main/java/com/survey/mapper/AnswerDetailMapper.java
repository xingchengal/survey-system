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
    @Insert("INSERT INTO t_answer_detail(answer_id, question_id, content, row_key, rank_no) " +
            "VALUES(#{answerId}, #{questionId}, #{content}, #{rowKey}, #{rankNo})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnswerDetail detail);

    /**
     * 单题的选项分布：按 content 分组计数。
     * ★ 返回的 Map key 是 SQL 里的列名（content / count / row_key），不是实体属性。
     * ★ 带上 row_key 是为了矩阵题：矩阵按"行 × 列"分组；普通题型的 row_key 恒为 NULL，
     *   会聚成同一组，结果与原来的 `GROUP BY content` 完全一致（不影响既有题型）。
     */
    @Select("SELECT row_key AS row_key, content, COUNT(*) AS count FROM t_answer_detail " +
            "WHERE question_id = #{questionId} GROUP BY row_key, content")
    List<Map<String, Object>> countByQuestionId(Integer questionId);

    /**
     * 排序题的统计：每个选项被排到第几名的**平均名次**。
     * ★ 只统计 rank_no 非空的行：老版本/脏数据里没有名次的排序答案不参与平均，
     *   否则 AVG 会被 NULL 拉成"看起来更靠前"的假象。
     */
    @Select("SELECT content, COUNT(*) AS count, AVG(rank_no) AS avg_rank FROM t_answer_detail " +
            "WHERE question_id = #{questionId} AND rank_no IS NOT NULL GROUP BY content")
    List<Map<String, Object>> countRankingByQuestionId(Integer questionId);

    /**
     * 单题选项分布，**只统计有效答卷**（`t_answer.quality_flag = 0`）。
     *
     * ★ 为什么不给 `countByQuestionId` 加一个 `excludeInvalid` 参数：
     *   注解 SQL 里拼 `<if>` 会让"缓存有两个口径"这件事藏在动态 SQL 里，
     *   排查时很难一眼看出走的是哪条。两条**静态 SQL** + 两个缓存 key，对应关系一目了然。
     * ★ JOIN 的方向：明细 → 答卷（`d.answer_id = a.id`），过滤条件挂在答卷上。
     */
    @Select("SELECT d.row_key AS row_key, d.content, COUNT(*) AS count FROM t_answer_detail d " +
            "JOIN t_answer a ON d.answer_id = a.id " +
            "WHERE d.question_id = #{questionId} AND a.quality_flag = 0 " +
            "GROUP BY d.row_key, d.content")
    List<Map<String, Object>> countValidByQuestionId(Integer questionId);

    /** 排序题统计（只统计有效答卷）—— 与 {@link #countRankingByQuestionId} 同口径。 */
    @Select("SELECT d.content, COUNT(*) AS count, AVG(d.rank_no) AS avg_rank FROM t_answer_detail d " +
            "JOIN t_answer a ON d.answer_id = a.id " +
            "WHERE d.question_id = #{questionId} AND d.rank_no IS NOT NULL AND a.quality_flag = 0 " +
            "GROUP BY d.content")
    List<Map<String, Object>> countValidRankingByQuestionId(Integer questionId);

    /**
     * ★ 批量版：多道题的选项分布，**只统计有效答卷**（整份问卷统计 + 剔除可疑样本时用）。
     * 返回每行 `qid / content / cnt`，与 {@link #countByQuestionIds} 同口径（全小写别名）。
     */
    @Select("<script>" +
            "SELECT d.question_id AS qid, d.row_key AS row_key, d.content, COUNT(*) AS cnt " +
            "FROM t_answer_detail d " +
            "JOIN t_answer a ON d.answer_id = a.id " +
            "WHERE d.question_id IN " +
            "<foreach collection='questionIds' item='qid' open='(' separator=',' close=')'>#{qid}</foreach> " +
            "AND a.quality_flag = 0 " +
            "GROUP BY d.question_id, d.row_key, d.content" +
            "</script>")
    List<Map<String, Object>> countValidByQuestionIds(@Param("questionIds") List<Integer> questionIds);

    /**
     * ★ 批量版排序题统计：一次取回多道排序题的 `{content, count, avgRank}`。
     * 与 {@link #countRankingByQuestionId} 同口径；`avg_rank` 为 double。
     */
    @Select("<script>" +
            "SELECT d.question_id AS qid, d.content, COUNT(*) AS cnt, AVG(d.rank_no) AS avg_rank " +
            "FROM t_answer_detail d " +
            "JOIN t_answer a ON d.answer_id = a.id " +
            "WHERE d.question_id IN " +
            "<foreach collection='questionIds' item='qid' open='(' separator=',' close=')'>#{qid}</foreach> " +
            "AND d.rank_no IS NOT NULL AND a.quality_flag = 0 " +
            "GROUP BY d.question_id, d.content" +
            "</script>")
    List<Map<String, Object>> countValidRankingByQuestionIds(@Param("questionIds") List<Integer> questionIds);

    /**
     * ★ **批量版**选项分布：一次取回多道题的 `{content, count}`（整份问卷统计接口用它）。
     *
     * 返回每行三个列：`qid` / `content` / `cnt`。
     * · 别名故意全小写，与 {@code AnswerMapper.countBySurveyIds} 同一口径
     *   （避免不同 JDBC 驱动对列标签大小写处理不一致）；
     * · **没有任何答卷的题目不会出现在结果里**（`GROUP BY` 无行）——
     *   调用方必须自己为它们补空列表，否则前端会拿到 null 而不是"暂无回答数据"。
     *
     * ★ 为什么不在这里再包一层"保证每题一行"：那需要数字表或递归 CTE，
     *   而在 Java 里补空列表只是 `getOrDefault(qid, List.of())` 一行。
     *
     * @param questionIds 题目 id 列表（调用方需保证非空：空列表会让 `IN ()` 变成语法错误）
     */
    @Select("<script>" +
            "SELECT question_id AS qid, row_key AS row_key, content, COUNT(*) AS cnt " +
            "FROM t_answer_detail " +
            "WHERE question_id IN " +
            "<foreach collection='questionIds' item='qid' open='(' separator=',' close=')'>#{qid}</foreach> " +
            "GROUP BY question_id, row_key, content" +
            "</script>")
    List<Map<String, Object>> countByQuestionIds(@Param("questionIds") List<Integer> questionIds);

    /**
     * ★ 批量版排序题统计（全部答卷口径）：`{qid, content, cnt, avg_rank}`。
     * ★ 为什么单独一条 SQL：`GROUP BY content` 只能数"被选了几次"，
     *   排序题要的是"平均排在第几名"，必须用 AVG(rank_no)。
     */
    @Select("<script>" +
            "SELECT question_id AS qid, content, COUNT(*) AS cnt, AVG(rank_no) AS avg_rank " +
            "FROM t_answer_detail " +
            "WHERE question_id IN " +
            "<foreach collection='questionIds' item='qid' open='(' separator=',' close=')'>#{qid}</foreach> " +
            "AND rank_no IS NOT NULL " +
            "GROUP BY question_id, content" +
            "</script>")
    List<Map<String, Object>> countRankingByQuestionIds(@Param("questionIds") List<Integer> questionIds);

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
     * ★ **批量版**填空文本：一次取回多道填空题的文本，且**每题最多 `perQuestion` 条**。
     *
     * ── 为什么不能简单套用单题版的 `LIMIT 2000` ──────────────
     *   单题版的 `LIMIT 2000` 限制的是"整条 SQL 的返回行数"，那没问题（只查一道题）。
     *   但批量查多题时，`LIMIT 2000` 会变成"**所有题一共**只能取 2000 条"：
     *   第一道题如果就有 2000 条，后面几道题**一条都拿不到**（静默丢数据，最难查的那种）。
     *   ⇒ 用窗口函数按 `question_id` 分区编号，再在外层过滤 `rn <= perQuestion`，
     *     这样"每题 2000 条"的上限才真正成立。
     *
     * ★ MySQL 8 才支持窗口函数（`ROW_NUMBER() OVER`）—— 本项目 init.sql 就是 8.0 语法。
     * ★ 排序用 `ORDER BY id`（自增主键）而不是 `submit_time`：同秒提交的答卷时间相同，
     *   按时间排序在分页/取前 N 条时不稳定。
     *
     * @param questionIds 题目 id 列表（非空）
     * @param perQuestion 每题最多取多少条（调用方与单题版的 2000 对齐）
     */
    @Select("<script>" +
            "SELECT t.qid AS qid, t.content AS content FROM (" +
            "SELECT question_id AS qid, content, " +
            "ROW_NUMBER() OVER (PARTITION BY question_id ORDER BY id) AS rn " +
            "FROM t_answer_detail WHERE question_id IN " +
            "<foreach collection='questionIds' item='qid' open='(' separator=',' close=')'>#{qid}</foreach> " +
            "AND content IS NOT NULL AND content != ''" +
            ") t WHERE t.rn &lt;= #{perQuestion}" +
            "</script>")
    List<Map<String, Object>> findTextAnswersByQuestionIds(@Param("questionIds") List<Integer> questionIds,
                                                           @Param("perQuestion") int perQuestion);

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
