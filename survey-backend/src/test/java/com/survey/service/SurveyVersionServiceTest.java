package com.survey.service;

import com.survey.common.BizException;
import com.survey.dto.SurveySnapshot;
import com.survey.dto.SurveyVersionDiff;
import com.survey.dto.SurveyVersionSummary;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.entity.SurveyVersion;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * {@link SurveyVersionService} 的单元测试（不连库）。
 *
 * ── 钉住的四条语义（都是"改着改着就没了"的那种）────────────
 *   1. **内容没变不生成版本** —— 否则用户点十次"保存"就是十个空版本；
 *   2. **只在题目结构变化时生成** —— 改标题不该刷版本（快照里仍带当时的标题，供对比）；
 *   3. **超过 50 版删最旧** —— 否则一份问卷的历史能无限膨胀；
 *   4. **diff 按 questionId 对齐** —— 不能按行号，否则"中间插一道题"会渲染成"后面每道题都变了"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SurveyVersionServiceTest {

    private static final Integer SURVEY_ID = 100;

    @Mock
    private SurveyVersionMapper versionMapper;
    @Mock
    private SurveyMapper surveyMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private OptionMapper optionMapper;

    @InjectMocks
    private SurveyVersionService service;

    // ==================== 夹具 ====================

    private static Survey survey() {
        Survey s = new Survey();
        s.setId(SURVEY_ID);
        s.setUserId(1);
        s.setTitle("版本测试问卷");
        s.setDescription("描述");
        return s;
    }

    private static Question single(int id, String title, int sortOrder, String... optionTexts) {
        Question q = new Question();
        q.setId(id);
        q.setSurveyId(SURVEY_ID);
        q.setType(1);
        q.setTitle(title);
        q.setRequired(1);
        q.setSortOrder(sortOrder);
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < optionTexts.length; i++) {
            Option o = new Option();
            o.setId(1000 + i);
            o.setQuestionId(id);
            o.setContent(optionTexts[i]);
            o.setSortOrder(i);
            options.add(o);
        }
        q.setOptions(options);
        return q;
    }

    /** 让 `insert` 回填 id 与创建时间（模拟 useGeneratedKeys），否则拿不到版本 id。 */
    private void givenInsertWiring() {
        when(surveyMapper.findById(SURVEY_ID)).thenReturn(survey());
        when(versionMapper.insert(any(SurveyVersion.class))).thenAnswer(inv -> {
            SurveyVersion v = inv.getArgument(0);
            if (v.getId() == null) {
                v.setId(1000 + v.getVersionNo());
            }
            v.setCreatedTime(LocalDateTime.now());
            return 1;
        });
    }

    /**
     * stub：库里现在有这些题，且**每道题的选项都能按 id 查回来**。
     * ★ 不能只 stub 题目：`capture` 会为每道选择题重新查一次选项表，
     *   不 stub 的话快照里的 options 会是空数组（真实的"选项全丢"就长这样）。
     */
    private void givenQuestions(Question... questions) {
        List<Question> list = List.of(questions);
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(list);
        for (Question q : list) {
            when(optionMapper.findByQuestionId(q.getId())).thenReturn(q.getOptions());
        }
    }

    // ==================== 生成 ====================

    @Test
    @DisplayName("首次保存：生成 v1，摘要写明'初始版本'与题数")
    void firstCaptureCreatesV1() {
        givenInsertWiring();
        when(versionMapper.findMaxVersionNo(SURVEY_ID)).thenReturn(0);
        when(versionMapper.findLatest(SURVEY_ID)).thenReturn(null);
        givenQuestions(single(1, "题一", 0, "A", "B"));

        SurveyVersion v = service.capture(SURVEY_ID, 7, null);

        assertNotNull(v);
        assertEquals(1, v.getVersionNo());
        assertEquals(1, v.getQuestionCount());
        assertEquals(7, v.getCreatedBy());
        assertTrue(v.getChangeSummary().contains("初始版本"), "实际摘要：" + v.getChangeSummary());
        assertTrue(v.getQuestionsJson().contains("题一"), "快照里应当有题目内容");
        verify(versionMapper).insert(any(SurveyVersion.class));
    }

    @Test
    @DisplayName("★ 题目结构没变：不生成新版本（重复点保存不该刷版本）")
    void captureSkipsWhenNothingChanged() {
        givenInsertWiring();
        when(versionMapper.findMaxVersionNo(SURVEY_ID)).thenReturn(0);
        givenQuestions(single(1, "题一", 0, "A", "B"));

        when(versionMapper.findLatest(SURVEY_ID)).thenReturn(null);
        SurveyVersion first = service.capture(SURVEY_ID, 7, null);
        assertNotNull(first);

        // 再存一次（内容一模一样）
        when(versionMapper.findLatest(SURVEY_ID)).thenReturn(first);
        SurveyVersion second = service.capture(SURVEY_ID, 7, null);

        assertNull(second, "内容没变时不应生成版本");
        verify(versionMapper, times(1)).insert(any(SurveyVersion.class));
    }

    @Test
    @DisplayName("题目变化：生成 v2，摘要里写明新增/修改的题数")
    void captureSummarizesChanges() {
        givenInsertWiring();
        // 第一次算号 0（v1），裁剪查询 1；第二次算号 1（v2），裁剪查询 2
        when(versionMapper.findMaxVersionNo(SURVEY_ID)).thenReturn(0, 1, 1, 2);
        when(versionMapper.findLatest(SURVEY_ID)).thenReturn(null);
        givenQuestions(single(1, "题一", 0, "A", "B"));
        SurveyVersion v1 = service.capture(SURVEY_ID, 7, null);
        assertNotNull(v1);

        when(versionMapper.findLatest(SURVEY_ID)).thenReturn(v1);
        givenQuestions(single(1, "题一（改）", 0, "A", "B"), single(2, "题二", 1, "C"));
        SurveyVersion v2 = service.capture(SURVEY_ID, 7, null);

        assertNotNull(v2);
        assertEquals(2, v2.getVersionNo());
        assertEquals(2, v2.getQuestionCount());
        assertTrue(v2.getChangeSummary().contains("新增 1 题"), "实际：" + v2.getChangeSummary());
        assertTrue(v2.getChangeSummary().contains("修改 1 题"), "实际：" + v2.getChangeSummary());
    }

    @Test
    @DisplayName("超过 50 版：按版本号阈值删最旧的（不是按行数）")
    void captureTrimsOldVersions() {
        givenInsertWiring();
        // 算号时 max=51 ⇒ 新版本 52；裁剪时 max=52 ⇒ 删 <= 2
        when(versionMapper.findMaxVersionNo(SURVEY_ID)).thenReturn(51, 52);
        when(versionMapper.findLatest(SURVEY_ID)).thenReturn(null);
        givenQuestions(single(1, "题一", 0, "A"));

        service.capture(SURVEY_ID, 7, null);

        verify(versionMapper).deleteUpTo(SURVEY_ID, 2);
    }

    // ==================== 对比 ====================

    @Test
    @DisplayName("diff：按 questionId 对齐，区分新增/删除/修改（含题干变化）")
    void diffAlignsByQuestionId() {
        givenInsertWiring();
        when(versionMapper.findMaxVersionNo(SURVEY_ID)).thenReturn(0, 0, 1, 1);
        when(versionMapper.findLatest(SURVEY_ID)).thenReturn(null);

        // v1：题一、题二
        givenQuestions(single(1, "题一", 0, "A"), single(2, "题二", 1, "B"));
        SurveyVersion v1 = service.capture(SURVEY_ID, 7, null);
        assertNotNull(v1);

        // v2：题一改题干、题二被删、新增题三
        when(versionMapper.findLatest(SURVEY_ID)).thenReturn(v1);
        givenQuestions(single(1, "题一改", 0, "A"), single(3, "题三", 1, "C"));
        SurveyVersion v2 = service.capture(SURVEY_ID, 7, null);
        assertNotNull(v2);

        when(versionMapper.findById(v1.getId())).thenReturn(v1);
        when(versionMapper.findById(v2.getId())).thenReturn(v2);
        SurveyVersionDiff diff = service.diff(v1.getId(), v2.getId());

        assertEquals(1, diff.added().size(), "新增：题三");
        assertEquals(3, diff.added().get(0).questionId());
        assertEquals(1, diff.removed().size(), "删除：题二");
        assertEquals(2, diff.removed().get(0).questionId());
        assertEquals(1, diff.modified().size(), "修改：题一");
        assertTrue(diff.modified().get(0).changes().contains("题干修改"));
    }

    @Test
    @DisplayName("diff：跨问卷的两个版本被拒绝")
    void diffRejectsDifferentSurveys() {
        SurveyVersion a = new SurveyVersion();
        a.setId(1);
        a.setSurveyId(100);
        a.setVersionNo(1);
        a.setQuestionsJson("{\"questions\":[]}");
        SurveyVersion b = new SurveyVersion();
        b.setId(2);
        b.setSurveyId(200);
        b.setVersionNo(1);
        b.setQuestionsJson("{\"questions\":[]}");
        when(versionMapper.findById(1)).thenReturn(a);
        when(versionMapper.findById(2)).thenReturn(b);

        assertThrows(BizException.class, () -> service.diff(1, 2));
    }

    // ==================== 读快照 ====================

    @Test
    @DisplayName("loadSnapshot：能把 JSON 反序列化回题目与选项（回滚依赖它）")
    void loadSnapshotRoundTrips() {
        givenInsertWiring();
        when(versionMapper.findMaxVersionNo(SURVEY_ID)).thenReturn(0);
        when(versionMapper.findLatest(SURVEY_ID)).thenReturn(null);
        givenQuestions(single(1, "题一", 0, "A", "B"));

        SurveyVersion v = service.capture(SURVEY_ID, 7, null);
        assertNotNull(v);

        SurveySnapshot snapshot = service.loadSnapshot(v);
        assertEquals("版本测试问卷", snapshot.getTitle());
        assertEquals(1, snapshot.getQuestions().size());
        assertEquals("题一", snapshot.getQuestions().get(0).getTitle());
        assertEquals(2, snapshot.getQuestions().get(0).getOptions().size());
    }

    @Test
    @DisplayName("listVersions：返回摘要（不含 JSON），时间格式化为 yyyy-MM-dd HH:mm:ss")
    void listVersionsFormatsTime() {
        SurveyVersion v = new SurveyVersion();
        v.setId(1);
        v.setVersionNo(3);
        v.setTitle("t");
        v.setQuestionCount(2);
        v.setChangeSummary("新增 1 题");
        v.setCreatedBy(7);
        v.setCreatedTime(LocalDateTime.of(2026, 10, 17, 21, 30, 5));
        when(versionMapper.findBySurveyId(SURVEY_ID)).thenReturn(List.of(v));

        List<SurveyVersionSummary> list = service.listVersions(SURVEY_ID);

        assertEquals(1, list.size());
        assertEquals("2026-10-17 21:30:05", list.get(0).createdTime());
        assertEquals(3, list.get(0).versionNo());
    }

    @Test
    @DisplayName("快照 JSON 里必须有选项（否则回滚会把选项全丢掉）")
    void snapshotContainsOptions() {
        givenInsertWiring();
        when(versionMapper.findMaxVersionNo(SURVEY_ID)).thenReturn(0);
        when(versionMapper.findLatest(SURVEY_ID)).thenReturn(null);
        givenQuestions(single(1, "题一", 0, "A", "B"));

        SurveyVersion v = service.capture(SURVEY_ID, 7, null);

        assertNotNull(v);
        assertTrue(v.getQuestionsJson().contains("\"options\""), "快照里缺 options：" + v.getQuestionsJson());
        assertTrue(v.getQuestionsJson().contains("\"A\""));
        verify(optionMapper, atLeastOnce()).findByQuestionId(anyInt());
    }
}
