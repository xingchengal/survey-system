package com.survey.service;

import com.survey.common.QuestionType;
import com.survey.dto.QuestionIdItem;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.common.TextLimits;
import com.survey.common.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 题目业务：全量覆盖式保存题目与选项、查询题目（带选项）。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   QuestionController  → 保存题目 / 查询题目
 *   AnswerController    → 用 getById() 把 questionId 反查成 surveyId 做归属校验
 *
 * ── 涉及的表 ──────────────────────────────────────────
 *   t_question、t_option（选项里的 nextQuestionId 就是逻辑跳转规则）
 *
 * ── ★ 全量覆盖语义（理解"跳转错位"这个已知问题的关键）──
 *   saveQuestions() 每次保存都会做四件事：
 *     1. 按前端传来的数组**重新编号 sortOrder**（order++）；
 *     2. 带 id 的更新（保留主键，这样历史答卷的 question_id 不会错乱）、不带 id 的插入；
 *     3. 选择题的选项**先全删再全插** —— 所以每次保存 option.id 都会变；
 *     4. 本次没传的题目删掉。
 *   ★ 逻辑跳转的 nextQuestionId 存的是 **真实的 question.id**，不是"1 基题目序号" ——
 *     详见 saveQuestions 里的长注释。切换该语义时全库 0 条跳转规则，所以**不需要数据迁移**。
 */
@Service
public class QuestionService {

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private OptionMapper optionMapper;

    /**
     * 保存问卷的所有题目（全量覆盖）。
     *
     * @param surveyId  问卷 id
     * @param questions 前端传来的完整题目数组（含 options 与 nextQuestionId）。
     *                  ★ nextQuestionId 是**真 question.id**（或 -1=结束 / null=顺序），
     *                    编辑器按 id 绑定，所以删题/调序之后它仍指向"当初选的那道题"。
     * @return **按下标一一对应**的题目 id 列表（第 i 项 = 本次提交数组第 i 项的真实 id）。
     *         ★ 为什么必须回显：新建的题在保存前没有 id，而编辑器的跳转目标要存真实 id ——
     *           前端拿到这份列表，才能在**下一次**提交前把草稿里的临时 id 换成真 id。
     *           详见 dto/QuestionIdItem 的说明。
     *
     * ★ 事务：题目与选项涉及多表多次写，中途失败必须整体回滚。
     * ★ 归属校验不在这里 —— 由 QuestionController.saveQuestions() 负责，
     *   它先按 surveyId 查出问卷再比对登录用户。
     */
    /** 一份问卷最多多少道题（防"一次保存塞几万道题"把公开的题目接口与导出打垮） */
    private static final int MAX_QUESTIONS_PER_SURVEY = 200;

    /** 单题最多多少个选项 */
    private static final int MAX_OPTIONS_PER_QUESTION = 50;

