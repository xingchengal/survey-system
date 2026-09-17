package com.survey.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SPA 前端路由支持
 * 刷新前端路由时，转发到 index.html 让 Vue Router 处理
 */
@Controller
public class SpaController {

    /**
     * 静态路由：明确列举前端所有固定路径
     */
    @RequestMapping(value = {
            "/",
            "/login",
            "/register",
            "/profile",
            "/dashboard",
            "/users",
            "/statistics",
            "/admin/surveys",
            "/admin/recycle",
            "/admin/announcements",
            "/admin/logs",
            "/admin/demo",
            "/admin/profile",
            "/my/dashboard",
            "/my/surveys",
            "/my/recycle",
            "/my/profile"
    })
    public String forwardStatic() {
        return "forward:/index.html";
    }

    /**
     * 动态路由：带参数的路径
     */
    @RequestMapping(value = {
            "/my/surveys/{id}/edit",
            "/my/surveys/{id}/statistics",
            "/s/{shareCode}"
    })
    public String forwardDynamic() {
        return "forward:/index.html";
    }
}