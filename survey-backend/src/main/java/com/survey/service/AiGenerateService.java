package com.survey.service;

import com.survey.common.SurveyStatus;
import com.survey.common.BizException;
import com.survey.entity.ErrorLog;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.common.TextLimits;
import com.survey.common.QuestionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

/**
 * AI 生成问卷：外部模型调用 + 解析 + 落库（短事务）都在本类，Controller 只是薄壳。
 *
 * ── 为什么必须把外部调用与事务分开 ─────────────────────
 *   若落库代码与 `@Transactional` 一起包住外部 LLM 调用（readTimeout 120 秒）与失败重试的
 *   Thread.sleep：单个请求会持有数据库连接好几分钟，几个并发就能把 HikariCP 连接池耗尽、拖垮全站；
 *   而 AI 调用成功之前根本没有任何写库动作 —— 那段事务对网络等待毫无保护意义。
 *
 * ── 分层 ──────────────────────────────────────────────
 *   Controller 不认识任何 Mapper / 事务注解 / okhttp，三层分工是：
 *     controller/AiGenerateController → 参数校验 + 组装响应（薄壳，不认识 okhttp）
 *     service/AiGenerateService       → 编排：调模型 → 解析 → 落库（短事务）→ 失败写异常日志
 *     service/QwenClient              → 唯一的外部模型边界（HTTP / 超时 / 重试 / 响应解析）
 */
@Service
public class AiGenerateService {

    private static final Logger LOG = LoggerFactory.getLogger(AiGenerateService.class);

    @Autowired
    private SurveyMapper surveyMapper;
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private OptionMapper optionMapper;
    @Autowired
    private QwenClient qwenClient;
    @Autowired
    private ErrorLogService errorLogService;

    /**
     * 事务模板 —— 专用于在**无事务的编排方法**（generateAndSave）里显式包住落库那几步。
     * ★ 为什么需要它：generateAndSave 内部调 saveGenerated 属于**同类内部调用**，
     *   Spring AOP 代理不介入，saveGenerated 上的 @Transactional 不会生效（详见 generateAndSave 的注释）。
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 把 AI 返回并解析好的问卷结构落库（草稿状态，归属为 userId）。
     *
     * ★ 事务只包住下面这几条 INSERT（毫秒级）——这是本类存在的全部意义。
     *
     * @param userId 归属用户（来自 X-User-Id 请求头，**不接受请求体指定**，否则可伪造归属）
     * @param topic  用户输入的主题；AI 没给出 title 时用它兜底
     * @param parsed AI 响应解析结果：{@code {title, description, questions:[{title,type,options}]}}
     * @return 新建问卷的 id
     * @throws BizException AI 没返回任何有效题目时（前端会拿到 400 + 明确文案）
     */
    @Transactional
    public int saveGenerated(Integer userId, String topic, Map<String, Object> parsed) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions = (List<Map<String, Object>>) parsed.get("questions");
        if (questions == null || questions.isEmpty()) {
            throw new BizException("AI 未返回有效题目，请重试");
        }

        String title = (String) parsed.get("title");
        String description = (String) parsed.get("description");
        if (title == null || title.trim().isEmpty()) {
            title = topic;
        }
        if (description == null) {
            description = "";
        }

        // ★ AI 返回的文本同样必须过长度校验。
        //   这条路**不经过** SurveyService.validateSurveyText，而 t_survey.title/description
        //   分别是 varchar(200)/varchar(500) —— 模型多吐几个字就会让 INSERT 被 MySQL 拒绝，
        //   用户看到的却是"AI 生成失败，请稍后重试"（重试多少次都一样，因为模型每次都这么吐）。
        //   ★ 这里选择**抛异常**而不是截断：截断会静默改掉模型给的内容，
        //     而整次生成失败是可回滚的（saveGenerated 有事务，不会留下半成品问卷）。
        TextLimits.requireWithin(title, TextLimits.SURVEY_TITLE, "AI 生成的问卷标题");
        TextLimits.requireWithin(description, TextLimits.SURVEY_DESCRIPTION, "AI 生成的问卷描述");

        Survey s = new Survey();
        s.setUserId(userId);
        s.setTitle(title);
        s.setDescription(description);
        s.setStatus(SurveyStatus.DRAFT.code());        // 草稿：AI 生成的结果要先让用户确认再发布
        s.setIsTemplate(0);
        s.setDeleted(0);
        s.setViewCount(0);
        surveyMapper.insert(s);

