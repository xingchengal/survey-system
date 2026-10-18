package com.survey.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.survey.common.BizException;
import com.survey.common.ForbiddenException;
import com.survey.entity.ErrorLog;
import com.survey.entity.User;
import com.survey.service.ErrorLogService;
import com.survey.service.UserService;
import com.survey.util.WebUtils;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理 + 异常日志落库 —— `com.survey.controller` 包内的 Controller 抛出的异常都汇总到这里。
 *
 * ── 它做两件事 ────────────────────────────────────────
 *   1. 把异常翻译成统一的 JSON：{success:false, code, message, timestamp}
 *   2. 把**系统级**异常写进 t_error_log（可预期的 4xx 不写；异步落库见 ErrorLogService.save 上的 @Async）
 *
 * ── 异常 → HTTP 状态码的映射约定（前端依赖这个）────────
 *   BizException                  → **400** + 可读 message（可预期的业务失败：无权限、口令错、状态不允许）
 *   @Valid 校验失败                → **400** + 第一条字段错误（MethodArgumentNotValidException）
 *   请求参数绑定失败               → **400** + 人话提示（见 handleMalformedRequest）：
 *       请求体不是合法 JSON / ?pageNum=abc / 缺必填参数或请求头 / Content-Type 不支持
 *   IllegalArgumentException      → **400** + "参数错误：…"（★ 这一条**会**写 t_error_log）
 *   ResponseStatusException       → **它自带的状态码** + 自带文案（4xx 不写异常日志；若不单独接住，
 *                                   会被下面的 RuntimeException 兜成 500 并污染异常日志）
 *   其它 RuntimeException         → **500** + 固定文案（系统级故障，**不回显**内部 message）
 *   NullPointerException          → **500** + 固定文案
 *   ★ 上面三类 400（BizException / @Valid / 参数绑定）都**不写 t_error_log** ——
 *     它们全是"客户端传错了"，不是程序故障（这是本项目异常治理的核心口径）。
 *   ★ 注：还有一条 handleMethodValidation（HandlerMethodValidationException → 400），
 *     是为"把校验注解写到 @RequestParam/@PathVariable 上"预留的入口，目前尚无使用者。
 *   ★ 项目里业务失败已全部抛 BizException，所以能走到 500 的只剩系统级故障：
 *     口令算法不可用（正常不可能发生）、分享码连续撞码耗尽，以及未预料的空指针/类型错误。
 *     ★ AI 调用失败**不会**落到这里：AiGenerateService.generateAndSave() 会把限流、解析、网络
 *       各类失败统一转成 BizException → 走上面那条 400。
 *
 * ── 三个边界处理 ──────────────────────────────────────
 *   1. 跳过文档/静态/监控类路径 —— 否则"文档 404"每访问一次就记一条，形成循环记录；
 *   2. 堆栈截断到 5000 字符 —— 超长堆栈会把单条日志撑得很大；
 *   3. saveErrorLog 整体 try-catch —— 记日志失败绝不能掩盖/影响原始异常。
 *
 * ── @Hidden 与增强范围 ────────────────────────────────
 *   @Hidden 不让这个类出现在 Knife4j 接口文档里（它没有对外接口，只是内部增强）。
 *   basePackages = "com.survey.controller" 只增强**该包内**的 Controller
 *   （config/SpaController 不在其中，它抛的 ResponseStatusException 由容器按自带状态码处理）；
 *   Service/Mapper 的异常仍会向上冒泡到这里（因为最终是 Controller 调用它们）。
 *
 * ── 客户端 IP 从哪来 ──────────────────────────────────
 *   IP 统一走 util/WebUtils.getClientIp()。它优先信任 X-Forwarded-For，而该请求头客户端可以伪造 ——
 *   所以日志里的 IP 仅供参考，**不能**用于任何安全判决（限流已单独改用容器给出的真实连接 IP，
 *   见 RateLimitInterceptor）。
 */
@Hidden
@RestControllerAdvice(basePackages = "com.survey.controller")
public class GlobalExceptionHandler {

    /** 统一用 SLF4J 记录：System.out/err/printStackTrace 在生产会直接丢失。 */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired
    private ErrorLogService errorLogService;

