package com.survey.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * SPA 前端路由支持：history 模式的路径统一 forward 到 index.html，交给 Vue Router。
 *
 * ★ 不用手工路由清单（新增页面漏同步就 404）：只转发“最后一段不含点”的路径，
 *   带扩展名的静态资源与文档页面仍由各自的处理器接管。
 * ★ /api/** 未匹配到接口时必须保持 404，不能返回一段 HTML（前端会当 JSON 解析报错）。
 */
@Controller
public class SpaController {

    private static final Logger LOG = LoggerFactory.getLogger(SpaController.class);

    /**
     * 通配转发前端路由形态的 GET 请求；路径段正则 `[^\.]*` 把带扩展名的静态资源挡在外面。
     */
    @GetMapping({"/", "/**/{path:[^\\.]*}"})
    public String forward(HttpServletRequest request) {
        String uri = request.getRequestURI();

        // /api/** 未匹配到接口时保持 404，不能返回一段 HTML（前端会当 JSON 解析）
        if (uri.startsWith("/api/")) {
            LOG.debug("未匹配的 API 路径，返回 404：{}", uri);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        return "forward:/index.html";
    }
}
