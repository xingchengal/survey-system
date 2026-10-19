package com.survey.dto;

import com.survey.entity.Question;

import java.util.List;

/**
 * 版本快照 JSON 的结构：{"title":..., "description":..., "questions":[...]}，只记录题目结构（题干 + 选项 + 跳转），不含答卷。
 * 用普通类而非 record：快照长期持久化，旧 JSON 必须能被将来任意版本读出，无参构造 + setter 最稳。
 * questions 直接复用 Question 实体（id / type / title / required / sortOrder / options 齐全），回滚可直接写回题目表。
 * ★ 反序列化必须允许未知字段（SurveyVersionService 的 ObjectMapper）：Question 将来加字段不能让旧快照读不出来。
 */
public class SurveySnapshot {

    /** 快照时的问卷标题（改标题不生成版本，但版本对比仍能看到） */
    private String title;

    /** 快照时的问卷描述 */
    private String description;

    /** 题目（含选项与跳转），按 sort_order 升序 */
    private List<Question> questions;

    /**
     * ★ 空构造器必须保留：Jackson 靠它 + setter 反序列化，删掉会让所有旧版本读取失败。
     */
    public SurveySnapshot() {
    }

    /** 便捷构造器（生成快照时用）。 */
    public SurveySnapshot(String title, String description, List<Question> questions) {
        this.title = title;
        this.description = description;
        this.questions = questions;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<Question> getQuestions() {
        return questions;
    }

    public void setQuestions(List<Question> questions) {
        this.questions = questions;
    }
}
