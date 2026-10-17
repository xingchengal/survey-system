package com.survey.dto;

import com.survey.entity.Question;

import java.util.List;

/**
 * 版本快照 JSON 的结构：`{"title":..., "description":..., "questions":[...]}`。
 *
 * ── 为什么是普通类而不是 record ─────────────────────────
 *   反序列化 record 依赖编译期的 `-parameters`（否则 Jackson 找不到构造器参数名）。
 *   而这份 JSON 是**长期持久化**的数据：旧快照必须能被将来任何一版代码读出来，
 *   普通类 + 无参构造 + setter 是兼容性最稳的写法。
 *
 * ── 为什么直接存 Question 实体 ──────────────────────────
 *   它已经带着 id / type / title / required / sortOrder / options，
 *   回滚时可以直接改回题目表，对比时也能按 questionId 对齐 —— 不必另造一套结构。
 *   ★ 反序列化时**允许未知字段**（见 `SurveyVersionService` 里的 ObjectMapper 配置）：
 *     将来给 Question 加字段，旧快照不能因此读不出来。
 */
public class SurveySnapshot {

    /** 快照时的问卷标题（改标题不生成版本，但对比时能看到当时的值） */
    private String title;

    /** 快照时的问卷描述 */
    private String description;

    /** 题目（含选项），按 sort_order 升序 */
    private List<Question> questions;

    /**
     * ★ 空构造器**必须保留**：Jackson 靠它 + setter 反序列化快照 JSON。
     *   去掉它（只留下面的便捷构造）会让所有历史版本在读出来时直接失败。
     */
    public SurveySnapshot() {
    }

    /** 便捷构造器（生成快照时用，省三次 setter）。 */
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
