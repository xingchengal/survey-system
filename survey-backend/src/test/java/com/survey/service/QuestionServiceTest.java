package com.survey.service;

import com.survey.common.BizException;
import com.survey.dto.QuestionIdItem;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 题目保存的两条硬契约（不连库、不起 Spring 容器）。
 *
 * ── 为什么专门测这两条 ────────────────────────────────
 *   ① **返回值必须是"按下标一一对应的题目 id 列表"** ——
 *      新建的题在保存前没有数据库 id，而编辑器的"跳转目标"必须存真实 id；
 *      前端靠这份回显把草稿阶段的**负数临时 id** 换成真 id。
 *      如果哪天有人把它改回"返回一句 保存成功"，新建问卷的跳转功能会**静默失效**
 *      （前端拿不到 id 列表 → 临时 id 留在数据里 → 提交被跳转校验拦下）。
 *      所以这一条必须由测试钉住，而不是只写在注释里。
 *   ② **跳转目标必须属于本问卷，否则 400 拒绝保存** ——
 *      不拦的话，"删掉被别的题跳转指向的那道题"会让规则失效，
 *      答题页表现为"找不到目标就提前结束问卷"（用户看到"问卷莫名其妙结束了"）；
 *      而**静默把目标置空再放行**更糟：用户以为规则还在。
 *   ③ 还要钉住**全量覆盖语义**（本次没传的题会被删除）——
 *      前端"预存"时若只提交新题，会把问卷里已有题目全删掉。
 *
 * ── 关于 mock ─────────────────────────────────────────
 *   只 mock Mapper，**不 mock 被测对象本身**，断言的是真实逻辑。
 *   项目在 src/test/resources/mockito-extensions 里指定了 mock-maker-subclass，
 *   因此不能 mock final 类 —— 本测试用到的类型都不是 final。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionServiceTest {

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private OptionMapper optionMapper;
    /**
     * ★ 保存题目成功后要生成版本快照，所以 QuestionService 多了这个依赖。
     * 这里给它一个 mock：本类断言的是"保存/校验"逻辑，版本快照本身由 SurveyVersionServiceTest 覆盖。
     * 不注入的话该字段是 null，saveQuestions 走到最后会 NPE。
     */
    @Mock
    private SurveyVersionService surveyVersionService;

    /**
     * ★ 进阶题型包（矩阵/排序/NPS）在保存时扣费，所以 QuestionService 多了两个依赖。
     * 这里都给 mock：本类断言的是保存/校验逻辑，扣费本身由 WalletServiceTest 覆盖。
     */
    @Mock
    private WalletService walletService;
    @Mock
    private SurveyMapper surveyMapper;

    @InjectMocks
    private QuestionService questionService;

    private static final Integer SURVEY_ID = 50;

    /** 造一道单选题（有选项，能携带跳转规则）。 */
    private static Question single(Integer id, String title, Integer jumpTo) {
        Question q = new Question();
        q.setId(id);
        q.setSurveyId(SURVEY_ID);
        q.setType(1);
        q.setTitle(title);
        q.setRequired(0);
        Option opt = new Option();
        opt.setContent("选项-" + title);
        opt.setNextQuestionId(jumpTo);
        List<Option> opts = new ArrayList<>();
        opts.add(opt);
        q.setOptions(opts);
        return q;
    }

    /** 模拟 insert 的自增主键回填（QuestionService 依赖它把新题的 id 写回实体）。 */
    private void stubAutoIncrement(int startId) {
        final int[] next = {startId};
        doAnswer(inv -> {
            Question q = inv.getArgument(0);
            q.setId(next[0]++);
            return 1;
        }).when(questionMapper).insert(any(Question.class));
    }

    @Test
    @DisplayName("★ 返回值按提交顺序一一对应（前端换真 id 的依据）")
    void saveQuestions_shouldReturnIndexAlignedIdList() {
        // 库里已有第 1 题（id=900），本次提交：已有题 + 两道新题
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(new ArrayList<>(List.of(single(900, "旧题", null))));
        stubAutoIncrement(1000);

        List<Question> payload = new ArrayList<>(List.of(
                single(900, "旧题", null),
                single(null, "新题A", null),
                single(null, "新题B", null)
        ));
        List<QuestionIdItem> result = questionService.saveQuestions(SURVEY_ID, payload);

        assertEquals(3, result.size(), "回显条数必须等于提交条数");
        assertEquals(900, result.get(0).getId(), "第 0 项应是已有题的 id（按 id 更新，id 不变）");
        assertEquals(1000, result.get(1).getId(), "第 1 项应是新题拿到的真实 id");
        assertEquals(1001, result.get(2).getId(), "第 2 项应是新题拿到的真实 id");
        assertEquals(0, result.get(0).getSortOrder(), "sortOrder 从 0 开始按顺序重排");
        assertEquals(1, result.get(1).getSortOrder());
        assertEquals(2, result.get(2).getSortOrder());
    }

    @Test
    @DisplayName("★ 新建的题（id 为 null）必须被 INSERT，并在回显里拿到真实 id")
    void saveQuestions_shouldEchoRealIdForNewQuestions() {
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(new ArrayList<>());
        stubAutoIncrement(2000);

        List<Question> payload = new ArrayList<>(List.of(single(null, "全新题", null)));
        List<QuestionIdItem> result = questionService.saveQuestions(SURVEY_ID, payload);

        assertEquals(1, result.size());
        assertEquals(2000, result.get(0).getId());
        assertNotNull(payload.get(0).getId(), "服务层必须把自增 id 回填到实体上（回显依赖它）");
    }

    @Test
    @DisplayName("跳转目标属于本问卷 → 放行；-1（结束）与 null（顺序）也放行")
    void saveQuestions_shouldAcceptValidJumpTargets() {
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(new ArrayList<>());
        stubAutoIncrement(3000);

        Question q1 = single(null, "Q1", null);
        Question q2 = single(null, "Q2", -1);          // 结束问卷
        Question q3 = single(null, "Q3", null);        // 顺序
        List<Question> payload = new ArrayList<>(List.of(q1, q2, q3));
        assertDoesNotThrow(() -> questionService.saveQuestions(SURVEY_ID, payload));
    }

    @Test
    @DisplayName("★ 跳转目标解析不出 → **400 拒绝保存**（绝不静默置空）")
    void saveQuestions_shouldRejectUnresolvedJumpTarget() {
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(new ArrayList<>());
        stubAutoIncrement(4000);

        Question q1 = single(null, "Q1", 999999);      // 指向不存在的题
        List<Question> payload = new ArrayList<>(List.of(q1));

        BizException ex = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, payload));
        assertTrue(ex.getMessage().contains("跳转目标已不存在"), "实际文案：" + ex.getMessage());
        // ★ 关键：**不能**把目标偷偷改成 null 后放行 —— 那样用户以为规则还在
        assertEquals(999999, q1.getOptions().get(0).getNextQuestionId(), "拒绝时必须保持原值，不得静默改写");
    }

    @Test
    @DisplayName("★★ 全量覆盖语义：本次没传的题会被删除（前端预存必须提交完整数组）")
    void saveQuestions_shouldDeleteQuestionsNotInPayload() {
        // 库里有两道题，本次只提交一道 —— 另一道会被删掉。
        // 这正是"预存只发新题 ⇒ 把已有题目删光"那个 bug 的底层原因，必须被测试钉住。
        when(questionMapper.findBySurveyId(SURVEY_ID))
                .thenReturn(new ArrayList<>(List.of(single(701, "已有题A", null), single(702, "已有题B", null))));
        stubAutoIncrement(6000);

        List<Question> payload = new ArrayList<>(List.of(single(701, "已有题A", null)));
        questionService.saveQuestions(SURVEY_ID, payload);

        // 702 没出现在提交里 ⇒ 它的选项与题目都必须被删掉
        org.mockito.Mockito.verify(optionMapper).deleteByQuestionId(702);
        org.mockito.Mockito.verify(questionMapper).deleteById(702);
        // 而 701 被保留（走 update，不 insert）
        org.mockito.Mockito.verify(questionMapper).update(org.mockito.ArgumentMatchers.argThat(q -> Integer.valueOf(701).equals(q.getId())));
    }

    @Test
    @DisplayName("★ 跳转目标指向**本次被删掉的题** → 400（删掉被指向的题必须拦下）")
    void saveQuestions_shouldRejectJumpTargetToDeletedQuestion() {
        // 库里第 700 题会被本次提交"漏掉"从而被删除
        when(questionMapper.findBySurveyId(SURVEY_ID)).thenReturn(new ArrayList<>(List.of(single(700, "将被删的题", null))));
        stubAutoIncrement(5000);

        // 提交里没有 700，而新题的跳转指向 700
        Question q = single(null, "新题", 700);
        List<Question> payload = new ArrayList<>(List.of(q));
        BizException ex = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, payload));
        assertTrue(ex.getMessage().contains("跳转目标已不存在"), "实际文案：" + ex.getMessage());
    }

    @Test
    @DisplayName("题目条数超上限 → 400（防一次保存塞几万道题）")
    void saveQuestions_shouldRejectTooManyQuestions() {
        List<Question> payload = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            payload.add(single(null, "Q" + i, null));
        }
        BizException ex = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, payload));
        assertTrue(ex.getMessage().contains("最多"), "实际文案：" + ex.getMessage());
    }

    /**
     * ★★ 空选项必须拒绝。
     *
     * 答题页把 opt.content 当 el-radio / el-checkbox 的 value：两个空选项的 value 都是 ""，
     * 于是"点一个 → 整组同时显示为选中"；下拉则变成空白面板、选不出东西，
     * 必填时把这个访客永久卡在该题（第三十三轮交付后由用户手测发现）。
     * 校验必须发生在**任何写库动作之前**，否则会留下"题目插了、选项没了"的半成品。
     */
    @Test
    @DisplayName("★★ 选项内容为空 → 400，且一条 SQL 都不执行")
    void saveQuestions_shouldRejectBlankOptionContent() {
        Question q = single(null, "带空选项的题", null);
        q.getOptions().get(0).setContent("   ");      // 纯空白也算空
        List<Question> payload = new ArrayList<>(List.of(q));

        BizException ex = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, payload));
        assertTrue(ex.getMessage().contains("选项内容不能为空"), "实际文案：" + ex.getMessage());
        verifyNoInteractions(questionMapper, optionMapper);
    }

    @Test
    @DisplayName("★ 选项题一个选项都没有 → 400（否则答题页是一道点不动的空题）")
    void saveQuestions_shouldRejectOptionQuestionWithoutOptions() {
        Question q = single(null, "没有选项的单选题", null);
        q.setOptions(new ArrayList<>());
        List<Question> payload = new ArrayList<>(List.of(q));

        BizException ex = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, payload));
        assertTrue(ex.getMessage().contains("至少需要一个选项"), "实际文案：" + ex.getMessage());
        verifyNoInteractions(questionMapper, optionMapper);
    }

    // ==================== 进阶题型（矩阵 / 排序）的保存校验 ====================

    private static Question matrix(String rowsJson, String colsJson) {
        Question q = new Question();
        q.setType(8);
        q.setTitle("矩阵题");
        q.setRequired(0);
        q.setMatrixRows(rowsJson);
        q.setMatrixCols(colsJson);
        return q;
    }

    @Test
    @DisplayName("★ 矩阵题：行列标题保存前会被规范化（去空白、JSON 紧凑），非矩阵题清空两列")
    void saveQuestions_shouldNormalizeMatrixAxes() {
        stubAutoIncrement(900);
        Question q = matrix("[\" 服务态度 \", \"响应速度\"]", "[\"满意\",\"不满意\"]");
        questionService.saveQuestions(SURVEY_ID, new ArrayList<>(List.of(q)));

        assertEquals("[\"服务态度\",\"响应速度\"]", q.getMatrixRows());
        assertEquals("[\"满意\",\"不满意\"]", q.getMatrixCols());
    }

    @Test
    @DisplayName("矩阵题行列标题重复 → 400（重复标题会让统计把两行合并）")
    void saveQuestions_shouldRejectDuplicateMatrixRows() {
        Question q = matrix("[\"服务态度\",\"服务态度\"]", "[\"满意\",\"不满意\"]");

        BizException ex = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, new ArrayList<>(List.of(q))));
        assertTrue(ex.getMessage().contains("不能重复"), "实际文案：" + ex.getMessage());
        verifyNoInteractions(questionMapper, optionMapper);
    }

    @Test
    @DisplayName("矩阵题至少 1 行 2 列：只有一列无法构成矩阵 → 400")
    void saveQuestions_shouldRejectMatrixWithOneColumn() {
        Question q = matrix("[\"服务态度\"]", "[\"满意\"]");

        BizException ex = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, new ArrayList<>(List.of(q))));
        assertTrue(ex.getMessage().contains("至少需要 2 项"), "实际文案：" + ex.getMessage());
    }

    @Test
    @DisplayName("★ 非矩阵题即使带了行列标题，也必须被清空（防改型残留）")
    void saveQuestions_shouldClearMatrixAxesForOtherTypes() {
        stubAutoIncrement(901);
        Question q = single(null, "普通单选题", null);
        q.setMatrixRows("[\"脏行\"]");
        q.setMatrixCols("[\"脏列\"]");

        questionService.saveQuestions(SURVEY_ID, new ArrayList<>(List.of(q)));

        assertNull(q.getMatrixRows());
        assertNull(q.getMatrixCols());
    }

    @Test
    @DisplayName("排序题只有 1 个选项 → 400（排不出来，平均名次恒为 1）")
    void saveQuestions_shouldRejectRankingWithOneOption() {
        Question q = new Question();
        q.setType(9);
        q.setTitle("排序题");
        q.setRequired(0);
        Option only = new Option();
        only.setContent("唯一选项");
        q.setOptions(new ArrayList<>(List.of(only)));

        BizException ex = assertThrows(BizException.class,
                () -> questionService.saveQuestions(SURVEY_ID, new ArrayList<>(List.of(q))));
        assertTrue(ex.getMessage().contains("至少需要 2 个选项"), "实际文案：" + ex.getMessage());
        verifyNoInteractions(questionMapper, optionMapper);
    }
}
