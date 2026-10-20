package com.survey.service;

import com.survey.common.Grader;
import com.survey.common.MatrixConfig;
import com.survey.common.QuestionType;
import com.survey.dto.QuestionIdItem;
import com.survey.dto.SurveySnapshot;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.entity.SurveyVersion;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.common.TextLimits;
import com.survey.common.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 题目业务：全量覆盖式保存题目与选项、查询题目（带选项）、版本回滚。
 * 涉及 t_question / t_option（选项里的 nextQuestionId 就是逻辑跳转规则）。
 * ★ 保存是全量覆盖：按数组重排 sortOrder、带 id 更新/不带 id 插入、选项先删后插、本次没传的题删除。
 * 跳转目标必须属于本问卷且本次存活，否则 400 不落库；回滚遇到"目标会被重建"同样拒绝。
 * 调用方：QuestionController（保存/查询）、AnswerController（getById 反查 surveyId 做归属校验）。
 */
@Service
public class QuestionService {

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private OptionMapper optionMapper;

    /**
     * 版本快照（保存后生成、回滚时读取）。
     * 依赖单向：QuestionService → SurveyVersionService；回滚的写库动作留在本类，必须走同一套校验。
     */
    @Autowired
    private SurveyVersionService surveyVersionService;

    /**
     * 进阶题型包计费（矩阵 / 排序 / NPS）。
     * 必须在**本类的保存事务内**调用：否则"扣费成功、题目保存校验失败"会扣了钱却没存上题。
     */
    @Autowired
    private WalletService walletService;

    /** 只用来取"问卷作者是谁" —— 扣的是作者的额度（协作者代改也一样）。 */
    @Autowired
    private com.survey.mapper.SurveyMapper surveyMapper;

    /** 一份问卷最多多少道题（防"一次保存塞几万道题"把公开的题目接口与导出打垮） */
    private static final int MAX_QUESTIONS_PER_SURVEY = 200;

    /** 单题最多多少个选项 */
    private static final int MAX_OPTIONS_PER_QUESTION = 50;

    /**
     * 保存问卷的所有题目（全量覆盖：本次没传的旧题会被删掉）。
     * 事务：题目与选项多表多次写，中途失败整体回滚；归属校验在 QuestionController（查出问卷再比对登录用户）。
     *
     * @param questions 完整题目数组（含 options 与 nextQuestionId：真 question.id / -1=结束 / null=顺序）
     * @return 按下标一一对应的 [{id, sortOrder}]：第 i 项是本次提交第 i 项的真实 id，
     *         前端用它把草稿里的临时负数 id 换成真 id（否则新建的题设不了跳转目标）
     */
    @Transactional
    public List<QuestionIdItem> saveQuestions(Integer surveyId, List<Question> questions) {
        return doSaveQuestions(surveyId, questions, null, null);
    }

    /**
     * 保存题目（带操作人）：版本快照的 created_by 用它。
     * 两个重载各自标 @Transactional 并各自调 doSaveQuestions —— 互相调用会让事务注解失效。
     */
    @Transactional
    public List<QuestionIdItem> saveQuestions(Integer surveyId, List<Question> questions, Integer userId) {
        return doSaveQuestions(surveyId, questions, userId, null);
    }

