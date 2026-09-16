package com.survey.controller;

import com.survey.entity.*;
import com.survey.mapper.*;
import com.survey.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Tag(name = "演示数据", description = "生成/重置演示数据（测试用，仅管理员）")
@RestController
@RequestMapping("/api/demo")
public class DemoDataController {

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

    private static final Random RANDOM = new Random();
    private static final String DEMO_PREFIX = "demo_user";
    private static final int USERS_PER_BATCH = 20;
    private static final int SURVEYS_PER_BATCH = 30;

    @Operation(summary = "一键生成演示数据（追加，仅管理员）")
    @PostMapping("/init")
    @Transactional
    public Map<String, Object> init(@RequestHeader("X-User-Role") String role) {
        Map<String, Object> result = new HashMap<>();

        // ★ 权限校验
        if (!"admin".equals(role)) {
            result.put("success", false);
            result.put("message", "无权限，仅管理员可执行");
            return result;
        }

        int existingCount = userMapper.countByUsernamePrefix(DEMO_PREFIX);
        int startIndex = existingCount + 1;
        int batchNo = existingCount / USERS_PER_BATCH + 1;

        // 1. 生成用户
        List<User> users = new ArrayList<>();
        String md5Pwd = UserService.md5("123456");
        for (int i = 0; i < USERS_PER_BATCH; i++) {
            User u = new User();
            u.setUsername(DEMO_PREFIX + (startIndex + i));
            u.setPassword(md5Pwd);
            u.setNickname("演示用户" + (startIndex + i));
            u.setRole("user");
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
                s.setStatus(1);
                s.setShareCode(generateCode());
            } else if (r < 8) {
                s.setStatus(0);
            } else {
                s.setStatus(2);
                s.setShareCode(generateCode());
            }
            s.setViewCount(RANDOM.nextInt(200));
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
            if (s.getStatus() != 1) continue;
            List<Question> questions = questionMapper.findBySurveyId(s.getId());
            int ansCount = 5 + RANDOM.nextInt(16);
            for (int a = 0; a < ansCount; a++) {
                Answer answer = new Answer();
                answer.setSurveyId(s.getId());
                answer.setUserId(null);
                answerMapper.insert(answer);

                for (Question q : questions) {
                    if (q.getType() == 3) {
                        AnswerDetail d = new AnswerDetail();
                        d.setAnswerId(answer.getId());
                        d.setQuestionId(q.getId());
                        d.setContent("这是第" + (a + 1) + "位用户的回答内容");
                        answerDetailMapper.insert(d);
                    } else {
                        List<Option> opts = optionMapper.findByQuestionId(q.getId());
                        if (opts.isEmpty()) continue;
                        if (q.getType() == 1) {
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
            }
        }

        result.put("success", true);
        result.put("message", "第 " + batchNo + " 批演示数据生成成功");
        result.put("batchNo", batchNo);
        result.put("userCount", USERS_PER_BATCH);
        result.put("surveyCount", SURVEYS_PER_BATCH);
        result.put("answerCount", totalAnswers);
        return result;
    }

    @Operation(summary = "重置演示数据（清空所有演示数据，仅管理员）")
    @DeleteMapping("/reset")
    @Transactional
    public Map<String, Object> reset(@RequestHeader("X-User-Role") String role) {
        Map<String, Object> result = new HashMap<>();

        // ★ 权限校验
        if (!"admin".equals(role)) {
            result.put("success", false);
            result.put("message", "无权限，仅管理员可执行");
            return result;
        }

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

    private String generateCode() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
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