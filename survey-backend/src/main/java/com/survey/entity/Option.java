package com.survey.entity;

/**
 * 选项 —— 表 t_option，条件跳转规则也存在这里（编辑器配跳转、答题页算下一题都读它）。
 * nextQuestionId：null = 不跳、按顺序；-1 = 结束问卷；> 0 = 跳到该 {@code t_question.id} 对应的题。
 * ★ 必须存真 question.id，不能退回「第 N 题」序号：题目每次保存都按数组重排并删题，
 *   存序号会在删题/调序后静默指向错的题（不报错，只是跳错）。
 */
public class Option {
    private Integer id;
    private Integer questionId;      // 所属题目 ID
    private String content;          // 选项内容（答卷统计 GROUP BY 的依据）
    private Integer sortOrder;       // 排序；多选跳转「按选项顺序取第一个命中」依赖它
    private Integer nextQuestionId;  // 条件跳转目标，三种取值见类注释

    /**
     * 是否正确答案：0=否（默认），1=是；只有单选/多选/下拉有判分意义，其它题型保存时归一成 0。
     * ★ 判分按选项原文 content 比对（明细表无 option_id），发布后改选项文字会影响之后提交的判分。
     */
    private Integer isCorrect;

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