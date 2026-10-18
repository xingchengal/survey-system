package com.survey.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.survey.common.BizException;
import com.survey.common.QuestionType;
import com.survey.dto.SurveySnapshot;
import com.survey.dto.SurveyVersionDiff;
import com.survey.dto.SurveyVersionSummary;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.entity.SurveyVersion;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 问卷版本快照：编辑历史、版本对比、回滚时读快照。
 *
 * ── 为什么要有它（背景）────────────────────────────────
 *   题目保存是**全量覆盖**语义（`QuestionService.saveQuestions`：没传的题会被删掉），
 *   一次误操作就可能把整份问卷的题目结构改掉，且**没有任何历史可查**。
 *   这里把每次"题目结构真的变了"的保存留一份快照，提供三件事：
 *   编辑历史（列表）、版本对比（结构化 diff）、回滚（把旧结构写回去）。
 *
 * ── 版本什么时候生成（★ 改这里之前先读）────────────────
 *   · 只在**题目保存**（`saveQuestions`）时生成，且**内容没变就不生成** ——
 *     用户点十次"保存"但一个字没改，不该产生十个版本；
 *   · 改标题/描述**不生成**版本（否则每改一次标题就多一版，版本会爆炸）。
 *     但每个快照里都带了当时的标题/描述，所以对比两个版本时仍能看到"标题当时是什么"；
 *   · **回滚也算一次编辑**：回滚后同样生成一个新版本（摘要里写明"回滚到 v3"），
 *     历史是线性的、可审计的 —— 回滚不是"抹掉中间几版"。
 *
 * ── 每份问卷最多保留 50 个版本 ──────────────────────────
 *   超出后删**最旧**的（`deleteUpTo`）。版本号不会因此回退（它是业务号，不是行号），
 *   所以用户看到的历史是"v47…v96"这样的滚动窗口，而不是永远从 v1 开始。
 *
 * ── 为什么本类不注入 QuestionService ────────────────────
 *   那会形成环：QuestionService →（保存后生成快照）→ SurveyVersionService →（回滚要写题目）→ QuestionService。
 *   所以分工是：**本类只负责"读写快照"**，回滚的写库动作留在 `QuestionService.rollbackToVersion`
 *   （它本来就拥有"全量覆盖式保存"的全部校验逻辑，回滚也必须走同一套）。
 */
@Service
public class SurveyVersionService {

    private static final Logger LOG = LoggerFactory.getLogger(SurveyVersionService.class);

    /** 每份问卷最多保留多少个版本（超出删最旧的）。 */
    private static final int MAX_VERSIONS_PER_SURVEY = 50;

    /** `change_summary` 的列宽（varchar(500)），超了会被 MySQL 拒绝 → 必须自己截断。 */
    private static final int MAX_SUMMARY_LENGTH = 500;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 快照 JSON 的编解码器。
     * ★ 关掉"未知字段报错"：这是**持久化格式**，将来给 Question 加字段时，
     *   旧快照必须还能被新代码读出来（否则一升级，历史版本全部反序列化失败）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private SurveyVersionMapper versionMapper;

    @Autowired
    private SurveyMapper surveyMapper;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private OptionMapper optionMapper;

    // ==================== 生成快照 ====================

    /**
     * 为问卷生成一个版本快照。**必须在保存题目的同一事务内调用**（`QuestionService` 里调用）——
     * 保存成功而快照失败，或快照成功而保存回滚，都会让历史与实际结构对不上。
     *
     * @param surveyId 问卷 id
     * @param userId   操作人（回滚时是执行回滚的人）
     * @param note     变更说明前缀（回滚时传「回滚到 v3」），可为 null
     * @return 新生成的版本；**题目结构与上一版完全相同时返回 null**（不生成噪音版本）
     */
    @Transactional
    public SurveyVersion capture(Integer surveyId, Integer userId, String note) {
        Survey survey = surveyMapper.findById(surveyId);
        if (survey == null) {
            // 防御性：正常调用发生在保存题目之后，问卷必然存在
            return null;
        }
        List<Question> questions = loadQuestionsForSnapshot(surveyId);
        String json = writeJson(new SurveySnapshot(survey.getTitle(), survey.getDescription(), questions));

        // ★ 只比题目结构（questionsJson），不比标题 —— 改标题不触发版本，见类注释。
        SurveyVersion latest = versionMapper.findLatest(surveyId);
        if (latest != null && json.equals(latest.getQuestionsJson())) {
            return null;
        }

        SurveyVersion version = new SurveyVersion();
        version.setSurveyId(surveyId);
        version.setVersionNo(versionMapper.findMaxVersionNo(surveyId) + 1);
        version.setTitle(survey.getTitle());
        version.setDescription(survey.getDescription());
        version.setQuestionCount(questions.size());
        version.setQuestionsJson(json);
        version.setChangeSummary(summarize(latest, questions, note));
        version.setCreatedBy(userId);

        try {
            versionMapper.insert(version);
        } catch (DuplicateKeyException e) {
            // 同一份问卷被两个请求同时保存：都算出了同一个 version_no，后到的撞上唯一键。
            // ★ 重新取号再插一次即可（内容不同是正常业务，不是错误）。
            version.setVersionNo(versionMapper.findMaxVersionNo(surveyId) + 1);
            versionMapper.insert(version);
        }

        trimOldVersions(surveyId);
        LOG.info("生成问卷版本快照：surveyId={} v{}（{} 题）{}",
                surveyId, version.getVersionNo(), questions.size(),
                version.getChangeSummary() == null ? "" : "，" + version.getChangeSummary());
        return version;
    }

