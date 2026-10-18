package com.survey.entity;

/**
 * 选项实体 —— 对应表 t_option。条件跳转规则就存在这里。
 *
 * ── 谁在用 ────────────────────────────────────────────
 *   QuestionService（保存题目时"先删后插"选项）、QuestionMapper/OptionMapper、
 *   SurveyEdit.vue（编辑器里给每个选项配"跳到第几题"）、SurveyFillByCode.vue（答题时算下一题）
 *
 * ── ★ nextQuestionId 的三种取值（本项目跳转功能的核心）──
 *   null = 不跳转，按顺序做下一题（数据库默认值，绝大多数选项是这个）
 *   -1   = 结束问卷
 *   &gt; 0  = 跳到**这个 id 对应的那道题**（= `t_question.id`）
 *
 * ── ★★ 必须是真 question.id，**不能退回"第 N 题"的序号** ──
 *   题目顺序（sort_order）每次保存都会按数组重排、并删掉没传的题。若这里存序号，
 *   删题或调序之后已存的跳转就会**静默指向错的题**（不报错，只是跳错）。
 *   ⇒ 两端都按 id 工作：编辑器下拉绑定 question.id、答题页按 id 找题、
 *     保存时校验"目标属于本问卷"（`QuestionService.saveQuestions`）、
 *     复制问卷时做跨问卷映射（`SurveyService.copySurvey`）。
 *   ★ 切换时全库 0 条跳转规则，所以**不需要迁移脚本**（不是漏了）。
 */
public class Option {
    private Integer id;
    private Integer questionId;      // 所属题目 ID
    private String content;          // 选项内容（也是答卷统计里 GROUP BY 的依据）
    private Integer sortOrder;       // 排序。★ 多选跳转"按选项顺序取第一个命中"依赖它
    private Integer nextQuestionId;  // 条件跳转目标，含义见类注释

    /**
     * 是否是正确答案：0 = 否（默认），1 = 是。
     * ★ 只有 单选(1) / 多选(2) / 下拉(5) 的选项有判分意义；其它题型保存时会被归一成 0。
     * ★ 判分按**选项原文 content** 比对（t_answer_detail 没有 option_id），
     *   所以考试问卷发布后改选项文字会影响之后提交的判分（已存的得分不变）。
     */
    private Integer isCorrect;

    // ===== Getter/Setter =====
    public Integer getNextQuestionId() { return nextQuestionId; }
    public void setNextQuestionId(Integer nextQuestionId) { this.nextQuestionId = nextQuestionId; }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getQuestionId() { return questionId; }
    public void setQuestionId(Integer questionId) { this.questionId = questionId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public Integer getIsCorrect() { return isCorrect; }
    public void setIsCorrect(Integer isCorrect) { this.isCorrect = isCorrect; }
}