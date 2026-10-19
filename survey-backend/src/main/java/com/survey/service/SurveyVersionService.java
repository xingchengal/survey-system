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
 * 问卷版本快照：编辑历史、版本对比、回滚。
 * ★ 只在题目结构真的变了时生成：改标题/描述不生成（否则版本爆炸），内容没变重复保存也不生成。
 * 每份问卷最多 50 版，超出删最旧；版本号是业务号不回退，历史呈"v47…v96"滚动窗口。
 * ★ 回滚 = 全量覆盖写回 + 生成新版本；跳转指向会被重建的题时 QuestionService 拒绝回滚，避免旧 id 悬空。
 * 不注入 QuestionService（会成环），本类只读写快照；回滚写库在 QuestionService.rollbackToVersion。
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
     * 快照 JSON 编解码器。
     * 关掉未知字段报错：这是持久化格式，给 Question 加字段后旧快照必须还能读出来。
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
     * 为问卷生成版本快照；必须在保存题目的同一事务内调用（否则历史与实际结构对不上）。
     *
     * @param userId 操作人（回滚时是执行回滚的人）
     * @param note   变更说明前缀（回滚时传「回滚到 v3」），可为 null
     * @return 新版本；题目结构没变时返回 null（不生成噪音版本）
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

        // 只比题目结构（questionsJson），不比标题 —— 改标题不触发版本，见类注释。
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
            // 并发保存算出同一个 version_no 撞唯一键：重新取号再插一次，内容不同是正常业务。
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
     * 对比两个版本（结构化 diff）；两个版本必须属于同一份问卷。
     * 按 questionId 对齐：saveQuestions 按 id 保留/更新，同一道题 id 稳定，能区分"改了"与"删了+新增"。
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
     * 不能复用 QuestionService.getQuestionsBySurveyId（循环依赖），所以自己查：1 次题目 + 每道选择题 1 次选项。
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
        // 分值也要参与差异判定，否则回滚会悄悄退回旧分值，对比页却显示"无变化"。
        if (!Objects.equals(a.getScore(), b.getScore())) {
            changes.add("分值变化");
        }
        if (!Objects.equals(a.getSortOrder(), b.getSortOrder())) {
            changes.add("位置：第 " + (safeOrder(a) + 1) + " 题 → 第 " + (safeOrder(b) + 1) + " 题");
        }
        if (!optionsSignature(a).equals(optionsSignature(b))) {
            changes.add("选项变化");
        }
        // 矩阵行列标题也要参与差异判定，否则只改行列会出现"保存了、版本没生成"。
        if (!Objects.equals(a.getMatrixRows(), b.getMatrixRows())
                || !Objects.equals(a.getMatrixCols(), b.getMatrixCols())) {
            changes.add("矩阵行列变化");
        }
        return changes;
    }

    /**
     * 选项的内容签名：按 sortOrder 排序后把「内容->跳转目标」串起来。
     * 必须算 nextQuestionId（只改跳转会变实质变更）与 isCorrect（否则对比显示无变化、回滚还原标记）。
     */
    private String optionsSignature(Question q) {
        if (q.getOptions() == null || q.getOptions().isEmpty()) {
            return "";
        }
        return q.getOptions().stream()
                .sorted(Comparator.comparingInt(o -> o.getSortOrder() == null ? 0 : o.getSortOrder()))
                .map(o -> o.getContent() + "->" + o.getNextQuestionId()
                        + (o.getIsCorrect() != null && o.getIsCorrect() == 1 ? "#correct" : ""))
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
