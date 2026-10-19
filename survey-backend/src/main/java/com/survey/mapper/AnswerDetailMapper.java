package com.survey.mapper;

import com.survey.entity.AnswerDetail;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 答卷明细表（t_answer_detail）的数据访问层 —— 一题一行。
 * content 一律存字符串：多选也存选项文本，统计时按 content 分组；它是最底层表，级联删除必须最先删它。
 * ★ 多选一选项一行：统计按 content 分组即得票数，不要把多个选项拼进一行。
 */
@Mapper
public interface AnswerDetailMapper {

    /** 插入一条答案（每题一行）。判分题的 is_correct 随明细一起落库。 */
    @Insert("INSERT INTO t_answer_detail(answer_id, question_id, content, row_key, rank_no, is_correct) " +
            "VALUES(#{answerId}, #{questionId}, #{content}, #{rowKey}, #{rankNo}, #{isCorrect})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnswerDetail detail);

    /**
     * 单题选项分布：按 content 分组计数；带 row_key 以支持矩阵题（普通题 row_key 恒 NULL，聚成一组）。
     * 返回 Map 的 key 是 SQL 列名（content / count / row_key），不是实体属性。
     */
    @Select("SELECT row_key AS row_key, content, COUNT(*) AS count FROM t_answer_detail " +
            "WHERE question_id = #{questionId} GROUP BY row_key, content")
    List<Map<String, Object>> countByQuestionId(Integer questionId);

    /** 排序题统计：每个选项的平均名次。只统计 rank_no 非空的行，否则 NULL 会把 AVG 拉成"看起来更靠前"。 */
    @Select("SELECT content, COUNT(*) AS count, AVG(rank_no) AS avg_rank FROM t_answer_detail " +
            "WHERE question_id = #{questionId} AND rank_no IS NOT NULL GROUP BY content")
    List<Map<String, Object>> countRankingByQuestionId(Integer questionId);

    /**
     * 单题选项分布，只统计有效答卷（quality_flag = 0）。
     * 不合并进 countByQuestionId：缓存有两个口径，两条静态 SQL + 两个 key 比动态 <if> 更易排查。
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

    /** 批量：多道题的选项分布，只统计有效答卷。返回每行 { qid, row_key, content, cnt }，与 countByQuestionIds 同口径。 */
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

    /** 批量：多道排序题统计，只统计有效答卷；avg_rank 为 double。 */
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
     * 批量：多道题的选项分布（全量口径）。一次取回 { qid, content, cnt }，别名全小写与 AnswerMapper 同口径。
     * 没有答卷的题目不会出现在结果里，调用方必须自己补空列表，否则前端拿到 null 而不是"暂无数据"。
     * @param questionIds 题目 id 列表（不能为空：空列表会让 IN () 变语法错误）
     */
    @Select("<script>" +
            "SELECT question_id AS qid, row_key AS row_key, content, COUNT(*) AS cnt " +
            "FROM t_answer_detail " +
            "WHERE question_id IN " +
            "<foreach collection='questionIds' item='qid' open='(' separator=',' close=')'>#{qid}</foreach> " +
            "GROUP BY question_id, row_key, content" +
            "</script>")
    List<Map<String, Object>> countByQuestionIds(@Param("questionIds") List<Integer> questionIds);

    /** 批量：排序题统计（全量口径）。排序题要的是"平均第几名"，必须 AVG(rank_no)，不能只 COUNT。 */
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
     * 填空题的所有非空文本（前端列表/词云）。
     * ★ 必须保留 LIMIT 2000（OOM 防护）：单题内容上限 1000 字，两万份答卷一次读进堆再序列化足以压垮服务。
     */
    @Select("SELECT content FROM t_answer_detail WHERE question_id = #{questionId} " +
            "AND content IS NOT NULL AND content != '' LIMIT 2000")
    List<String> findTextAnswers(Integer questionId);

    /**
     * 批量：多道填空题的文本，每题最多 perQuestion 条（与单题版 2000 对齐）。
     * 不能直接套单题版 LIMIT：那会变成"所有题合计 2000 条"，后面的题一条都拿不到。
     * 用 ROW_NUMBER() 按 question_id 分区取前 N（MySQL 8 窗口函数）。
     * @param questionIds 题目 id 列表（非空）
     * @param perQuestion 每题最多取多少条（与单题版的 2000 对齐）
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
     * 整份问卷的全部明细（Excel 导出唯一调用方）。
     * 有意不加 LIMIT：导出前已预检份数 ≤5000 且明细行数 ≤50000，这里再截会静默丢数据，比 OOM 更糟。
     */
    @Select("SELECT d.* FROM t_answer_detail d " +
            "JOIN t_answer a ON d.answer_id = a.id " +
            "WHERE a.survey_id = #{surveyId}")
    List<AnswerDetail> findBySurveyId(Integer surveyId);

    /**
     * 整份问卷的明细行数 —— 导出前内存预检用。
     * 必须数明细行而非答卷份数：真正进堆的是"份数 × 题数"，5000 × 300 = 150 万行。
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

    // 不要再加"按单份答卷删明细"的接口：级联删除统一走上面的 deleteBySurveyId，
    // 多一条删除入口会造成不同的孤儿数据形态。
}