    /**
     * 把问卷回滚到某个历史版本（回滚也是一次编辑：产生新版本，历史不会被抹掉）。
     * ★ 跳转规则指向"会被重新创建的题"时拒绝回滚：那题会拿到新 id，快照里的旧 id 会变成悬空规则，
     * 所以直接 400 并说清原因，不静默把规则置空（那会让用户以为回滚成功了）。
     *
     * @param version 已过归属校验的版本（Controller 传入，避免重复查库）
     * @return 回滚产生的新版本
     */
    @Transactional
    public SurveyVersion rollbackToVersion(SurveyVersion version, Integer userId) {
        Integer surveyId = version.getSurveyId();
        SurveySnapshot snapshot = surveyVersionService.loadSnapshot(version);
        List<Question> restored = snapshot.getQuestions() == null
                ? new ArrayList<>()
                : new ArrayList<>(snapshot.getQuestions());

        // 当前库里还存在的题目 id
        Set<Integer> existingIds = questionMapper.findBySurveyId(surveyId).stream()
                .map(Question::getId)
                .collect(Collectors.toSet());
        // 回滚时需要**重新插入**的题目（快照里有、库里没有）—— 它们的 id 会变
        Set<Integer> willBeRecreated = restored.stream()
                .map(Question::getId)
                .filter(Objects::nonNull)
                .filter(id -> !existingIds.contains(id))
                .collect(Collectors.toSet());

        for (Question q : restored) {
            if (q.getOptions() == null) {
                continue;
            }
            for (Option opt : q.getOptions()) {
                if (opt.getNextQuestionId() != null && willBeRecreated.contains(opt.getNextQuestionId())) {
                    throw new BizException(
                            "无法回滚到 v" + version.getVersionNo() + "：该版本的跳转规则指向一道在后续版本里"
                                    + "已被删除的题目，回滚会重新创建它并分配新的 id，原规则会变成悬空规则。"
                                    + "请手工调整该题的跳转，或把这一版另存为新问卷。");
                }
            }
        }

        // 只清"库里已没有的题"的 id（它们本来就要重建）；仍存在的题保留 id，
        // 否则历史答卷的 question_id 会全部悬空。
        for (Question q : restored) {
            if (q.getId() != null && !existingIds.contains(q.getId())) {
                q.setId(null);
            }
            if (q.getOptions() != null) {
                for (Option opt : q.getOptions()) {
                    opt.setId(null);   // 选项本来就是"全删重插"（saveQuestions 的既有语义）
                }
            }
        }

        // 回滚不做"选项内容非空"校验（validateOptions=false）：历史快照里可能就带空选项，
        // 要求回滚满足新校验等于让这些版本永远回滚不回来；恢复后作者再保存才走严格校验。
        doSaveQuestions(surveyId, restored, userId, "回滚到 v" + version.getVersionNo(), false);

        SurveyVersion latest = surveyVersionService.getLatest(surveyId);
        if (latest == null) {
            throw new IllegalStateException("回滚后未能生成版本快照");
        }
        return latest;
    }

    private List<QuestionIdItem> doSaveQuestions(Integer surveyId, List<Question> questions,
                                                 Integer userId, String note) {
        // 默认走严格校验（用户保存）；回滚传 false，理由见 rollbackToVersion 的调用点注释
        return doSaveQuestions(surveyId, questions, userId, note, true);
    }

