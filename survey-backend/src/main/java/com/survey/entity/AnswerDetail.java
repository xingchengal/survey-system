package com.survey.entity;

/**
 * 答卷明细实体 —— 对应表 t_answer_detail，"一题一行"（表体）。
 *
 * ── 谁在用 ────────────────────────────────────────────
 *   AnswerService.submit()（逐条插入）、AnswerDetailMapper（统计/查询）、
 *   SurveyStatistics.vue（单题统计、填空题文本、单份答卷详情）
 *
 * ── content 里到底存了什么（★ 多选题的口径最容易搞错）────
 *   不论单选/多选/填空，统一存**字符串**：
 *     单选 → 选项文本（如 "很满意"），**一题一行**
 *     多选 → 选项文本，**一选项一行**（勾了 3 个选项就是 3 行，questionId 相同）
 *     填空 → 用户输入的原文，一题一行
 *     矩阵 → 内容=列标题、`row_key`=行标题，**一行一条**（3 行矩阵就是 3 行明细）
 *     排序 → 内容=选项文本、`rank_no`=名次（1 起），**一名一条**
 *     NPS  → "0"~"10"，一题一行
 *   所以单题统计可以直接 `GROUP BY content` 数出分布（见 AnswerDetailMapper.countByQuestionId）
 *   —— 这个统计口径**只认"一选项一行"**：若把多选拼成一行 "A,B"，饼图就会按"整串组合"分组而失真。
 *
 *   ★ 不要按"多选 → 前端拼接成串（一题一行）"来理解：那与全项目实现**三处都不符** ——
 *     历史答卷是 `answer_id=1 + question_id=8` 有 3 行、演示数据 `DemoDataService` 也是一选项一行、
 *     统计按 content 分组也只认一选项一行。按旧口径写同题去重守卫，会直接导致
 *     **多选题勾 ≥2 项提交必然 400**。权威口径见 `AnswerService.submit` 的校验
 *     与 `AnswerService.MAX_ROWS_PER_QUESTION`。
 *
 * ── 关于 questionId 的校验 ──────────────────────────────
 *   AnswerService.submit() 在落库前会：用一次批量查询取出本问卷的合法题目 id 集合、
 *   逐条过滤、校验单题内容长度与空答卷，并按题型限制同题行数（多选 ≤50 行）。
 */
public class AnswerDetail {
    private Integer id;
    private Integer answerId;        // 属于哪一份答卷（t_answer.id）
    private Integer questionId;      // 答的是哪道题（t_question.id）
    private String content;          // 答案本体（见类注释）
    /** 矩阵题：这一行答案属于哪个矩阵行（**行标题原文**，不是下标）；其它题型为 null */
    private String rowKey;
    /** 排序题：名次（1 起）；其它题型为 null */
    private Integer rankNo;

    /**
     * 该题是否答对：1 = 对，0 = 错，NULL = 未判分（普通问卷 / 非计分题 / 判分上线前的历史答卷）。
     * ★ 口径是**按题**而不是按行：多选一选项一行，同一道题的所有行会拿到同一个值。
     * ★ 成绩卡与答卷详情用它展示"逐题对错"，作者事后改答案不影响已存的值。
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