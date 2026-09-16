package com.survey.controller;

import com.survey.annotation.OperationLog;
import com.survey.common.PageResult;
import com.survey.entity.User;
import com.survey.service.UserService;
import com.survey.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "用户管理", description = "注册、登录、用户列表等")
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    private static final String DOC_COOKIE_NAME = "doc_token";

    @OperationLog("用户注册")
    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public String register(@RequestBody User user) {
        return userService.register(user);
    }

    // 登录不加日志（避免每次都记录）
    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody User user, HttpServletResponse response) {
        Map<String, Object> result = new HashMap<>();
        User loginUser = userService.login(user.getUsername(), user.getPassword());
        if (loginUser == null) {
            result.put("success", false);
            result.put("message", "用户名或密码错误");
        } else {
            String token = jwtUtil.generateToken(loginUser.getId(), loginUser.getUsername(), loginUser.getRole());

            if ("admin".equals(loginUser.getRole())) {
                Cookie cookie = new Cookie(DOC_COOKIE_NAME, token);
                cookie.setPath("/");
                cookie.setHttpOnly(true);
                cookie.setMaxAge(7 * 24 * 3600);
                response.addCookie(cookie);
            } else {
                Cookie cookie = new Cookie(DOC_COOKIE_NAME, "");
                cookie.setPath("/");
                cookie.setMaxAge(0);
                response.addCookie(cookie);
            }

            result.put("success", true);
            result.put("message", "登录成功");
            result.put("token", token);

            loginUser.setPassword(null);
            result.put("user", loginUser);
        }
        return result;
    }

    // ★ 补：@OperationLog + @Operation
    @OperationLog("退出登录")
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public String logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(DOC_COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return "已退出";
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/profile")
    public User getProfile(@RequestHeader("X-User-Id") Integer userId) {
        User user = userService.getById(userId);
        if (user != null) user.setPassword(null);
        return user;
    }

    // ★ 补：@OperationLog
    @OperationLog("修改昵称")
    @Operation(summary = "修改昵称")
    @PutMapping("/nickname")
    public String updateNickname(@RequestBody Map<String, String> body,
                                 @RequestHeader("X-User-Id") Integer userId) {
        return userService.updateNickname(userId, body.get("nickname"));
    }

    // ★ 补：@OperationLog
    @OperationLog("修改密码")
    @Operation(summary = "修改密码")
    @PutMapping("/password")
    public String updatePassword(@RequestBody Map<String, String> body,
                                 @RequestHeader("X-User-Id") Integer userId) {
        return userService.updatePassword(userId, body.get("oldPassword"), body.get("newPassword"));
    }

    // ★ 补：@OperationLog
    @OperationLog("修改头像")
    @Operation(summary = "修改头像")
    @PutMapping("/avatar")
    public String updateAvatar(@RequestBody Map<String, String> body,
                               @RequestHeader("X-User-Id") Integer userId) {
        return userService.updateAvatar(userId, body.get("avatar"));
    }

    // ★ 补：@OperationLog
    @OperationLog("修改联系方式")
    @Operation(summary = "修改手机号和邮箱")
    @PutMapping("/contact")
    public String updateContact(@RequestBody Map<String, String> body,
                                @RequestHeader("X-User-Id") Integer userId) {
        return userService.updateContact(userId, body.get("phone"), body.get("email"));
    }

    @Operation(summary = "查询全部普通用户（管理员，旧接口）")
    @GetMapping("/list")
    public List<User> listUsers(@RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) {
            throw new RuntimeException("无权限");
        }
        return userService.listNormalUsers();
    }

    @Operation(summary = "分页查询普通用户（管理员，支持关键词搜索）")
    @GetMapping("/page")
    public PageResult<User> pageUsers(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) {
            throw new RuntimeException("无权限");
        }
        return userService.listNormalUsersPage(pageNum, pageSize, keyword);
    }

    @OperationLog("删除用户")
    @Operation(summary = "删除用户（管理员）")
    @DeleteMapping("/delete/{id}")
    public String deleteUser(@PathVariable Integer id, @RequestHeader("X-User-Role") String role) {
        if (!"admin".equals(role)) {
            return "无权限";
        }
        int result = userService.deleteUser(id);
        return result > 0 ? "删除成功" : "删除失败";
    }

    @Operation(summary = "统计普通用户数量")
    @GetMapping("/count")
    public int countUsers() {
        return userService.countNormalUsers();
    }
}