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
 * AI 生成问卷：调模型 + 解析 + 短事务落库；Controller 只是薄壳。
 * ★ 外部 HTTP 不进事务：模型最长 120 秒加等待重试，会把数据库连接占满，几个并发就耗尽连接池。
 * ★ 超时 / 限流 / 重试一次都在 QwenClient；失败在这里转 BizException（400）并写一条 t_error_log。
 * 分层：Controller → 本类编排 → QwenClient（唯一外部模型边界）。
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
     * 事务模板：给无事务的编排方法 generateAndSave 显式包住落库那几步。
     * saveGenerated 上虽有 @Transactional，但同类内部调用不走代理，注解不生效。
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 把 AI 返回并解析好的问卷结构落库（草稿，归属 userId）。
     * 事务只包住下面几条 INSERT（毫秒级）——这是本类存在的全部意义；userId 只认请求头，防止伪造归属。
     *
     * @return 新建问卷的 id
     * @throws BizException AI 没返回任何有效题目（前端拿到 400）
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

        // AI 返回的文本同样必须过长度校验：这条路不经过 SurveyService.validateSurveyText，
        //   模型多吐几个字就会撞 varchar 上限、INSERT 被拒。这里抛异常而非截断（截断会静默改内容）。
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

            // 宽容解析题型：模型常把类型写成字符串 "1"，强转会抛 ClassCastException，
            //   被外层转成 400 并整次回滚，而那次调用已经计过费 —— 认不出来就按"缺字段"跳过该题。
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
            // 同类校验：题干超长时同样会炸 500（t_question.title varchar(500)）
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
                    // 选项同理（t_option.content varchar(500)）
                    TextLimits.requireWithin(opt.getContent(), TextLimits.OPTION_CONTENT, "AI 生成的选项内容");
                    opt.setSortOrder(oOrder++);
                    optionMapper.insert(opt);
                }
            }
        }
        return s.getId();
    }

    /**
     * 编排一次完整的 AI 生成：调模型 → 解析 → 落库；失败写异常日志并转 BizException。
     * 不能加 @Transactional：内部有最长 120 秒的外部 HTTP 与重试等待，事务只包 saveGenerated 的 INSERT。
     * 请求上下文由 Controller 传参，不注入 HttpServletRequest：Service 与 Web 容器解耦，便于单测。
     *
     * @return 新建问卷的 id
     * @throws BizException 对用户可见的失败（主题校验、限流提示、AI 未返回题目、生成失败）
     */
    public int generateAndSave(Integer userId, String topic, String extra,
                               String requestUri, String requestMethod, String clientIp) {
        try {
            Map<String, Object> parsed = qwenClient.generateSurveyJson(topic, extra);
            // 同类内部调用不走代理：必须显式开事务，否则 saveGenerated 上的 @Transactional 不生效，
            //   中途失败会留下"有问卷没题目"的半成品。
            Integer newSurveyId = transactionTemplate.execute(status -> saveGenerated(userId, topic, parsed));
            return newSurveyId == null ? 0 : newSurveyId;
        } catch (BizException e) {
            // 业务拒绝（如"AI 未返回有效题目"）不写异常日志：项目约定业务失败抛 BizException、不污染异常日志。
            throw e;
        } catch (Exception e) {
            LOG.error("AI 生成失败：{}", e.getMessage(), e);
            // 真异常（网络 / 格式 / 限流）写进 t_error_log，异常日志页面才查得到。
            saveAiErrorLog(e, userId, requestUri, requestMethod, clientIp);

            if (e instanceof QwenClient.RateLimitedException) {
                // 限流是可预期的（免费额度排队），给明确等待提示而不是把堆栈甩给用户
                throw new BizException("AI 服务当前限流（免费额度排队中），请等 10~30 秒后重试");
            }
            // 对外只给固定文案：不把底层网络信息 / AI 原始返回透传给客户端，详情已进 t_error_log。
            throw new BizException("AI 生成失败，请稍后重试");
        }
    }

    /**
     * AI 生成失败时写入 t_error_log；不写的话「异常日志」页面查不到 AI 失败记录。
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
