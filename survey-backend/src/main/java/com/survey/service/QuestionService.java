package com.survey.service;

import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class QuestionService {

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private OptionMapper optionMapper;

    /**
     * 保存问卷的所有题目
     * 核心逻辑：已有的题目更新（保留 id），新题目插入，用户删掉的题目删除
     * 这样能保证 t_answer_detail 里的 question_id 不会错乱
     */
    @Transactional
    public void saveQuestions(Integer surveyId, List<Question> questions) {
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
            if (q.getType() == 1 || q.getType() == 2) {
                optionMapper.deleteByQuestionId(q.getId());
                if (q.getOptions() != null) {
                    int optOrder = 0;
                    for (Option opt : q.getOptions()) {
                        opt.setId(null);
                        opt.setQuestionId(q.getId());
                        opt.setSortOrder(optOrder++);
                        optionMapper.insert(opt);
                    }
                }
            }
        }

        // 5. 删除本次没传过来的题目（用户真的删掉的）
        for (Question q : existing) {
            if (!keptIds.contains(q.getId())) {
                questionMapper.deleteById(q.getId());
            }
        }
    }

    /**
     * 获取问卷的所有题目（带选项）
     */
    public List<Question> getQuestionsBySurveyId(Integer surveyId) {
        List<Question> questions = questionMapper.findBySurveyId(surveyId);
        for (Question q : questions) {
            if (q.getType() == 1 || q.getType() == 2) {
                q.setOptions(optionMapper.findByQuestionId(q.getId()));
            }
        }
        return questions;
    }
}