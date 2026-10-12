package com.survey.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * SPA 前端路由支持：把前端路由转发到 index.html，交给 Vue Router 处理。
 *
 * ── 为什么需要它 ──────────────────────────────────────
 *   Vue Router 用的是 history 模式（不是 #/xxx 那种 hash 模式），
 *   所以用户直接访问 /my/surveys、或在某个页面按 F5 时，浏览器会把整个路径发给后端。
 *   如果没有这个 Controller，后端找不到这个"文件"就会返回 404。
 *   这里统一 forward 到 index.html，接管的活儿交给前端路由。
 *
 * ── ★ 为什么用通配转发，而不是手工清单 ────────────────
 *   把前端路由**逐条列出来**属于双份维护：新增一个页面忘了同步，那个页面一刷新就 404。
 *   所以这里只保留一条通配：**「不以点结尾的路径段」**都转发给前端。
 *     · 带点的路径（/favicon.svg、/assets/index-abc.js、/doc.html）**不会命中**，
 *       仍然由静态资源处理器 / 文档处理器接管；
 *     · 前端新增任意深度的路由都不用再来改这里。
 *
 * ── 两条必须守住的边界 ────────────────────────────────
 *   1. **以 "/api/" 开头的路径绝不能被吞掉**：如果某个 /api 路径没有任何 @RequestMapping 匹配，
 *      它不应返回一段 HTML（前端会拿去当 JSON 解析，报出莫名其妙的错），
 *      而应保持 **404** —— 下面显式判断并抛出 NOT_FOUND。
 *   2. 已有映射的接口（如 /api/health）优先级高于通配，不受影响。
 *
 * ── 只在生产/同源部署时需要 ────────────────────────────
 *   开发时前端跑在 5173（Vite dev server），它自带 history fallback，用不到这里；
 *   这个类是为「dist 拷进后端 static、前后端同源单包部署」准备的。
 */
@Controller
public class SpaController {

    private static final Logger LOG = LoggerFactory.getLogger(SpaController.class);

    /**
     * 通配转发所有"前端路由形态"的 GET 请求。
     *
     * 模式说明：路径模式是 `"/**"` 之后再接一段"最后不含点"的路径段，
     *   用于匹配任意深度的前端路由；单独的 `"/"` 命中首页（它没有路径段）。
     *   关键是路径段的正则 `[^\.]*` —— 它把静态资源（带扩展名）挡在外面。
     */
    @GetMapping({"/", "/**/{path:[^\\.]*}"})
    public String forward(HttpServletRequest request) {
        String uri = request.getRequestURI();

        // 边界 1：/api/** 没有匹配到接口时，必须是 404，而不是一段 HTML
        if (uri.startsWith("/api/")) {
            LOG.debug("未匹配的 API 路径，返回 404：{}", uri);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        return "forward:/index.html";
    }
}
