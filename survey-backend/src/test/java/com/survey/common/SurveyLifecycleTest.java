package com.survey.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生命周期映射的**唯一口径**：status + deleted → lifecycle。
 * ★ 这条映射同时被"历史数据回填 SQL""三个状态写入 SQL""对账 SQL"使用，
 *   口径一变四处同时漂 —— 所以用测试钉死在 Java 侧。
 */
class SurveyLifecycleTest {

    @Test
    @DisplayName("status=0 / NULL → DRAFT")
    void draft() {
        assertEquals(SurveyLifecycle.DRAFT, SurveyLifecycle.of(0, 0));
        assertEquals(SurveyLifecycle.DRAFT, SurveyLifecycle.of(null, 0));
        assertEquals(SurveyLifecycle.DRAFT, SurveyLifecycle.of(0, null));
    }

    @Test
    @DisplayName("status=1 → PUBLISHED；status=2 → ENDED")
    void publishedAndEnded() {
        assertEquals(SurveyLifecycle.PUBLISHED, SurveyLifecycle.of(1, 0));
        assertEquals(SurveyLifecycle.ENDED, SurveyLifecycle.of(2, 0));
    }

    @Test
    @DisplayName("★ 回收站优先：deleted≠0 时不管 status 都是 ARCHIVED（删进回收站不改 status）")
    void archivedWins() {
        assertEquals(SurveyLifecycle.ARCHIVED, SurveyLifecycle.of(1, 1));
        assertEquals(SurveyLifecycle.ARCHIVED, SurveyLifecycle.of(2, 1));
        assertEquals(SurveyLifecycle.ARCHIVED, SurveyLifecycle.of(0, 1));
    }

    @Test
    @DisplayName("fromCode：未知值返回 null（脏数据不静默当 DRAFT）")
    void fromCodeIsStrict() {
        assertEquals(SurveyLifecycle.PUBLISHED, SurveyLifecycle.fromCode("PUBLISHED"));
        assertNull(SurveyLifecycle.fromCode(null));
        assertNull(SurveyLifecycle.fromCode("published"), "不做大小写归一");
        assertNull(SurveyLifecycle.fromCode("DELETED"));
    }
}
