package com.survey.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.PageResult;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SurveyService {

    @Autowired
    private SurveyMapper surveyMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private OptionMapper optionMapper;
    @Autowired
    private AnswerMapper answerMapper;

    private String generateShareCode() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public Survey createSurvey(Survey survey) {
        if (survey.getIsTemplate() == null) survey.setIsTemplate(0);
        if (survey.getDeleted() == null) survey.setDeleted(0);
        if (survey.getViewCount() == null) survey.setViewCount(0);
        surveyMapper.insert(survey);
        return survey;
    }

    public int deleteSurvey(Integer id) {
        return surveyMapper.deleteById(id);
    }

    public int updateSurvey(Survey survey) {
        return surveyMapper.update(survey);
    }

    public List<Survey> listAll() {
        return surveyMapper.findAll();
    }

    public List<Survey> listByUserId(Integer userId) {
        return surveyMapper.findByUserId(userId);
    }

    public Survey getById(Integer id) {
        return surveyMapper.findById(id);
    }

    public Survey getByShareCode(String shareCode) {
        return surveyMapper.findByShareCode(shareCode);
    }

    public Survey publish(Integer surveyId) {
        Survey survey = surveyMapper.findById(surveyId);
        if (survey == null) return null;
        if (survey.getShareCode() == null || survey.getShareCode().isEmpty()) {
            survey.setShareCode(generateShareCode());
        }
        survey.setStatus(1);
        surveyMapper.update(survey);
        return survey;
    }

    // ===== 回收站 =====
    public int moveToRecycle(Integer id, Integer userId) {
        return surveyMapper.moveToRecycle(id, userId);
    }

    public int restoreFromRecycle(Integer id) {
        return surveyMapper.restoreFromRecycle(id);
    }

    /**
     * ★ 彻底删除（只能删除回收站里的问卷）
     */
    public int permanentDelete(Integer id) {
        Survey survey = surveyMapper.findById(id);
        if (survey == null) {
            throw new RuntimeException("问卷不存在");
        }
        if (survey.getDeleted() == null || survey.getDeleted() == 0) {
            throw new RuntimeException("只能彻底删除回收站中的问卷，请先将其移入回收站");
        }
        return surveyMapper.deleteById(id);
    }

    public List<Survey> listRecycleByUserId(Integer userId) {
        return surveyMapper.findRecycleByUserId(userId);
    }

    public List<Survey> listAllRecycle() {
        return surveyMapper.findAllRecycle();
    }

    // ===== 访问次数 =====
    public int incrementViewCount(String shareCode) {
        return surveyMapper.incrementViewCount(shareCode);
    }

    public int sumViewCount() {
        return surveyMapper.sumViewCount();
    }

    // ===== 复制问卷 =====
    @Transactional
    public Survey copySurvey(Integer sourceId, Integer userId) {
        Survey old = surveyMapper.findById(sourceId);
        if (old == null) return null;

        Survey newSurvey = new Survey();
        newSurvey.setUserId(userId);
        newSurvey.setTitle(old.getTitle() + "-副本");
        newSurvey.setDescription(old.getDescription());
        newSurvey.setStatus(0);
        newSurvey.setShareCode(null);
        newSurvey.setDeleted(0);
        newSurvey.setIsTemplate(0);
        newSurvey.setViewCount(0);
        surveyMapper.insert(newSurvey);

        List<Question> questions = questionMapper.findBySurveyId(sourceId);
        for (Question q : questions) {
            Question newQ = new Question();
            newQ.setSurveyId(newSurvey.getId());
            newQ.setType(q.getType());
            newQ.setTitle(q.getTitle());
            newQ.setRequired(q.getRequired());
            newQ.setSortOrder(q.getSortOrder());
            questionMapper.insert(newQ);

            if (q.getType() == 1 || q.getType() == 2) {
                List<Option> options = optionMapper.findByQuestionId(q.getId());
                for (Option opt : options) {
                    Option newOpt = new Option();
                    newOpt.setQuestionId(newQ.getId());
                    newOpt.setContent(opt.getContent());
                    newOpt.setSortOrder(opt.getSortOrder());
                    optionMapper.insert(newOpt);
                }
            }
        }
        return newSurvey;
    }

    // ===== 模板 =====
    public List<Survey> listTemplates(String keyword) {
        return surveyMapper.findTemplates(keyword);
    }

    @Transactional
    public Survey createFromTemplate(Integer templateId, Integer userId) {
        return copySurvey(templateId, userId);
    }

    public int setTemplate(Integer id, Integer isTemplate, String category) {
        return surveyMapper.setTemplate(id, isTemplate, category);
    }

    // ===== 分页：全部问卷（管理员） =====
    public PageResult<Survey> pageAllSurveys(int pageNum, int pageSize, String keyword, Integer status) {
        PageHelper.startPage(pageNum, pageSize);
        List<Survey> list = surveyMapper.findPageByCondition(keyword, status);
        PageInfo<Survey> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(), pageNum, pageSize);
    }

    // ===== 分页：回收站 =====
    public PageResult<Survey> pageRecycleByUser(Integer userId, int pageNum, int pageSize, String keyword) {
        PageHelper.startPage(pageNum, pageSize);
        List<Survey> list = surveyMapper.findRecyclePageByUser(userId, keyword);
        PageInfo<Survey> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(), pageNum, pageSize);
    }

    public PageResult<Survey> pageRecycleAll(int pageNum, int pageSize, String keyword) {
        PageHelper.startPage(pageNum, pageSize);
        List<Survey> list = surveyMapper.findRecyclePageAll(keyword);
        PageInfo<Survey> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(), pageNum, pageSize);
    }

    // ===== 用户个人统计 =====
    public Map<String, Object> getUserStatistics(Integer userId) {
        List<Survey> surveys = surveyMapper.findByUserId(userId);
        surveys = surveys.stream()
                .filter(s -> s.getIsTemplate() == null || s.getIsTemplate() == 0)
                .collect(Collectors.toList());

        long total = surveys.size();
        long published = surveys.stream()
                .filter(s -> s.getStatus() != null && s.getStatus() == 1).count();
        long draft = surveys.stream()
                .filter(s -> s.getStatus() == null || s.getStatus() == 0).count();
        long ended = surveys.stream()
                .filter(s -> s.getStatus() != null && s.getStatus() == 2).count();

        int responses = 0;
        for (Survey s : surveys) {
            responses += answerMapper.countBySurveyId(s.getId());
        }

        List<Survey> recent = new ArrayList<>(surveys);
        recent.sort((a, b) -> {
            if (a.getCreatedTime() == null) return 1;
            if (b.getCreatedTime() == null) return -1;
            return b.getCreatedTime().compareTo(a.getCreatedTime());
        });
        if (recent.size() > 5) recent = recent.subList(0, 5);

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("published", published);
        result.put("draft", draft);
        result.put("ended", ended);
        result.put("responses", responses);
        result.put("recent", recent);
        return result;
    }

    // ===== 系统级总览（管理员） =====
    public Map<String, Object> getSystemOverview() {
        List<Survey> all = surveyMapper.findAll();
        List<Survey> normalSurveys = all.stream()
                .filter(s -> s.getIsTemplate() == null || s.getIsTemplate() == 0)
                .collect(Collectors.toList());

        long total = normalSurveys.size();
        long published = normalSurveys.stream()
                .filter(s -> s.getStatus() != null && s.getStatus() == 1).count();
        long draft = normalSurveys.stream()
                .filter(s -> s.getStatus() == null || s.getStatus() == 0).count();
        long ended = normalSurveys.stream()
                .filter(s -> s.getStatus() != null && s.getStatus() == 2).count();

        int responses = 0;
        for (Survey s : normalSurveys) {
            responses += answerMapper.countBySurveyId(s.getId());
        }

        int views = surveyMapper.sumViewCount();

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("published", published);
        result.put("draft", draft);
        result.put("ended", ended);
        result.put("responses", responses);
        result.put("views", views);
        return result;
    }

    // ===== 定时任务 =====
    @Scheduled(cron = "0 * * * * ?")
    public void autoPublishAndClose() {
        List<Survey> toPublish = surveyMapper.findPendingPublish();
        for (Survey s : toPublish) {
            if (s.getShareCode() == null || s.getShareCode().isEmpty()) {
                s.setShareCode(generateShareCode());
            }
            s.setStatus(1);
            surveyMapper.update(s);
            System.out.println("⏰ 自动发布问卷：" + s.getTitle() + " (id=" + s.getId() + ")");
        }

        List<Survey> toClose = surveyMapper.findPendingClose();
        for (Survey s : toClose) {
            s.setStatus(2);
            surveyMapper.update(s);
            System.out.println("⏰ 自动关闭问卷：" + s.getTitle() + " (id=" + s.getId() + ")");
        }
    }
}