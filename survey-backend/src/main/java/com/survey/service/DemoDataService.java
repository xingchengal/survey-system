package com.survey.service;

import com.survey.common.QuestionType;
import com.survey.common.SurveyStatus;
import com.survey.entity.*;
import com.survey.mapper.*;
// 注：同包类（SurveyService / UserService）不需要 import。
import com.survey.common.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 演示数据：一键造/清演示用的用户与问卷（答辩演示、压测用）。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   DemoDataController → /init、/reset 两个端点（页面是 DemoData.vue 的「演示数据」页）。
 *
 * ── 权限 ──────────────────────────────────────────────
 *   两个端点都需管理员 —— 但**权限校验不在这里**：Controller 上挂了类级 @RequireAdmin，
 *   由 aspect/AdminCheckAspect 统一实施。本类只管业务，不碰身份。
 *
 * ── 数据约定（reset 靠它精确回收，改这里要同步改 reset 逻辑）──
 *   演示用户名一律以 DEMO_PREFIX("demo_user") 开头；演示问卷的 userId 都指向这些用户。
 *   演示用户密码统一 123456，用 UserService.hashPassword() 生成（与真实注册同一套算法）。
 *   ★ reset 只删「名字带前缀」的用户及其问卷，不碰真实数据。
 */
// ★ prod 下"不注册"的是 **DemoDataController**（它上面有 @Profile("!prod")，
//   端点因此 404），**不是本类** —— 本类只有 @Service，prod 下照样会被实例化。
//   别按"prod 下整个类不加载"去推断：若给本类加 @PostConstruct 或定时任务，在 prod 下是会执行的。
//   之所以要挡住那个端点：/init 会批量创建密码统一为 123456 的 demo_user* 真实账号，
//   管理员在生产里误点一次，库里就多 20 个可被撞库的弱口令入口。
//   本地/演示（local 默认 profile）照常可用。
@Service
public class DemoDataService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private SurveyMapper surveyMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private OptionMapper optionMapper;
    @Autowired
    private AnswerMapper answerMapper;
    @Autowired
    private AnswerDetailMapper answerDetailMapper;
    /**
     * ★ 分享码必须复用 SurveyService 的实现（SecureRandom + 查重 + 重试）：
     *   若本地另写一套不检测占用的生成器，一旦撞上 t_survey.share_code 的唯一索引，
     *   整个 @Transactional 的"20 用户 + 30 问卷"批次会全部回滚并返回 500。
     */
    @Autowired
    private SurveyService surveyService;

    private static final Random RANDOM = new Random();
    private static final String DEMO_PREFIX = "demo_user";
    private static final int USERS_PER_BATCH = 20;
    private static final int SURVEYS_PER_BATCH = 30;

    @Transactional
    public Map<String, Object> init() {
        Map<String, Object> result = new HashMap<>();

        // ★ **权限校验不在这里** —— DemoDataController 的类级 @RequireAdmin 负责
        //   （本方法连身份参数都没有）。

        int existingCount = userMapper.countByUsernamePrefix(DEMO_PREFIX);
        int startIndex = existingCount + 1;
        int batchNo = existingCount / USERS_PER_BATCH + 1;

        // 1. 生成用户
        List<User> users = new ArrayList<>();
        String demoPwd = UserService.hashPassword("123456");
        for (int i = 0; i < USERS_PER_BATCH; i++) {
            User u = new User();
            u.setUsername(DEMO_PREFIX + (startIndex + i));
            u.setPassword(demoPwd);
            u.setNickname("演示用户" + (startIndex + i));
            u.setRole(Role.USER);
            userMapper.insert(u);
            users.add(u);
        }

        // 2. 生成问卷
        String[] surveyTitles = {
                "员工满意度调查", "客户反馈问卷", "产品体验调研", "用户需求调查", "市场调研问卷",
                "课程反馈调查", "服务质量评价", "网站使用体验", "APP满意度调研", "健康习惯调查",
                "阅读习惯调研", "消费习惯调查", "旅游偏好问卷", "音乐喜好调研", "运动频率调查",
                "观影偏好调研", "饮食健康问卷", "作息时间调查", "学习效率调研", "工作压力调查",
                "社交习惯问卷", "购物体验评价", "产品功能反馈", "新功能需求调研", "团队协作评价",
                "会议效率调研", "办公环境满意度", "培训效果评估", "职位满意度调查", "公司文化认同"
        };

        List<Survey> surveys = new ArrayList<>();
        for (int i = 0; i < SURVEYS_PER_BATCH; i++) {
            User owner = users.get(RANDOM.nextInt(users.size()));
            Survey s = new Survey();
            s.setUserId(owner.getId());
            s.setTitle(surveyTitles[i] + " [第" + batchNo + "批]");
            s.setDescription("这是一份关于" + surveyTitles[i] + "的调研问卷，感谢您的参与。");
            int r = RANDOM.nextInt(10);
            if (r < 5) {
                s.setStatus(SurveyStatus.PUBLISHED.code());
                s.setShareCode(surveyService.generateShareCode());
            } else if (r < 8) {
                s.setStatus(SurveyStatus.DRAFT.code());
            } else {
                s.setStatus(SurveyStatus.ENDED.code());
                s.setShareCode(surveyService.generateShareCode());
            }
            // ★ 访问量先留 0，等下面把答卷造完再按「答卷数 + 少量只浏览未提交的访客」回填。
            //   若在这里凭空给 0~199 次访问，30 份问卷会让管理员看板的「访客次数」直接跳到两千多，
            //   与"只生成了 30 份问卷"完全对不上。
            s.setViewCount(0);
            s.setIsTemplate(0);
            s.setDeleted(0);
            surveyMapper.insert(s);
            surveys.add(s);

            int qCount = 3 + RANDOM.nextInt(3);
            for (int q = 0; q < qCount; q++) {
                Question question = new Question();
                question.setSurveyId(s.getId());
                int type = 1 + RANDOM.nextInt(3);
                question.setType(type);
                question.setTitle(generateQuestionTitle(type, q));
                question.setRequired(RANDOM.nextBoolean() ? 1 : 0);
                question.setSortOrder(q);
                questionMapper.insert(question);

                if (type == 1 || type == 2) {
                    int optCount = 3 + RANDOM.nextInt(3);
                    for (int o = 0; o < optCount; o++) {
                        Option opt = new Option();
                        opt.setQuestionId(question.getId());
                        opt.setContent(generateOptionContent(o));
                        opt.setSortOrder(o);
                        optionMapper.insert(opt);
                    }
                }
            }
        }

        // 3. 生成答卷
        int totalAnswers = 0;
        for (Survey s : surveys) {
            if (!SurveyStatus.isPublished(s.getStatus())) continue;
            List<Question> questions = questionMapper.findBySurveyId(s.getId());
            // ★ 每份已发布问卷造 3~8 份答卷：一批约 80 份，既够图表展示也不失真。
            //   若造 5~20 份，一批就是 200+ 份，管理员看板的「总答卷」会跳到两百多，
            //   与"30 份问卷"不成比例。
            int ansCount = 3 + RANDOM.nextInt(6);
            int created = 0;
            for (int a = 0; a < ansCount; a++) {
                Answer answer = new Answer();
                answer.setSurveyId(s.getId());
                answer.setUserId(null);
                answerMapper.insert(answer);

                for (Question q : questions) {
                    if (QuestionType.isText(q.getType())) {
                        AnswerDetail d = new AnswerDetail();
                        d.setAnswerId(answer.getId());
                        d.setQuestionId(q.getId());
                        d.setContent("这是第" + (a + 1) + "位用户的回答内容");
                        answerDetailMapper.insert(d);
                    } else {
                        List<Option> opts = optionMapper.findByQuestionId(q.getId());
                        if (opts.isEmpty()) continue;
                        if (QuestionType.isSingle(q.getType())) {
                            AnswerDetail d = new AnswerDetail();
                            d.setAnswerId(answer.getId());
                            d.setQuestionId(q.getId());
                            d.setContent(opts.get(RANDOM.nextInt(opts.size())).getContent());
                            answerDetailMapper.insert(d);
                        } else {
                            int selCount = 1 + RANDOM.nextInt(Math.min(3, opts.size()));
                            Set<Integer> used = new HashSet<>();
                            for (int k = 0; k < selCount; k++) {
                                int idx = RANDOM.nextInt(opts.size());
                                if (used.contains(idx)) continue;
                                used.add(idx);
                                AnswerDetail d = new AnswerDetail();
                                d.setAnswerId(answer.getId());
                                d.setQuestionId(q.getId());
                                d.setContent(opts.get(idx).getContent());
                                answerDetailMapper.insert(d);
                            }
                        }
                    }
                }
                totalAnswers++;
                created++;
            }
            // ★ 回填访问量：答卷数 + 0~19 次"只浏览没提交"的访问。
            //   这样「访客次数」永远 ≥「总答卷」且与问卷量级自洽，看板上不会出现
            //   "30 份问卷 / 两千多访客" 这种对不上的数字。
            surveyMapper.updateViewCount(s.getId(), created + RANDOM.nextInt(20));
        }

        result.put("success", true);
        result.put("message", "第 " + batchNo + " 批演示数据生成成功");
        result.put("batchNo", batchNo);
        result.put("userCount", USERS_PER_BATCH);
        result.put("surveyCount", SURVEYS_PER_BATCH);
        result.put("answerCount", totalAnswers);
        return result;
    }

    @Transactional
    public Map<String, Object> reset() {
        Map<String, Object> result = new HashMap<>();

        // ★ **权限校验不在这里**（同 init），由 DemoDataController 的类级 @RequireAdmin 负责。

        List<User> demoUsers = userMapper.findByUsernamePrefix(DEMO_PREFIX);
        if (demoUsers.isEmpty()) {
            result.put("success", true);
            result.put("message", "没有需要清空的演示数据");
            result.put("deletedUsers", 0);
            result.put("deletedSurveys", 0);
            return result;
        }

        List<Integer> surveyIds = new ArrayList<>();
        for (User u : demoUsers) {
            List<Survey> surveys = surveyMapper.findByUserIdIncludeDeleted(u.getId());
            for (Survey s : surveys) {
                surveyIds.add(s.getId());
            }
        }

        for (Integer sid : surveyIds) {
            answerDetailMapper.deleteBySurveyId(sid);
            answerMapper.deleteBySurveyId(sid);
            optionMapper.deleteBySurveyId(sid);
            questionMapper.deleteBySurveyId(sid);
            surveyMapper.deleteById(sid);
        }

        for (User u : demoUsers) {
            userMapper.deleteById(u.getId());
        }

        result.put("success", true);
        result.put("message", "演示数据已全部清空");
        result.put("deletedUsers", demoUsers.size());
        result.put("deletedSurveys", surveyIds.size());
        return result;
    }

    private String generateQuestionTitle(int type, int index) {
        String[] single = {"您的性别是？", "您的年龄段是？", "您对本次服务的整体评价是？", "您从何处了解到我们？", "您通常什么时间使用？"};
        String[] multi = {"您喜欢哪些功能？（多选）", "您关注哪些方面？（多选）", "您通过哪些渠道接触？（多选）"};
        String[] text = {"请留下您的建议：", "您对我们的期望是：", "其他补充说明："};
        if (type == 1) return single[index % single.length];
        if (type == 2) return multi[index % multi.length];
        return text[index % text.length];
    }

    private String generateOptionContent(int index) {
        String[] options = {"非常满意", "比较满意", "一般", "不太满意", "非常不满意", "选项A", "选项B", "选项C", "选项D"};
        return options[index % options.length];
    }
}