    /**
     * @param validateOptions 是否校验"选项非空/互不相同"等；只有用户保存该为 true，回滚是恢复历史快照不是新输入。
     */
    private List<QuestionIdItem> doSaveQuestions(Integer surveyId, List<Question> questions,
                                                 Integer userId, String note, boolean validateOptions) {
        // 乱序 / 抽题与逻辑跳转互斥：跳转按题目位置解析，打乱或抽掉目标会让流程失效。
        // 设置页保存时已拒绝“先有跳转再开乱序”，这里挡住反向顺序（先开乱序、后加跳转）。
        Survey surveyForOrder = surveyMapper.findById(surveyId);
        boolean orderChanged = surveyForOrder != null
                && ((surveyForOrder.getShuffleQuestions() != null && surveyForOrder.getShuffleQuestions() == 1)
                || (surveyForOrder.getShuffleOptions() != null && surveyForOrder.getShuffleOptions() == 1)
                || (surveyForOrder.getQuestionsPerAttempt() != null && surveyForOrder.getQuestionsPerAttempt() > 0));
        if (orderChanged && hasJumpRule(questions)) {
            throw new BizException("问卷已开启乱序 / 随机抽题，请先在设置里关闭，再设置逻辑跳转");
        }

        // 题目与选项条数必须有上限：请求体 4MB 而一道题只有几十字节，一次能塞几万道题，
        // 而公开的题目接口（1+N SQL）、Excel 导出（列上限 16384）、提交答卷都会因此被放大。
        if (questions.size() > MAX_QUESTIONS_PER_SURVEY) {
            throw new BizException("一份问卷最多 " + MAX_QUESTIONS_PER_SURVEY + " 道题");
        }
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            if (q.getOptions() != null && q.getOptions().size() > MAX_OPTIONS_PER_QUESTION) {
                throw new BizException("单题的选项最多 " + MAX_OPTIONS_PER_QUESTION + " 个");
            }
            // 选项内容必须非空（与 SurveyEdit.vue 同口径，这里是后端兜底）：答题页用 content 当
            // radio/checkbox 的 value，多个空选项 value 全是 ""，点一个整组选中；且必须在任何写库动作之前抛。
            if (validateOptions && QuestionType.hasOptions(q.getType())) {
                // 排序题至少要两个选项：只有一个时"排序"没有意义，平均名次也恒为 1。
                if (QuestionType.isRanking(q.getType())) {
                    if (q.getOptions() == null || q.getOptions().size() < 2) {
                        throw new BizException("第 " + (i + 1) + " 题（排序题）至少需要 2 个选项");
                    }
                } else if (q.getOptions() == null || q.getOptions().isEmpty()) {
                    throw new BizException("第 " + (i + 1) + " 题至少需要一个选项");
                }
                // 选项内容必须互不相同：答题页把 content 当 value，同名选项点一个会同时选中两个，
                // 统计又按 content 分组也分不开 —— 存进去就是废数据，保存时直接拒绝。
                java.util.Set<String> seenOptions = new java.util.HashSet<>();
                for (int j = 0; j < q.getOptions().size(); j++) {
                    String content = q.getOptions().get(j).getContent();
                    if (content == null || content.isBlank()) {
                        throw new BizException("第 " + (i + 1) + " 题的第 " + (j + 1) + " 个选项内容不能为空");
                    }
                    String trimmed = content.trim();
                    if (!seenOptions.add(trimmed)) {
                        throw new BizException("第 " + (i + 1) + " 题有两个内容相同的选项：" + trimmed);
                    }
                }
            }
            // 矩阵题的行/列标题是 JSON 文本（见 common/MatrixConfig），保存前必须校验并规范化：
            // 空白/重复标题会进库（统计把两行合并），[" A "] 与 ["A"] 也会被当成两行。
            if (QuestionType.isMatrix(q.getType())) {
                if (validateOptions) {
                    java.util.List<String> rows = MatrixConfig.validate(q.getMatrixRows(),
                            "第 " + (i + 1) + " 题的矩阵行标题",
                            MatrixConfig.MAX_ROWS, 1);
                    java.util.List<String> cols = MatrixConfig.validate(q.getMatrixCols(),
                            "第 " + (i + 1) + " 题的矩阵列标题",
                            MatrixConfig.MAX_COLS, 2);
                    q.setMatrixRows(MatrixConfig.toJson(rows));
                    q.setMatrixCols(MatrixConfig.toJson(cols));
                }
            } else {
                // 非矩阵题必须清空这两列：否则"矩阵→单选"改型后旧行列一直挂着，再改回矩阵会看到旧值。
                q.setMatrixRows(null);
                q.setMatrixCols(null);
            }

            // 判分配置的校验与归一（考试模式），规则唯一口径在 common/Grader：只有单选/多选/下拉能设分值。
            // 必须在写库前拒绝：给不可判分题型设分会让总分永远差一截，设了分却没有正确答案则恒不得分。
            if (validateOptions) {
                int score = q.getScore() == null ? 0 : q.getScore();
                if (score < 0) {
                    throw new BizException("第 " + (i + 1) + " 题的分值不能为负数");
                }
                if (score > Grader.MAX_QUESTION_SCORE) {
                    throw new BizException("第 " + (i + 1) + " 题的分值不能超过 "
                            + Grader.MAX_QUESTION_SCORE + " 分");
                }
                if (score > 0 && !Grader.isGradable(q.getType())) {
                    throw new BizException("第 " + (i + 1) + " 题：只有单选、多选、下拉可以设置分值");
                }
                q.setScore(score);

                if (Grader.isGradable(q.getType())) {
                    int correctCount = 0;
                    if (q.getOptions() != null) {
                        for (Option opt : q.getOptions()) {
                            boolean correct = opt.getIsCorrect() != null && opt.getIsCorrect() == 1;
                            // 归一：只认 0/1，避免前端传 2 / true 之类的脏值
                            opt.setIsCorrect(correct ? 1 : 0);
                            if (correct) correctCount++;
                        }
                    }
                    if (score > 0 && correctCount == 0) {
                        throw new BizException("第 " + (i + 1) + " 题设置了分值，但没有指定正确答案");
                    }
                    if (score > 0 && QuestionType.isSingle(q.getType()) && correctCount != 1) {
                        throw new BizException("第 " + (i + 1) + " 题是单选/下拉，必须恰好有一个正确答案");
                    }
                } else if (q.getOptions() != null) {
                    // 非判分题型（排序也有 options）：清掉答案标记，否则将来改型成单选时会突然生效。
                    for (Option opt : q.getOptions()) {
                        opt.setIsCorrect(0);
                    }
                }
            }
        }

