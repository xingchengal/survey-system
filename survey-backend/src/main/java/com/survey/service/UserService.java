package com.survey.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.PageResult;
import com.survey.entity.*;
import com.survey.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.List;

@Service
public class UserService {

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
    @Autowired
    private NotificationMapper notificationMapper;

    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String register(User user) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            return "用户名不能为空";
        }
        if (user.getPassword() == null || user.getPassword().length() < 6) {
            return "密码至少6位";
        }
        if (userMapper.findByUsername(user.getUsername()) != null) {
            return "用户名已被注册";
        }
        user.setPassword(md5(user.getPassword()));
        if (user.getNickname() == null || user.getNickname().isEmpty()) {
            user.setNickname(user.getUsername());
        }
        user.setRole("user");
        userMapper.insert(user);
        return "注册成功";
    }

    public User login(String username, String password) {
        User user = userMapper.findByUsername(username);
        if (user == null) return null;
        if (!user.getPassword().equals(md5(password))) return null;
        return user;
    }

    public User getById(Integer id) {
        return userMapper.findById(id);
    }

    public List<User> listNormalUsers() {
        return userMapper.findAllNormalUsers(null);
    }

    public PageResult<User> listNormalUsersPage(int pageNum, int pageSize, String keyword) {
        PageHelper.startPage(pageNum, pageSize);
        List<User> list = userMapper.findAllNormalUsers(keyword);
        PageInfo<User> pageInfo = new PageInfo<>(list);
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(), pageNum, pageSize);
    }

    /**
     * ★ 删除用户 + 级联删除所有关联数据
     */
    @Transactional
    public int deleteUser(Integer id) {
        User user = userMapper.findById(id);
        if (user == null) return 0;

        // 1. 找出该用户的所有问卷（含回收站）
        List<Survey> surveys = surveyMapper.findByUserIdIncludeDeleted(id);

        for (Survey s : surveys) {
            // 1.1 删除该问卷的所有答卷明细
            List<Answer> answers = answerMapper.findBySurveyId(s.getId());
            for (Answer a : answers) {
                answerDetailMapper.deleteByAnswerId(a.getId());
            }
            // 1.2 删除该问卷的所有答卷
            answerMapper.deleteBySurveyId(s.getId());
            // 1.3 删除该问卷的选项
            optionMapper.deleteBySurveyId(s.getId());
            // 1.4 删除该问卷的题目
            questionMapper.deleteBySurveyId(s.getId());
            // 1.5 删除问卷
            surveyMapper.deleteById(s.getId());
        }

        // 2. 删除该用户的通知
        notificationMapper.deleteAllByUser(id);

        // 3. 删除用户本身
        return userMapper.deleteById(id);
    }

    public int countNormalUsers() {
        return userMapper.countNormalUsers();
    }

    public String updateNickname(Integer userId, String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            return "昵称不能为空";
        }
        User user = userMapper.findById(userId);
        if (user == null) return "用户不存在";
        userMapper.updateNickname(userId, nickname.trim());
        return "修改成功";
    }

    public String updatePassword(Integer userId, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isEmpty()) return "请输入原密码";
        if (newPassword == null || newPassword.length() < 6) return "新密码至少6位";
        User user = userMapper.findById(userId);
        if (user == null) return "用户不存在";
        if (!user.getPassword().equals(md5(oldPassword))) return "原密码错误";
        userMapper.updatePassword(userId, md5(newPassword));
        return "修改成功";
    }

    public String updateAvatar(Integer userId, String avatar) {
        if (avatar == null || avatar.isEmpty()) return "头像不能为空";
        if (avatar.length() > 3 * 1024 * 1024) return "图片过大，请选择 1MB 以内的图片";
        userMapper.updateAvatar(userId, avatar);
        return "修改成功";
    }

    public String updateContact(Integer userId, String phone, String email) {
        if (phone != null && !phone.trim().isEmpty()) {
            if (!phone.matches("^1[3-9]\\d{9}$")) {
                return "手机号格式不正确";
            }
        }
        if (email != null && !email.trim().isEmpty()) {
            if (!email.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
                return "邮箱格式不正确";
            }
        }
        userMapper.updateContact(userId,
                phone == null ? "" : phone.trim(),
                email == null ? "" : email.trim());
        return "修改成功";
    }
}