    // ==================== 查询 ====================

    /** 版本列表（新的在前，**不含快照 JSON**）。 */
    public List<SurveyVersionSummary> listVersions(Integer surveyId) {
        return versionMapper.findBySurveyId(surveyId).stream()
                .map(v -> new SurveyVersionSummary(
                        v.getId(), v.getVersionNo(), v.getTitle(), v.getQuestionCount(),
                        v.getChangeSummary(), v.getCreatedBy(),
                        v.getCreatedTime() == null ? null : v.getCreatedTime().format(TIME_FMT)))
                .collect(Collectors.toList());
    }

    /** 读单个版本（**含 JSON**）。 */
    public SurveyVersion getById(Integer id) {
        return id == null ? null : versionMapper.findById(id);
    }

    /** 最新一版（**含 JSON**）—— 回滚后要把它返回给调用方（"回滚产生的那个版本"）。 */
    public SurveyVersion getLatest(Integer surveyId) {
        return versionMapper.findLatest(surveyId);
    }

    /** 取版本 id，不存在就 400（Controller 与回滚都用它，避免各处判空）。 */
    public SurveyVersion requireById(Integer id) {
        SurveyVersion version = getById(id);
        if (version == null) {
            throw new BizException("版本不存在");
        }
        return version;
    }

    /** 反序列化某个版本的题目快照（回滚用）。 */
    public SurveySnapshot loadSnapshot(SurveyVersion version) {
        if (version == null || version.getQuestionsJson() == null || version.getQuestionsJson().isBlank()) {
            throw new BizException("该版本没有可用的题目内容");
        }
        return readJson(version.getQuestionsJson());
    }

    // ==================== 对比 ====================

    /**
     * 对比两个版本（结构化 diff）。两个版本必须属于同一份问卷。
     *
     * ★ 按 `questionId` 对齐：`saveQuestions` 是按 id 保留/更新的，
     *   同一道题在两个版本里 id 稳定，所以能区分"改了"与"删了+新增"。
     */
    public SurveyVersionDiff diff(Integer fromId, Integer toId) {
        SurveyVersion from = requireById(fromId);
        SurveyVersion to = requireById(toId);
        if (!Objects.equals(from.getSurveyId(), to.getSurveyId())) {
            throw new BizException("只能对比同一份问卷的两个版本");
        }
        SurveySnapshot before = loadSnapshot(from);
        SurveySnapshot after = loadSnapshot(to);
        Map<Integer, Question> beforeMap = indexById(before.getQuestions());
        Map<Integer, Question> afterMap = indexById(after.getQuestions());

        List<SurveyVersionDiff.QuestionChange> added = new ArrayList<>();
        List<SurveyVersionDiff.QuestionChange> removed = new ArrayList<>();
        List<SurveyVersionDiff.QuestionChange> modified = new ArrayList<>();

        // 新增 / 修改：按"目标版本"的题目顺序遍历（前端的展示顺序）
        for (Question q : safeList(after.getQuestions())) {
            Question old = beforeMap.get(q.getId());
            if (old == null) {
                added.add(new SurveyVersionDiff.QuestionChange(q.getId(), q.getTitle(), List.of("新增")));
            } else {
                List<String> changes = describeChanges(old, q);
                if (!changes.isEmpty()) {
                    modified.add(new SurveyVersionDiff.QuestionChange(q.getId(), q.getTitle(), changes));
                }
            }
        }
        // 删除：起始版本里有、目标版本里没有
        for (Question q : safeList(before.getQuestions())) {
            if (!afterMap.containsKey(q.getId())) {
                removed.add(new SurveyVersionDiff.QuestionChange(q.getId(), q.getTitle(), List.of("删除")));
            }
        }

        return new SurveyVersionDiff(
                from.getId(), to.getId(),
                from.getVersionNo(), to.getVersionNo(),
                before.getTitle(), after.getTitle(),
                !Objects.equals(before.getTitle(), after.getTitle()),
                added, removed, modified);
    }

    // ==================== 内部实现 ====================

    /**
     * 读出用于快照的题目（含选项）。
     * ★ 这里**不能**用 `QuestionService.getQuestionsBySurveyId`（会形成循环依赖，见类注释），
     *   所以自己查：1 次查题目 + 每道选择题 1 次查选项。
     *   题目数有上限（`MAX_QUESTIONS_PER_SURVEY = 200`），且只在保存这一条路径上发生，可接受。
     */
    private List<Question> loadQuestionsForSnapshot(Integer surveyId) {
        List<Question> questions = questionMapper.findBySurveyId(surveyId);
        for (Question q : questions) {
            if (QuestionType.hasOptions(q.getType())) {
                q.setOptions(optionMapper.findByQuestionId(q.getId()));
            }
        }
        return questions;
    }