        int qOrder = 0;
        for (Map<String, Object> q : questions) {
            Object titleObj = q.get("title");
            Object typeObj = q.get("type");
            // AI 偶尔会吐出缺字段的题目，跳过而不是让整次生成失败
            if (titleObj == null || typeObj == null) continue;

            // ★ **宽容解析题型**。
            //   若直接做 ((Number) typeObj).intValue() —— 模型把类型写成字符串
            //   （形如 "type": "1" —— 对 LLM 来说太常见了）就会抛 ClassCastException，
            //   被外层 catch 统一转成 400「AI 生成失败，请稍后重试」并整次回滚 ——
            //   而**那次调用是已经计过费的**，用户重试大概率还会拿到同样的输出、同样失败。
            //   注释本来承诺的是"缺字段就跳过"，现在类型认不出来也按同一策略跳过该题。
            int type;
            try {
                type = (typeObj instanceof Number n) ? n.intValue()
                        : Integer.parseInt(typeObj.toString().trim());
            } catch (NumberFormatException ex) {
                LOG.warn("AI 返回的题型不是数字，跳过该题：type={} title={}", typeObj, titleObj);
                continue;
            }
            if (type != QuestionType.SINGLE.code() && type != QuestionType.MULTI.code()
                    && type != QuestionType.TEXT.code()) {
                LOG.warn("AI 返回了未知题型，跳过该题：type={} title={}", type, titleObj);
                continue;
            }

            Question question = new Question();
            question.setSurveyId(s.getId());
            question.setTitle(titleObj.toString());
            // ★ 同类校验：题干超长时同样会炸 500（t_question.title varchar(500)）
            TextLimits.requireWithin(question.getTitle(), TextLimits.QUESTION_TITLE, "AI 生成的题目内容");
            question.setType(type);
            question.setRequired(0);
            question.setSortOrder(qOrder++);
            questionMapper.insert(question);

            Object opts = q.get("options");
            if (opts instanceof List) {
                int oOrder = 0;
                for (Object o : (List<?>) opts) {
                    if (o == null) continue;
                    Option opt = new Option();
                    opt.setQuestionId(question.getId());
                    opt.setContent(o.toString());
                    // ★ 选项同理（t_option.content varchar(500)）
                    TextLimits.requireWithin(opt.getContent(), TextLimits.OPTION_CONTENT, "AI 生成的选项内容");
                    opt.setSortOrder(oOrder++);
                    optionMapper.insert(opt);
                }
            }
        }
        return s.getId();
    }

    /**
     * 编排一次完整的 AI 生成：调模型 → 解析 → 落库，失败时写异常日志并抛对用户友好的 BizException。
     *
     * ★ 这个方法**没有** @Transactional（有意为之，别加）：
     *   它内部包含最长 120 秒的外部 HTTP 调用与重试等待 —— 事务只包住 saveGenerated() 的几条 INSERT。
     *   给这里加 @Transactional 就会让一个请求占着数据库连接好几分钟，必须守住这条边界。
     *
     * ★ 请求上下文（uri / method / ip）由 Controller 以参数传入，而不是在这里注入
     *   HttpServletRequest：Service 保持与 Web 容器无关，单元测试可以直接 new 出来调。
     *
     * @return 新建问卷的 id
     * @throws BizException 对用户可见的失败（主题校验、限流提示、AI 未返回题目、生成失败）
     */
    public int generateAndSave(Integer userId, String topic, String extra,
                               String requestUri, String requestMethod, String clientIp) {
        try {
            Map<String, Object> parsed = qwenClient.generateSurveyJson(topic, extra);
            // ★ 必须用 TransactionTemplate 显式开事务，**不能**写成 `return saveGenerated(...)`：
            //   那种**同类内部调用**不经过 Spring AOP 代理，saveGenerated 上的 @Transactional
            //   **完全不生效** —— 落库的几条 INSERT（问卷 → 题目 → 选项）各自 autocommit，
            //   中途失败就留下"有问卷没题目""有题目没选项"的半成品，
            //   而 saveGenerated 的契约是"中途失败必须整体回滚"。
            //   ★ 也**不能**图省事给 generateAndSave 整个方法加 @Transactional：
            //     它上面有最长 120 秒的外部 LLM 调用与重试等待，把事务拉这么长
            //     会让一个请求占着数据库连接好几分钟。
            Integer newSurveyId = transactionTemplate.execute(status -> saveGenerated(userId, topic, parsed));
            return newSurveyId == null ? 0 : newSurveyId;
        } catch (BizException e) {
            // 业务拒绝（例如"AI 未返回有效题目"）是可预期的 —— **不写异常日志**，
            // 与项目"业务失败抛 BizException、不污染异常日志"的既有约定一致。
            throw e;
        } catch (Exception e) {
            LOG.error("AI 生成失败：{}", e.getMessage(), e);
            // ★ 真异常（网络 / 格式 / 限流）照旧写进 t_error_log —— 这才是"异常日志"该有的内容
            saveAiErrorLog(e, userId, requestUri, requestMethod, clientIp);

            if (e instanceof QwenClient.RateLimitedException) {
                // 限流是可预期的（免费额度排队），给明确等待提示而不是把堆栈甩给用户
                throw new BizException("AI 服务当前限流（免费额度排队中），请等 10~30 秒后重试");
            }
            // ★ 对外只给固定文案：不把底层网络信息 / AI 原始返回透传给客户端，
            //   详细原文已经写进 t_error_log，管理员在异常日志页面能查到。
            throw new BizException("AI 生成失败，请稍后重试");
        }
    }

    /**
     * ★ AI 生成失败时写入 t_error_log。
     * 若只 printStackTrace、不抛给 GlobalExceptionHandler，「异常日志」页面里就查不到 AI 生成的失败记录。
     */
    private void saveAiErrorLog(Exception e, Integer userId,
                                String requestUri, String requestMethod, String clientIp) {
        try {
            ErrorLog log = new ErrorLog();
            log.setUserId(userId);
            log.setRequestUri(requestUri);
            log.setRequestMethod(requestMethod);
            log.setErrorType(e instanceof QwenClient.RateLimitedException
                    ? "AiRateLimit" : e.getClass().getSimpleName());

            String msg = e.getMessage() == null ? "AI 生成失败" : e.getMessage();
            log.setErrorMessage(msg.length() > 900 ? msg.substring(0, 900) : msg);
            log.setIp(clientIp);

            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stackTrace = sw.toString();
            log.setStackTrace(stackTrace.length() > 5000 ? stackTrace.substring(0, 5000) : stackTrace);

            // 与 GlobalExceptionHandler 保持一致：不设 createTime，交给数据库默认值
            errorLogService.save(log);
        } catch (Exception ignore) {
            // 记日志失败绝不影响业务
            LOG.error("记录 AI 异常日志失败：{}", ignore.getMessage(), ignore);
        }
    }
}
