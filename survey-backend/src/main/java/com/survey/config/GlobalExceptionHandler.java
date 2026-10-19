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
 * 全局异常处理 + 系统级异常落库；只增强 com.survey.controller 包。
 *
 * ★ 口径：可预期的 4xx 不写 t_error_log（BizException 400 / ForbiddenException 403 /
 *   @Valid 与参数绑定 400）；只有系统级故障才记日志，并统一 500 + 固定文案、不回显 message。
 * ★ 响应形状固定 {success:false, code, message, timestamp}，前端只依赖 HTTP 状态码。
 */
@Hidden
@RestControllerAdvice(basePackages = "com.survey.controller")
public class GlobalExceptionHandler {

    /** 统一用 SLF4J：System.out/err/printStackTrace 在生产会丢。 */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Autowired
    private ErrorLogService errorLogService;

    /** 注入 UserService 而非 UserMapper：配置层直连数据层会绕过分层。 */
    @Autowired
    private UserService userService;

    /**
     * 业务异常 → 400；可预期的失败，不写 t_error_log（否则异常日志会被正常拒绝刷满）。
     * 与 handleRuntimeException 的唯一区别就是“不写日志、回显 message”。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBizException(BizException e) {
        return buildResponse(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 权限不足（{@code @RequireAdmin} / {@code @RequirePermission} 切面抛出）→ **403**。
     *
     * 不复用 BizException 的 400：400 是“请求参数/状态不对”，403 是“身份做不了这件事”，
     * 前端据此给不同引导；同样不写 t_error_log。
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException e) {
        return buildResponse(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * Bean Validation 失败（{@code @Valid @RequestBody}）→ 400 + 第一条字段错误。
     * 必须接住：否则会掉进 handleException 被当成 500，前端不知道是哪个字段。
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
     * 客户端请求格式不对（JSON 非法 / 参数类型错 / 缺必填参数或请求头 / Content-Type 不支持）→ 400。
     *
     * 这些异常在参数绑定阶段抛出，语义是“客户端传错”，不写 t_error_log、不回显框架原文。
     *
     * @see #describeClientError(Exception)
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class,
            HttpMediaTypeNotSupportedException.class
    })
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception e) {
        // 有意不写异常日志：客户端传错参数不算故障
        return buildResponse(HttpStatus.BAD_REQUEST, describeClientError(e));
    }

    /** 翻译成一句人话；不回显 e.getMessage()（会带请求体片段或内部类型名）。 */
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
     * 它继承 ResponseStatusException，不单独接住会被 handleRuntimeException 兜成 500。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(HandlerMethodValidationException e) {
        // 不调它取字段错误的 API：各版本访问器名不一致（新版本没有 getAllValidationResults）
        LOG.warn("方法参数校验失败：{}", e.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "参数校验失败");
    }

    /**
     * ResponseStatusException → 用它自带的状态码 + 原因（4xx 不写日志，5xx 才记）。
     * 不单独接住会被 RuntimeException 兜底成 500，并污染异常日志。
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
     * 系统级故障兜底 → 500 + 固定文案，不回显 e.getMessage()（详细原因只进 t_error_log）。
     * 能走到这里的已不是业务失败（业务失败全抛 BizException → 400）。
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试");
    }

    /**
     * 参数错误 → 400，消息前加“参数错误：”前缀。
     * 注意：这一条会写 t_error_log（与另外三条 400 不同）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.BAD_REQUEST, "参数错误：" + e.getMessage());
    }

    /** 空指针 → 500；不回显原始 message（避免暴露内部细节）。 */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(NullPointerException e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误（空指针）");
    }

    /**
     * 其它异常兜底 → 500；不回显原始 message（可能带表名、SQL 片段或内部网络细节）。
     * 详细原因照旧写 t_error_log，客户端只看到一句无用信息。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        saveErrorLog(e);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统繁忙，请稍后重试");
    }

    /** 异常落库（异步）；非 Web 线程（如定时任务）拿不到请求上下文时直接放弃。 */
    private void saveErrorLog(Exception e) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            HttpServletRequest request = attrs.getRequest();

            String uri = request.getRequestURI();

            // 排除文档/静态/监控请求：否则“文档 404”每次访问都记一条，形成循环
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
            // request_uri 是 varchar(300)：不截断会让“记录异常”这一步自己抛异常
            log.setRequestUri(uri.length() > 300 ? uri.substring(0, 300) : uri);
            log.setRequestMethod(request.getMethod());
            log.setErrorType(e.getClass().getSimpleName());   // 只存类名，如 "RuntimeException"

            // 消息可能为 null，兜底用类全名
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = e.getClass().getName();
            }
            log.setErrorMessage(msg);

            log.setIp(WebUtils.getClientIp(request));

            // 堆栈截断，防止单条记录过大
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stackTrace = sw.toString();
            if (stackTrace.length() > 5000) {
                stackTrace = stackTrace.substring(0, 5000) + "\n... [已截断]";
            }
            log.setStackTrace(stackTrace);

            // 身份头由 JwtAuthFilter 写入；未登录请求（如注册）没有
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
            // 记录日志失败不能影响业务，更不能掩盖原始异常
            LOG.error("记录异常日志失败：{}", ex.getMessage(), ex);
        }
    }

    /** 统一失败响应：{success:false, code: HTTP 状态码, message, timestamp}。 */
    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", status.value());
        body.put("message", message);
        body.put("timestamp", LocalDateTime.now().toString());
        return new ResponseEntity<>(body, status);
    }
}
