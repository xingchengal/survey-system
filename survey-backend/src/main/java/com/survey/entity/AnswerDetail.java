package com.survey.entity;

/**
 * 答卷明细 —— 表 t_answer_detail，答题内容一题一行（多选/矩阵/排序为「一项一行」）。
 * content 一律存文本：单选/填空/NPS 一题一行，多选一选项一行；矩阵行标题进 {@code rowKey}，排序名次进 {@code rankNo}。
 * ★ 不能改成「多选拼成一行」：单题统计直接 {@code GROUP BY content}，拼成「A,B」后饼图会按整串组合分组而失真。
 */
public class AnswerDetail {
    private Integer id;
    private Integer answerId;        // 属于哪份答卷（t_answer.id）
    private Integer questionId;      // 答的是哪道题（t_question.id）
    private String content;          // 答案本体，口径见类注释
    /** 矩阵题：行标题原文（不是下标）；其它题型 null */
    private String rowKey;
    /** 排序题：名次（1 起）；其它题型 null */
    private Integer rankNo;

    /**
     * 该题是否答对：1=对，0=错，NULL=未判分（普通问卷 / 非计分题）。
     * ★ 按题不按行：多选一选项一行，同题所有行拿到同一个值；作者事后改答案不影响已存的值。
     */
    private Integer isCorrect;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getAnswerId() { return answerId; }
    public void setAnswerId(Integer answerId) { this.answerId = answerId; }

    public Integer getQuestionId() { return questionId; }
    public void setQuestionId(Integer questionId) { this.questionId = questionId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getRowKey() { return rowKey; }
    public void setRowKey(String rowKey) { this.rowKey = rowKey; }

    public Integer getRankNo() { return rankNo; }
    public void setRankNo(Integer rankNo) { this.rankNo = rankNo; }

    public Integer getIsCorrect() { return isCorrect; }
    public void setIsCorrect(Integer isCorrect) { this.isCorrect = isCorrect; }
}