    /** 生成变更摘要（相对上一版），带上可选的前缀（回滚时用）。 */
    private String summarize(SurveyVersion latest, List<Question> current, String note) {
        StringBuilder sb = new StringBuilder();
        if (note != null && !note.isBlank()) {
            sb.append(note).append("：");
        }
        if (latest == null) {
            sb.append("初始版本（").append(current.size()).append(" 题）");
            return fit(sb.toString());
        }
        Map<Integer, Question> before = indexById(readJson(latest.getQuestionsJson()).getQuestions());
        Map<Integer, Question> now = indexById(current);

        int added = 0;
        int removed = 0;
        int modified = 0;
        for (Question q : current) {
            Question old = before.get(q.getId());
            if (old == null) {
                added++;
            } else if (!describeChanges(old, q).isEmpty()) {
                modified++;
            }
        }
        for (Integer id : before.keySet()) {
            if (!now.containsKey(id)) {
                removed++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (added > 0) parts.add("新增 " + added + " 题");
        if (removed > 0) parts.add("删除 " + removed + " 题");
        if (modified > 0) parts.add("修改 " + modified + " 题");
        if (parts.isEmpty()) parts.add("内容有变化");
        sb.append(String.join("、", parts)).append("（共 ").append(current.size()).append(" 题）");
        return fit(sb.toString());
    }

    /**
     * 描述两道"同 id 题目"之间的差异。
     *
     * @return 人类可读的变更点；空列表 = 完全没变
     */
    private List<String> describeChanges(Question a, Question b) {
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(a.getType(), b.getType())) {
            changes.add("题型变化");
        }
        if (!Objects.equals(a.getTitle(), b.getTitle())) {
            changes.add("题干修改");
        }
        if (!Objects.equals(a.getRequired(), b.getRequired())) {
            changes.add("必填设置变化");
        }
        if (!Objects.equals(a.getSortOrder(), b.getSortOrder())) {
            changes.add("位置：第 " + (safeOrder(a) + 1) + " 题 → 第 " + (safeOrder(b) + 1) + " 题");
        }
        if (!optionsSignature(a).equals(optionsSignature(b))) {
            changes.add("选项变化");
        }
        // ★ 矩阵题的行列标题也要参与差异判定：只改行列、不改题干时，
        //   不比较这两列就会出现"保存了、版本没生成"以及对比页显示"无变化"。
        if (!Objects.equals(a.getMatrixRows(), b.getMatrixRows())
                || !Objects.equals(a.getMatrixCols(), b.getMatrixCols())) {
            changes.add("矩阵行列变化");
        }
        return changes;
    }

    /**
     * 选项的"内容签名"：按 sortOrder 排序后把 `内容->跳转目标` 串起来。
     * ★ 必须把 `nextQuestionId` 也算进去 —— 只改跳转规则不改文案，也是**实质变更**。
     */
    private String optionsSignature(Question q) {
        if (q.getOptions() == null || q.getOptions().isEmpty()) {
            return "";
        }
        return q.getOptions().stream()
                .sorted(Comparator.comparingInt(o -> o.getSortOrder() == null ? 0 : o.getSortOrder()))
                .map(o -> o.getContent() + "->" + o.getNextQuestionId())
                .collect(Collectors.joining("|"));
    }

    private static int safeOrder(Question q) {
        return q.getSortOrder() == null ? 0 : q.getSortOrder();
    }

    private static Map<Integer, Question> indexById(List<Question> questions) {
        Map<Integer, Question> map = new LinkedHashMap<>();
        for (Question q : safeList(questions)) {
            if (q.getId() != null) {
                map.put(q.getId(), q);
            }
        }
        return map;
    }

    /** 老快照可能没有 questions 字段（防御）。 */
    private static List<Question> safeList(List<Question> questions) {
        return questions == null ? List.of() : questions;
    }

    /** 裁剪：只保留最新 N 个版本。 */
    private void trimOldVersions(Integer surveyId) {
        int max = versionMapper.findMaxVersionNo(surveyId);
        if (max > MAX_VERSIONS_PER_SURVEY) {
            int deleted = versionMapper.deleteUpTo(surveyId, max - MAX_VERSIONS_PER_SURVEY);
            if (deleted > 0) {
                LOG.info("版本裁剪：问卷 {} 删掉 {} 个最旧版本（只保留最新 {} 个）",
                        surveyId, deleted, MAX_VERSIONS_PER_SURVEY);
            }
        }
    }

    private String fit(String summary) {
        if (summary == null) {
            return null;
        }
        return summary.length() <= MAX_SUMMARY_LENGTH
                ? summary
                : summary.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            // 系统级故障（序列化不可用），不是业务失败 → 500
            throw new IllegalStateException("版本快照序列化失败", e);
        }
    }

    private SurveySnapshot readJson(String json) {
        try {
            return MAPPER.readValue(json, SurveySnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("版本快照反序列化失败", e);
        }
    }
}