    @Transactional
    public List<QuestionIdItem> saveQuestions(Integer surveyId, List<Question> questions) {
        // ★ **题目与选项的条数必须有上限**。
        //   请求体上限是 4MB，而一道题的 JSON 只有几十字节 —— 一次保存可以塞进**几万道题**，
        //   之后每个读到这份问卷的地方都会放大：
        //     · GET /api/question/list/{id} 在 JwtAuthFilter 白名单里（**无需登录**），
        //       且内部是"1 次查题目 + 每题一次查选项"的 1+N 条 SQL，这条路径还没有限流；
        //     · Excel 导出把每道题当成一个表头列，xlsx 的列上限是 16384，超了导出直接失败；
        //     · 提交答卷 / 复制问卷都要把整份题目与选项读进内存并在一个事务里逐条写。
        //   正常问卷不会超过几十题，这里取一个宽松但对攻击者无利可图的上限。
        if (questions.size() > MAX_QUESTIONS_PER_SURVEY) {
            throw new BizException("一份问卷最多 " + MAX_QUESTIONS_PER_SURVEY + " 道题");
        }
        for (Question q : questions) {
            if (q.getOptions() != null && q.getOptions().size() > MAX_OPTIONS_PER_QUESTION) {
                throw new BizException("单题的选项最多 " + MAX_OPTIONS_PER_QUESTION + " 个");
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

            // ★ 题干与选项的长度必须**不超过数据库列宽**，
            //   否则超长会在 INSERT/UPDATE 时被 MySQL 拒绝 → 用户看到 500 而不是 400
            //   （t_question.title 与 t_option.content 都是 varchar(500)）。
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

            // 4. 选择题：先删旧选项，再插新选项
            if (QuestionType.hasOptions(q.getType())) {
                optionMapper.deleteByQuestionId(q.getId());
                if (q.getOptions() != null) {
                    int optOrder = 0;
                    for (Option opt : q.getOptions()) {
                        opt.setId(null);
                        opt.setQuestionId(q.getId());
                        opt.setSortOrder(optOrder++);
                        TextLimits.requireWithin(opt.getContent(), TextLimits.OPTION_CONTENT, "选项内容");
                        optionMapper.insert(opt);
                    }
                }
            }
        }

        // 5. 删除本次没传过来的题目（用户真的删掉的）
        // ★ 删题**必须同时删掉它的选项**。
        //   若只调 questionMapper.deleteById()，而上面"保留题目"的分支是
        //   optionMapper.deleteByQuestionId() + 重插 —— 一删一留，被删题的选项就永远留在 t_option 里。
        //   t_option 没有外键约束，这些孤儿选项既不会被数据库拦下，也没有任何查询能发现它们：
        //     ① 库里垃圾持续增长；
        //     ② 任何按 question_id 直接取数的路径（手工 SQL、导出、报表）会读到已删题目的选项；
        //     ③ 一旦 t_question.id 被复用（自增重置 / 数据导入），新题会"凭空"继承旧选项。
        //   前端并不总是回传全部题目，所以"用户点删除此题再保存"这条分支是日常可达的。
        for (Question q : existing) {
            if (!keptIds.contains(q.getId())) {
                optionMapper.deleteByQuestionId(q.getId());
                questionMapper.deleteById(q.getId());
            }
        }

        // ★★ 跳转目标必须**属于本问卷**，否则一律 **400 拒绝保存**（不落库、不静默置空）。
        //   为什么必须拒绝而不是"置空后放行"：
        //     · 目标指向一道**本次被删掉**的题时，规则已经失效。落库的后果是答题页
        //       "找不到目标就提前结束问卷" —— 用户看到的是"问卷莫名其妙结束了"；
        //     · 静默置空更糟：用户以为规则还在，**下次编辑才发现没了**（最难查的一类不一致）。
        //   ★ 那"新建的题还没有真实 id"怎么办？由**前端在预存时把这类目标摘掉**、
        //     拿到真实 id 后再写回（见 SurveyEdit.ensureRealQuestionIds）——
        //     也就是说这个矛盾应该在前端解决，而不是让后端接受"最终形态里不存在的目标"。
        //   ★ 顺序很关键：必须在上面"删除未保留题目"**之后**执行 ——
        //     那时 keptIds 才是最终存活集合（指向一道本次刚被删掉的题也算非法）。
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

        // ★ 回显"本次提交的第 i 项 → 真实 id"（顺序即 questions 的顺序，也就是刚写入的 sortOrder）。
        //   前端要靠它把草稿阶段的临时 id 换成真 id，否则新建的题永远设不了跳转目标。
        List<QuestionIdItem> idItems = new ArrayList<>(questions.size());
        for (Question q : questions) {
            idItems.add(new QuestionIdItem(q.getId(), q.getSortOrder()));
        }
        return idItems;
    }

    /**
     * 按题目 id 取题目（归属校验用：questionId -> surveyId）
     */
    public Question getById(Integer id) {
        return questionMapper.findById(id);
    }

    /**
     * 获取问卷的所有题目（带选项）
     */
    public List<Question> getQuestionsBySurveyId(Integer surveyId) {
        List<Question> questions = questionMapper.findBySurveyId(surveyId);
        for (Question q : questions) {
            if (QuestionType.hasOptions(q.getType())) {
                q.setOptions(optionMapper.findByQuestionId(q.getId()));
            }
        }
        return questions;
    }
}