    /**
     * ★ 这里注入 UserService 而不是 UserMapper：配置层直连数据层会绕过 Service，
     *   破坏"Controller/切面 → Service → Mapper"的分层；走 Service 也便于以后
     *   给取用户加缓存或权限过滤。
     */
    @Autowired
    private UserService userService;

    /**
     * 业务异常（★ 推荐的抛法）：密码错误、无权限、状态不允许、目标不存在…… → 400。
     *
     * ★ 与下面 handleRuntimeException 的**唯一也是关键的区别：不写 t_error_log**。
     *   理由：这类是"用户操作不合法"，不是程序出错。若也记日志，
     *   用户输错一次密码、点一次没权限的按钮就多一条"异常"，
     *   「异常日志」页面会被正常业务拒绝刷满，真异常反而被淹没。
     *
     * ★ 语义分工（前端据此只依赖 HTTP 状态码）：
     *     BizException            → 400 + message（可预期的业务失败，前端直接把 message 弹给用户）
     *     RuntimeException 等     → 500 + 固定文案（系统级故障，并记异常日志）
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBizException(BizException e) {
        return buildResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 权限不足（{@code @RequirePermission} 切面抛出）→ **403**。
     *
     * ★ 为什么不复用 BizException 的 400：见 common/ForbiddenException 的类注释 ——
     *   400 是"这次请求的参数/状态不对"，403 是"你的身份做不了这件事"，
     *   前端据此能给出不同引导（改输入 vs 找管理员授权）。
     * ★ **不写 t_error_log**：与 BizException 同理，这是可预期的业务拒绝，
     *   不是程序故障；记了只会把真正的异常刷走。
     * ★ 注意：{@code @RequireAdmin} 目前仍抛 BizException（400），两套口径在 H2-6 统一。
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException e) {
        return buildResponse(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * Bean Validation 失败（{@code @Valid @RequestBody}）→ **400 + 第一条字段错误**。
     *
     * ★ 这个处理器不能省：@Valid 的失败若不接住，会掉进下面的 handleException(Exception)
     *   兜底，被当成系统故障返回 500，表现为"用户少填一个字段却报系统繁忙"，
     *   前端也拿不到该提示哪个字段。
     *
     * ★ 不写异常日志：与 BizException 同理 —— "参数不合法"是可预期的业务拒绝，
     *   不能因为用户输错一次表单就往 t_error_log 里记一条"异常"。
     *
     * @see com.survey.dto.LoginRequest / com.survey.dto.RegisterRequest（注解声明处）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isEmpty())
                .findFirst()
                .orElse("参数校验失败");
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 客户端请求本身格式不对（请求体不是合法 JSON / 参数类型不对 / 缺少必填参数或请求头 /
     * Content-Type 不支持）→ **400 + 不写异常日志**。
     *
     * ★ 为什么这些异常必须单独接住：它们**不是业务代码抛的**，而是 Spring MVC 在
     *   **参数绑定阶段**抛的，语义是"客户端传错了"，不是"服务端挂了"：
     *     · 请求体不是合法 JSON        → HttpMessageNotReadableException
     *     · `?pageNum=abc` 类型不对     → MethodArgumentTypeMismatchException
     *     · 缺少必填参数 / 请求头       → ServletRequestBindingException（及其两个子类）
     *     · Content-Type 不是 json     → HttpMediaTypeNotSupportedException
     *   若不接住，它们会掉进下面的 RuntimeException / Exception 兜底，变成
     *   **500「系统繁忙，请稍后重试」**，而且**被写进 t_error_log**：
     *     · 前端拿到 500 会以为服务端故障，其实自己少传了个字段；
     *     · 用户输错一次参数就往「异常日志」记一条 —— 正是 BizException 当初要治理的污染。
     *
     * ★ 验收口径：这类请求返回 400，且 t_error_log 不增长。
     *
     * @see #describeClientError(Exception) 文案翻译（**不回显**框架的原始 message）
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class,
            HttpMediaTypeNotSupportedException.class
    })
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception e) {
        // 注意：这里**有意不调 saveErrorLog** —— 客户端传错参数不该污染异常日志
        return buildResponse(HttpStatus.BAD_REQUEST, describeClientError(e));
    }

    /**
     * 把参数绑定异常翻译成一句人话。
     * ★ 不回显 e.getMessage()：框架的原文形如
     *   `JSON parse error: Unrecognized token 'this': was expecting ...` 或
     *   `Failed to convert value of type 'java.lang.String' to required type 'int'` ——
     *   前者会把请求体片段回显出来，后者泄露内部类型名。给用户"哪个参数不对"就够了。
     */
    private String describeClientError(Exception e) {
        if (e instanceof HttpMessageNotReadableException) {
            return "请求体格式不正确（需要合法的 JSON）";
        }
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            return "参数「" + mismatch.getName() + "」格式不正确";
        }
        if (e instanceof HttpMediaTypeNotSupportedException) {
            return "不支持的 Content-Type，请使用 application/json";
        }
        return "缺少必需的请求参数或请求头";
    }

    /**
     * 方法参数级校验失败（{@code @RequestParam} / {@code @PathVariable} 上的约束）→ 400。
     *
     * ★ 为什么也要单独接：它继承自 ResponseStatusException（属于 RuntimeException），
     *   不加这个处理器就会被 handleRuntimeException 兜成 500。
     *   目前项目里还没有在方法参数上写约束，这条是**为下一步迁移预留的入口**
     *   （把散落的 if 校验逐步搬到注解上时不会踩坑）。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(HandlerMethodValidationException e) {
        // ★ 这里刻意**不调用**它取字段错误的 API：该类在 Spring 各版本间的访问器方法名有变动
        //   （Spring Boot 4 / Framework 7 上就没有 getAllValidationResults），
        //   为了一个"预留入口"去绑定具体版本不划算。当前项目也还没有任何方法参数上写约束，
        //   真要用起来时，先把这条的文案改成从异常里取详细信息即可。
        LOG.warn("方法参数校验失败：{}", e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "参数校验失败");
    }

    /**
     * 带 HTTP 状态码的异常（{@link ResponseStatusException}）→ **用它自带的状态码 + 原因**回复。
     *
     * ★ 为什么必须单独接：{@code ResponseStatusException} 继承自 {@code RuntimeException}，
     *   而这个类里**已经有** {@code @ExceptionHandler(RuntimeException.class)} 的兜底 ——
     *   不写这一条，它会被兜成 **500「系统繁忙」**，而且**写进 t_error_log**
     *   （本该是干净的一次 404）。
     *
     * ★ 语义分工：4xx（客户端请求的目标不存在等）是**可预期的** → 不写异常日志，
     *   与 BizException/@Valid/参数绑定那三条 400 保持同一个口径；
     *   5xx 才是服务端故障 → 照常记录，便于排查。
     *
     * ★ 目前唯一的抛出点：{@code SurveyController.getOne()} 对"已删除的字面量端点"
     *   （/api/survey/list 等被 /{id} 接住的路径）抛 404，让老调用方/文档得到如实的文案。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = (e.getReason() != null && !e.getReason().isBlank())
                ? e.getReason() : status.getReasonPhrase();
        if (status.is5xxServerError()) {
            saveErrorLog(e);
        }
        return buildResponse(status, message);
    }

    /**
     * 系统级故障兜底：**500 + 固定文案，不回显 e.getMessage()**。
     *
     * ★ 为什么是 500 而不是 400：项目里业务失败已全部抛 BizException（→ 400），
     *   能走到这里的只剩系统级故障：
     *     · UserService 的 MD5 / PBKDF2 算法不可用（正常不可能发生）
     *     · SurveyService 分享码连续撞码耗尽
     *     · 以及任何未预料的空指针 / 类型转换错误（它们各有专门的处理器，这里只是兜底）
     *   ★ AI 调用失败**不在其中**：它会被 service/AiGenerateService 统一转成 BizException → 400，
     *     根本到不了这个 500 兜底。
     *   这些若返回 400 会让前端以为"是用户输入的问题"，也让 400（可纠正）与
     *   500（服务故障）的边界重新模糊；而且它们的 message 里可能带底层网络/算法细节。
     *   所以统一 500 + 一句无用信息，详细原因照旧写 t_error_log。
     *
     * ★ 与 handleIllegalArgument 的区别：那个**仍然是 400** ——
     *   NumberFormatException 这类"参数格式不对"确实是调用方可纠正的。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试");
    }

    /**
     * 参数错误 → 400，消息前加"参数错误："前缀，便于前端区分。
     * ★ 注意：这一条**会**写 t_error_log（与 BizException / @Valid / 参数绑定那三条 400 不同）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.BAD_REQUEST, "参数错误：" + e.getMessage());
    }

    /** 空指针 → 500。★ 不把原始 message 返回前端（避免暴露内部细节）。 */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(NullPointerException e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误（空指针）");
    }

    /**
     * 兜底：其它所有异常 → 500。
     *
     * ★ **不把异常原文返回给客户端**：MySQL 驱动的异常 message 会带上表名/列名甚至 SQL 片段，
     *   AI 调用失败还会透传底层网络与解析错误文本 —— 等于免费给攻击者提供内部结构情报。
     *   详细原因照旧写进 t_error_log（saveErrorLog），客户端只看到一句无用信息。
     *   这与 handleNullPointer 的写法一致。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试");
    }

    /**
     * 把异常写进 t_error_log（异步）。
     * ★ 只有非 Web 线程（如定时任务）调用时拿不到请求上下文，这时直接放弃记录。
     */
    private void saveErrorLog(Exception e) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            HttpServletRequest request = attrs.getRequest();

            String uri = request.getRequestURI();

            // ★ 排除文档、静态资源、监控等请求（避免"访问文档 404 → 记一条日志"的循环记录）
            if (uri.startsWith("/v3/")
                    || uri.startsWith("/swagger-ui")
                    || uri.startsWith("/doc.html")
                    || uri.startsWith("/webjars")
                    || uri.startsWith("/favicon")
                    || uri.startsWith("/knife4j")
                    || uri.startsWith("/actuator")
                    || uri.startsWith("/error")) {
                return;
            }

            ErrorLog log = new ErrorLog();
            // ★ request_uri 是 varchar(300)，而 URI 由客户端拼（可以带超长 query）——
            //   不截断就会让"记录异常"这一步自己抛异常（ip 同理，见 WebUtils）。
            log.setRequestUri(uri.length() > 300 ? uri.substring(0, 300) : uri);
            log.setRequestMethod(request.getMethod());
            log.setErrorType(e.getClass().getSimpleName());   // 只存类名，如 "RuntimeException"

            // 异常消息（可能为 null，兜底用类全名）
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = e.getClass().getName();
            }
            log.setErrorMessage(msg);

            log.setIp(WebUtils.getClientIp(request));

            // 堆栈信息（截断，防止一条记录过大）
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stackTrace = sw.toString();
            if (stackTrace.length() > 5000) {
                stackTrace = stackTrace.substring(0, 5000) + "\n... [已截断]";
            }
            log.setStackTrace(stackTrace);

            // 用户信息：X-User-Id 由 JwtAuthFilter 写入；未登录的请求（如注册）就没有
            String userIdStr = request.getHeader("X-User-Id");
            if (userIdStr != null && !userIdStr.isEmpty()) {
                try {
                    Integer userId = Integer.parseInt(userIdStr);
                    log.setUserId(userId);
                    User user = userService.getById(userId);
                    if (user != null) {
                        log.setUsername(user.getNickname() != null ? user.getNickname() : user.getUsername());
                    }
                } catch (Exception ignored) {}
            }

            errorLogService.save(log);
        } catch (Exception ex) {
            // 记录日志失败不能影响业务（更不能掩盖原始异常）
            LOG.error("记录异常日志失败：{}", ex.getMessage(), ex);
        }
    }

    /**
     * 统一响应结构 —— 所有异常响应都是这个形状，前端只需处理一种格式。
     * { success: false, code: HTTP 状态码, message: 原因, timestamp: 发生时间 }
     */
    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", status.value());
        body.put("message", message);
        body.put("timestamp", LocalDateTime.now().toString());
        return new ResponseEntity<>(body, status);
    }
}