        // 1. 查出数据库里现有的题目
        List<Question> existing = questionMapper.findBySurveyId(surveyId);
        Map<Integer, Question> existingMap = new HashMap<>();
        for (Question q : existing) {
            existingMap.put(q.getId(), q);
        }

        // 2. 记录被保留的题目 id
        Set<Integer> keptIds = new HashSet<>();

        // 3. 遍历传入的题目
        int order = 0;
        for (Question q : questions) {
            q.setSurveyId(surveyId);
            q.setSortOrder(order++);

            // 题干与选项长度必须不超过列宽，否则超长会在 INSERT/UPDATE 被 MySQL 拒绝 → 500 而不是 400
            // （t_question.title 与 t_option.content 都是 varchar(500)）。
            TextLimits.requireWithin(q.getTitle(), TextLimits.QUESTION_TITLE, "题目内容");

            if (q.getId() != null && existingMap.containsKey(q.getId())) {
                // 已有题目：更新，保留 id
                questionMapper.update(q);
                keptIds.add(q.getId());
            } else {
                // 新题目：插入
                q.setId(null);
                questionMapper.insert(q);
            }

            // 4. 有选项的题：先删旧选项，再插新选项；没有选项的题也要删一次（清掉改型残留）
            if (QuestionType.hasOptions(q.getType())) {
                optionMapper.deleteByQuestionId(q.getId());
                if (q.getOptions() != null) {
                    int optOrder = 0;
                    for (Option opt : q.getOptions()) {
                        opt.setId(null);
                        opt.setQuestionId(q.getId());
                        opt.setSortOrder(optOrder++);
                        opt.setImageUrl(normalizeImageUrl(opt.getImageUrl()));
                        TextLimits.requireWithin(opt.getContent(), TextLimits.OPTION_CONTENT, "选项内容");
                        optionMapper.insert(opt);
                    }
                }
            } else {
                // 从选择题改成矩阵/文本等无选项题型时旧选项必须删掉，否则 t_option 留下孤儿行，
                // 导出/统计按 question_id 取数会读到幽灵选项。
                optionMapper.deleteByQuestionId(q.getId());
            }
        }

        // 5. 删除本次没传过来的题目（用户真的删掉的）
        // 删题必须同时删选项：t_option 没有外键，只删题目会留下孤儿行 —— 垃圾持续增长、
        // 按 question_id 取数的路径读到已删题的选项、id 被复用时新题凭空继承旧选项。
        for (Question q : existing) {
            if (!keptIds.contains(q.getId())) {
                optionMapper.deleteByQuestionId(q.getId());
                questionMapper.deleteById(q.getId());
            }
        }

        // ★ 跳转目标必须属于本问卷，否则 400 拒绝保存（不落库、不静默置空）：
        //   目标指向本次被删的题时，落库会让答题页"找不到目标就提前结束"；静默置空更糟，
        //   用户以为规则还在，下次编辑才发现没了。新建的题还没有真 id，由前端预存时摘掉、拿到真 id 再写回。
        //   顺序很关键：必须在"删除未保留题目"之后执行，此时 keptIds 才是最终存活集合。
        for (Question q : questions) {
            if (q.getOptions() == null) continue;
            for (Option opt : q.getOptions()) {
                Integer target = opt.getNextQuestionId();
                if (target == null || target == -1) continue;   // null=顺序、-1=结束，都合法
                if (!keptIds.contains(target)) {
                    throw new BizException(
                            "有选项的跳转目标已不存在（可能刚被删除），请重新设置该题的跳转规则");
                }
            }
        }

        // 考试模式一致性：开关开着就至少要有一道计分题，否则提交时总分为 0，成绩卡显示"0 / 0 分"。
        // 与 SurveyService.normalizeSurveySettings 的"开启时总分必须 > 0"是一对：那边挡未设分，这边挡设完又删。
        if (validateOptions) {
            com.survey.entity.Survey current = surveyMapper.findById(surveyId);
            if (current != null && current.getGradeEnabled() != null && current.getGradeEnabled() == 1
                    && Grader.totalScore(questions) == 0) {
                throw new BizException("这份问卷已开启考试模式，至少要有一道题设置分值");
            }
        }

        // 进阶题型包（矩阵/排序/NPS）：出现任意一种就扣一次（幂等键 advanced-types:{surveyId}）。
        // 位置在所有校验之后、版本快照之前：校验失败整体回滚（含扣费）；扣的是问卷作者的额度，回滚不扣。
        if (validateOptions && questions.stream().anyMatch(q -> QuestionType.isAdvanced(q.getType()))) {
            com.survey.entity.Survey survey = surveyMapper.findById(surveyId);
            walletService.chargeForAdvancedTypes(surveyId, survey == null ? null : survey.getUserId());
        }

        // 保存成功后生成版本快照（同一事务：保存回滚，快照也不会留下）。
        // capture 内部判断"题目结构与上一版完全相同则不生成"，重复点保存不会刷版本；放在跳转校验之后。
        surveyVersionService.capture(surveyId, userId, note);

        // 回显"本次提交第 i 项 → 真实 id"（顺序即刚写入的 sortOrder），前端靠它把草稿里的
        // 临时负数 id 换成真 id，否则新建的题永远设不了跳转目标。
        List<QuestionIdItem> idItems = new ArrayList<>(questions.size());
        for (Question q : questions) {
            idItems.add(new QuestionIdItem(q.getId(), q.getSortOrder()));
        }
        return idItems;
    }

    /** 按题目 id 取题目（归属校验用：questionId -> surveyId） */
    public Question getById(Integer id) {
        return questionMapper.findById(id);
    }

    /** 获取问卷的所有题目（带选项） */
    public List<Question> getQuestionsBySurveyId(Integer surveyId) {
        List<Question> questions = questionMapper.findBySurveyId(surveyId);
        for (Question q : questions) {
            if (QuestionType.hasOptions(q.getType())) {
                q.setOptions(optionMapper.findByQuestionId(q.getId()));
            }
        }
        return questions;
    }

    /** 入参里是否存在逻辑跳转（next_question_id 非空，含 -1=结束）。 */
    private static boolean hasJumpRule(List<Question> questions) {
        if (questions == null) {
            return false;
        }
        for (Question q : questions) {
            if (q == null || q.getOptions() == null) {
                continue;
            }
            for (Option o : q.getOptions()) {
                if (o != null && o.getNextQuestionId() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 选项配图只接受本系统上传的相对路径：外链会绕过类型校验与 GC，一律拒绝。
     * 空串 / 空白统一成 null（库里"无图"只有一种表示）。
     */
    private static String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        String url = imageUrl.trim();
        // 500 = t_option.image_url 的列宽；超长会在 MySQL 层被拒成 500，这里提前转 400。
        if (!url.startsWith("/uploads/") || url.length() > 500) {
            throw new BizException("选项图片地址不合法，请重新上传");
        }
        return url;
    }
}