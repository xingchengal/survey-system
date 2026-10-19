package com.survey.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.survey.common.QuestionType;
import com.survey.common.SurveyLifecycle;
import com.survey.common.SurveyStatus;
import com.survey.common.BizException;
import com.survey.common.DateRanges;
import com.survey.common.Grader;
import com.survey.common.PageParams;
import com.survey.common.SqlLikeEscaper;
import com.survey.common.PageResult;
import com.survey.common.Role;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.UserMapper;
import com.survey.service.NotificationService;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.OptionMapper;
import com.survey.mapper.QuestionMapper;
import com.survey.mapper.SurveyCollaboratorMapper;
import com.survey.mapper.SurveyIpMapper;
import com.survey.mapper.SurveyMapper;
import com.survey.mapper.SurveyReviewMapper;
import com.survey.mapper.SurveyVersionMapper;
import com.survey.mapper.WalletMapper;
import com.survey.common.TextLimits;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 问卷核心业务：增删改查、发布、复制、回收站、模板、分页、统计、定时任务。
 *
 * ── 谁在调我 ──────────────────────────────────────────
 *   SurveyController    → 问卷接口的绝大部分
 *   QuestionController  → 保存题目/查题目时先按 surveyId 查问卷做归属校验
 *   AnswerController    → 归属校验时按 surveyId 反查问卷
 *   UserService         → 删除用户时级联删掉其名下所有问卷
 *
 * ── 涉及的表 ──────────────────────────────────────────
 *   t_survey（主）、t_question、t_option、t_answer（统计答卷数时用）、
 *   t_answer_detail（级联删除时按答卷逐份清明细）
 *
 * ── 三条关键设计（改代码前务必理解）────────────────────
 *   1. 分享码：SecureRandom 生成 6 位 + 查重（见 generateShareCode），由服务端独占管理；
 *   2. 逻辑删除：问卷不是直接删，先移到回收站（deleted=1），permanentDelete() 才真删，
 *      而且**要求它已经在回收站里**（否则抛异常）—— 防止手滑彻底删掉正常问卷；
 *   3. 统计不走 N+1：countResponsesBySurveyIds() 用一条 GROUP BY 批量取答卷数。
 *
 * ── 状态码约定 ────────────────────────────────────────
 *   status      : 0=草稿   1=已发布   2=已结束
 *   deleted     : 0=正常   1=在回收站
 *   isTemplate  : 0=普通问卷  1=模板（模板的 userId 通常是 0，属于"公共资产"）
 */
@Service
public class SurveyService {

    /** 统一用 SLF4J 记录：System.out/err/printStackTrace 的输出在生产环境会直接丢失，出了故障拿不到任何线索。 */
    private static final Logger LOG = LoggerFactory.getLogger(SurveyService.class);

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
    private StatsCacheService statsCacheService;

    /**
     * H5 额度钱包：发布扣费 / 移入回收站退款。
     * ★ 只在 publish() 与 moveToRecycle() 两处调用：
     *   前者在同一事务里"先扣费、后改状态"，余额不足会连同状态一起回滚。
     */
    @Autowired
    private WalletService walletService;

    /**
     * 恢复上架申请的收件人来源（所有有效管理员）。
     * ★ 只用 findAdminIds()，不要在这里再写一条 `SELECT ... role='admin'` ——
     *   已注销管理员必须被排除，那个过滤条件只应有一份。
     */
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private NotificationService notificationService;

    /**
     * 模板分类清单：设为模板时把"现场输入的新分类"自动登记进清单。
     * ★ 不在这里做权限/长度判断 —— 统一走 TemplateCategoryService，两条入口同一套规则。
     */
    @Autowired
    private TemplateCategoryService templateCategoryService;

    /**
     * 彻底删除时要清理的"后加子表"。
     * ★ 这 4 张表是本项目后续几轮才出现的（版本快照/协作者/IP/举报），
     *   早期的 cascadeDeleteSurvey 只认识"题目/选项/答卷/明细"，于是彻底删除会留下孤儿行
     *   —— 其中 t_survey_version 里是**完整题目 JSON**，属于"删了但没删干净"。
     *   新增任何"从属于问卷"的表时，必须同步加到这里（并补 SurveyServiceCascadeDeleteTest 的断言）。
     */
    @Autowired
    private SurveyVersionMapper surveyVersionMapper;
    @Autowired
    private SurveyCollaboratorMapper surveyCollaboratorMapper;
    @Autowired
    private SurveyIpMapper surveyIpMapper;
    @Autowired
    private SurveyReviewMapper surveyReviewMapper;

    /** 管理端总览「额度总量」卡片（只读汇总） */
    @Autowired
    private WalletMapper walletMapper;

    // ===== 分享码生成 =====

    /** 分享码字符集（小写字母 + 数字） */
    private static final String SHARE_CODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    /** 分享码长度 */
    private static final int SHARE_CODE_LENGTH = 6;
    /** 撞码后最多重试几次 */
    private static final int SHARE_CODE_MAX_RETRY = 20;

    /**
     * ★ 用 SecureRandom 而不是 java.util.Random：
     *   Random 的序列是可预测的（知道种子就能推出来）。分享码相当于"问卷的口令"，
     *   一旦被猜到，别人不用登录就能打开未公开的问卷。SecureRandom 是密码学安全的随机源。
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成一个"数据库中尚未被占用"的分享码。
     *
     * 为什么要查重：36^6 ≈ 21.7 亿种组合，概率上很宽裕，但 t_survey.share_code 上有唯一索引 ——
     * 万一撞码，直接 insert/update 会抛数据库异常（用户看到 500 而不是友好提示）。所以先查再返回。
     *
     * ★ 调用方：publish()（用户点发布）、autoPublishAndClose()（每分钟的定时任务），
     *   以及 DemoDataService.init()（造演示数据）。
     *   ★ 它们必须复用这一套 "SecureRandom + 查重 + 重试"：另写一套 java.util.Random + 不查重的
     *     6 位码，一旦撞上 t_survey.share_code 的唯一索引，**整批 20 用户 30 问卷**会全部回滚。
     *
     * ★ 可见性说明：它保持 public 就是为了让造演示数据复用 —— 收回 private 等于逼出第二份实现，
     *   重新踩上面那个整批回滚的坑。
     *
     * @return 全新的、数据库里不存在的分享码
     * @throws IllegalStateException 连续重试都撞码（正常数据量下不可能发生，属于兜底）
     */
    public String generateShareCode() {
        for (int attempt = 0; attempt < SHARE_CODE_MAX_RETRY; attempt++) {
            StringBuilder sb = new StringBuilder(SHARE_CODE_LENGTH);
            for (int i = 0; i < SHARE_CODE_LENGTH; i++) {
                sb.append(SHARE_CODE_CHARS.charAt(secureRandom.nextInt(SHARE_CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (surveyMapper.findByShareCode(code) == null) {
                return code;   // 没被占用，直接用
            }
        }
        throw new IllegalStateException("分享码生成失败：连续 " + SHARE_CODE_MAX_RETRY
                + " 次都碰撞，请检查 t_survey 表的数据量");
    }

    /** 问卷标题长度上限（= t_survey.title 的列宽 varchar(200)） */
    private static final int MAX_TITLE_LENGTH = TextLimits.SURVEY_TITLE;
    /**
     * 问卷描述长度上限（= t_survey.description 的列宽 varchar(500)）。
     * ★ 必须与列宽一致：上限一旦大于列宽，超出部分的描述会通过下面的 validateSurveyText、
     *   再被 MySQL 拒绝，用户看到的是 500「系统繁忙」，而不是 400「问卷描述不能超过 500 个字符」。
     *   口径统一在 common/TextLimits。
     *   ★ 若业务上确实需要更长描述：先 ALTER 列宽，再改 TextLimits，两边必须一起动，
     *     否则又回到上面那个 500。
     */
    private static final int MAX_DESC_LENGTH = TextLimits.SURVEY_DESCRIPTION;

    /**
     * 问卷列表单次导出最多多少行。
     * ★ 与 AnswerController / UserService 的导出上限同一个思路：导出会把全部行读进内存，
     *   不设上限时一次"顺手点一下"就足以把堆打满。SQL 侧固定 `LIMIT 10001`，
     *   超过就明确拒绝，不静默截断。
     */
    public static final int SURVEY_EXPORT_MAX_ROWS = 10_000;

    /**
     * 问卷文本字段的长度上限校验。
     *
     * ★ 为什么必须校验长度：title / description 没有上限时，超长文本既能撑爆存储，
     *   又会让「全部问卷」列表（每行都带 title/description）返回巨大的响应体。
     *   （答卷内容的同类校验在 AnswerService.submit 里。）
     *
     * ★ 只做长度校验，**不擅自剥离 HTML 标签**：Vue 模板默认会对插值做转义，
     *   后端硬剥会把用户正常写的 "<" 也吃掉，属于以破坏数据的方式"防 XSS"。
     *
     * @throws BizException 超长时（400 + 明确文案，前端直接弹给用户）
     */
    private void validateSurveyText(Survey survey) {
        if (survey == null) return;
        if (survey.getTitle() != null && survey.getTitle().length() > MAX_TITLE_LENGTH) {
            throw new BizException("问卷标题不能超过 " + MAX_TITLE_LENGTH + " 个字符");
        }
        if (survey.getDescription() != null && survey.getDescription().length() > MAX_DESC_LENGTH) {
            throw new BizException("问卷描述不能超过 " + MAX_DESC_LENGTH + " 个字符");
        }
    }

    /**
     * 新建问卷。
     *
     * ★★ status / shareCode / isTemplate / deleted 必须全部**无条件**归零 ——
     *   它们与 userId 一样属于**服务端独占字段**，绝不能相信请求体。若让 status 与 shareCode
     *   原样落库，/add（**普通登录用户即可**，不需要管理员）就能造出这些自相矛盾的组合，
     *   每一种都会留下收不了尾的脏数据：
     *     · {"status":1}                   → "已发布却没有分享码"：统计、/page?status=1、看板
     *                                        都把它算作已发布，匿名入口却永远打不开它；
     *     · {"shareCode":"abc123"}         → **自选分享码**，绕过 publish() 的 SecureRandom+查重；
     *                                        更糟的是 /api/survey/share/{code} 在 JwtAuthFilter 白名单里，
     *                                        任何人免登录就能读到这份**草稿**的标题与描述；
     *     · {"status":1,"shareCode":"abc"} → 一份"已发布"且可被匿名读到、却完全没走 publish() 的问卷；
     *     · {"status":2}                   → 生下来就是"已结束"。
     *   ★ 收口在 Service（唯一的 INSERT 入口），以后新增创建途径也绕不过 ——
     *     这正是"别把不变量留给调用方记得"的做法。
     *   ★ 字段的合法取值只由两条专用路径产出：status 走 publish()/定时任务/回收站，
     *     shareCode 走 generateShareCode()（在 publish() 里调用）。
     */
    public Survey createSurvey(Survey survey) {
        validateSurveyText(survey);
        survey.setStatus(SurveyStatus.DRAFT.code());   // 新问卷永远是草稿
        survey.setShareCode(null);                     // 分享码只由 publish() 生成
        survey.setIsTemplate(0);                       // 要设模板请走管理员的 /set-template
        survey.setDeleted(0);
        if (survey.getViewCount() == null) survey.setViewCount(0);
        surveyMapper.insert(survey);
        return survey;
    }

    // ★ cascadeDeleteSurvey 上的 @Transactional 在**同类内部调用**时不生效（Spring AOP 基于代理），
    //   所以外层调用方必须自己标一次事务，否则会落到**半删状态**：子表删了、问卷还在（或反过来）。
    //   现有两个调用方都满足"事务边界成立"：
    //     · permanentDelete()：自己标了 @Transactional（同类内部调用，靠外层这次注解）
    //     · UserService.deleteUser()：**跨 Bean** 调用（注入的 surveyService 代理），事务正常生效
    //   以后再给 cascadeDeleteSurvey 加调用方时，先确认它属于上面哪一种，否则会回到"半删状态"。

    /**
     * 级联物理删除一份问卷及其全部子数据。
     *
     * ★ 删除顺序必须**自底向上**（先子后父），与 UserService.deleteUser 的既有顺序保持一致：
     *     答卷明细 → 答卷 → 选项 → 题目 → 版本/协作/IP/举报 → 通知（按 link 清）→ 问卷
     *   反过来删（先删问卷）会留下永远查不到、也永远删不掉的子表垃圾。
     * ★ 两处复用：permanentDelete（回收站彻底删）、UserService.deleteUser（删用户时连带删
     *   他名下的问卷）—— 集中在这里就避免了"两份实现、改一处漏一处"。
     *
     * @param surveyId 问卷 id
     * @return 删除的问卷行数（0 表示问卷本来就不存在；没有子数据时前几步影响行数为 0，属正常）
     */
    @Transactional
    public int cascadeDeleteSurvey(Integer surveyId) {
        if (surveyId == null) return 0;

        // 0) 先取出题目 id：题目行马上要被删掉，删完就再也查不到，
        //    而统计缓存是**逐题**建的（选项分布 / 填空文本各一个 key）。
        //    ★ 这里只按具体 key 删，不做前缀扫描清库 —— 前缀扫描会连限流计数一起清掉，
        //      见 common/RedisKeys 的命名空间约定。
        List<Integer> questionIds = questionMapper.findBySurveyId(surveyId).stream()
                .map(Question::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 1) 答卷明细：一条 JOIN DELETE 删完（AnswerDetailMapper.deleteBySurveyId）。
        //    ★ 必须用这条 JOIN DELETE：若改成先 findBySurveyId 把全部答卷读进内存、再逐份调
        //      deleteByAnswerId，一份 5000 份答卷的问卷就是 1 次查询 + 5000 条 DELETE，
        //      **全在同一个事务里持锁执行**，容易锁等待甚至超时。
        int detailRows = answerDetailMapper.deleteBySurveyId(surveyId);
        // 2) 答卷
        int answerRows = answerMapper.deleteBySurveyId(surveyId);
        // 3) 选项
        optionMapper.deleteBySurveyId(surveyId);
        // 4) 题目
        questionMapper.deleteBySurveyId(surveyId);
        // 5) 版本快照 / 协作者 / IP 记录 / 举报（都是"从属于问卷"的后加表）
        //    ★ 必须在删问卷本体**之前**：这些表都靠 survey_id 关联，
        //      问卷行一删就再也定位不到它们了（只能靠手工 SQL 才发现残留）。
        int versionRows = surveyVersionMapper.deleteBySurveyId(surveyId);
        int collaboratorRows = surveyCollaboratorMapper.deleteBySurveyId(surveyId);
        int ipRows = surveyIpMapper.deleteBySurveyId(surveyId);
        int reviewRows = surveyReviewMapper.deleteBySurveyId(surveyId);
        // 5.5) 站内通知：全站**只有**「收到新答卷」这类通知的 link 带问卷 id
        //      （`/my/surveys/{id}/statistics`）。问卷一删，那条通知点进去必然「问卷不存在」，
        //      而且它是**未读**的，会让接收人的红点永远消不掉（前端每 30 秒轮询未读数）。
        //      ⇒ 在这里随问卷一起清掉（C3「通知孤儿」）。
        //      ★ 与上面几张表的区别：通知**不是**"从属于问卷"的表，只有一部分通知指向问卷，
        //        所以清理条件是「link 精确指到这份问卷」，不是 deleteBySurveyId（那张表没有 survey_id 列）。
        int notificationRows = notificationService.deleteBySurveyLink(surveyId);
        // 6) 问卷本体
        int rows = surveyMapper.deleteById(surveyId);
        // 7) 统计缓存：问卷与题目都已不存在，这些 key 不会再被任何正常请求读到
        //    （统计接口会先因"问卷/题目不存在"而 400），删掉纯粹是不让残值白占内存到 TTL 到期。
        //    ★ 事务内删缓存是安全的：删是幂等的，且下次访问会回源得到正确值 ——
        //      与"自增"相反，后者绝不能在事务里做（回滚会造成缓存比库多）。
        statsCacheService.evictSurvey(surveyId, questionIds);
        LOG.info("级联删除问卷 id={}：清理答卷 {} 份 / 明细 {} 条 / 版本 {} 条 / 协作者 {} 条 / "
                        + "IP {} 条 / 举报 {} 条 / 站内通知 {} 条，以及其选项与题目，删除问卷 {} 行",
                surveyId, answerRows, detailRows, versionRows, collaboratorRows, ipRows, reviewRows,
                notificationRows, rows);
        return rows;
    }

    /**
     * 更新问卷。★ 同样做文本长度校验。
     *
     * ★ 关于"能改哪些字段"：
     *   SurveyMapper.update 是一条**静态 SQL**，SET 列表只有
     *   **title / description / start_time / end_time** 四个字段 ——
     *   status 与 share_code **不在**里面。若把 status 放回 SET，静态 SQL 会把客户端没传的
     *   字段写成 NULL：一次"只改标题"的局部提交就能把 status 写成 NULL（那份问卷从此谁也答不了），
     *   还得靠调用方手工回填旧值兜底。
     *   状态变更只走 publish() / updateStatusAndShareCode() / 回收站与定时任务的专用 SQL。
     * ★ 它仍是静态 SQL（不是动态更新）：没传的字段会写成 NULL —— 这是**有意保留**的语义，
     *   因为起止时间需要能被清空（前端把时间输入框清掉时传 null）。
     */
    public int updateSurvey(Survey survey) {
        validateSurveyText(survey);
        normalizeSurveySettings(survey);
        return surveyMapper.update(survey);
    }

    /**
     * 问卷设置的归一化 + 校验（答题密码 / 限量 / 感谢语 / 跳转链接 / 公开结果）。
     *
     * ★ 空串一律归 NULL：前端把输入框清空时传的是 ""，直接存库会变成"有密码但密码是空串"
     *   —— passwordRequired() 判的是 isBlank，虽然也能兜住，但库里留下空串会让
     *   "清空密码"这件事在数据层面看不出来。归 NULL 后语义干净：NULL = 关闭。
     * ★ 跳转链接只放行 http/https：`javascript:` 伪协议会被浏览器执行，
     *   而这段 URL 是作者填的、要展示给所有答题人，必须当成不可信输入。
     */
    private void normalizeSurveySettings(Survey survey) {
        String pwd = trimToNull(survey.getAccessPassword());
        if (pwd != null && pwd.length() > 20) {
            throw new BizException("答题密码不能超过 20 个字符");
        }
        survey.setAccessPassword(pwd);

        String thank = trimToNull(survey.getThankText());
        if (thank != null && thank.length() > 500) {
            throw new BizException("感谢语不能超过 500 个字符");
        }
        survey.setThankText(thank);

        String url = trimToNull(survey.getRedirectUrl());
        if (url != null) {
            if (url.length() > 500) {
                throw new BizException("跳转链接不能超过 500 个字符");
            }
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw new BizException("跳转链接必须以 http:// 或 https:// 开头");
            }
        }
        survey.setRedirectUrl(url);

        Integer max = survey.getMaxResponses();
        if (max != null && (max < 1 || max > 1_000_000)) {
            throw new BizException("限收份数必须在 1 ~ 1000000 之间（留空表示不限）");
        }

        survey.setPublicResult(survey.getPublicResult() != null && survey.getPublicResult() == 1 ? 1 : 0);

        // ★ 考试模式（判分）：gradeEnabled 只认 0/1；关闭时**顺手清掉及格线**，
        //   避免"开关关了、阈值还在"的幽灵配置（下次再开启时会用上一个旧阈值，用户完全不知道）。
        //   ★ 开启时必须先有计分题：总分由题目分值算出（与 Grader 同一口径），
        //     否则提交时会出现"0 / 0 分"的成绩卡。
        Integer gradeEnabled = survey.getGradeEnabled() != null && survey.getGradeEnabled() == 1 ? 1 : 0;
        survey.setGradeEnabled(gradeEnabled);
        if (gradeEnabled == 0) {
            survey.setPassScore(null);
        } else {
            int total = Grader.totalScore(
                    survey.getId() == null ? List.of() : questionMapper.findBySurveyId(survey.getId()));
            if (total <= 0) {
                throw new BizException("开启考试模式前，请先给题目设置分值");
            }
            Integer pass = survey.getPassScore();
            if (pass != null) {
                if (pass < 0 || pass > total) {
                    throw new BizException("及格分必须在 0 ~ " + total + " 之间（本卷总分 " + total + " 分）");
                }
            }
        }

        // ★ 答题限时：null / 0 = 不限；上限 24 小时（再长就不是"考试"了，而是配置写错）。
        //   ★ 服务端按前端上报的 duration_ms 校验（+10 秒宽限），**不是防作弊**：
        //     duration_ms 可伪造，这条挡的是"倒计时结束还在答"的正常超时。
        Integer timeLimit = survey.getTimeLimitSeconds();
        if (timeLimit != null && timeLimit <= 0) {
            timeLimit = null;   // 0 与 null 同义：不限时（前端清空输入框时可能传 0）
        }
        if (timeLimit != null && timeLimit > 86400) {
            throw new BizException("答题限时不能超过 24 小时（86400 秒）；留空表示不限时");
        }
        survey.setTimeLimitSeconds(timeLimit);

        // ★ 提交后显示答案：只有考试问卷才有意义 —— 关闭考试模式时强制归零，
        //   否则会出现"普通问卷却返回了一堆 null 答案"的歧义配置。
        int showAnswer = gradeEnabled == 1 && survey.getShowAnswerAfterSubmit() != null
                && survey.getShowAnswerAfterSubmit() == 1 ? 1 : 0;
        survey.setShowAnswerAfterSubmit(showAnswer);

        // ★ 是否仅限登录用户填写：只认 0/1，默认 0（老问卷与老客户端都不传 → 保持匿名，零影响）。
        survey.setRequireLogin(survey.getRequireLogin() != null && survey.getRequireLogin() == 1 ? 1 : 0);

        // ★ 签到核销 / 公开报名名单：默认都关闭（0），老问卷零影响。
        survey.setCheckinEnabled(survey.getCheckinEnabled() != null && survey.getCheckinEnabled() == 1 ? 1 : 0);
        survey.setShowParticipants(survey.getShowParticipants() != null && survey.getShowParticipants() == 1 ? 1 : 0);
    }

    /** 去空白；空串返回 null（"清空"在库里统一表示为 NULL） */
    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 查全部问卷（含模板）。
     *
     * ★ 为什么必须保留：GET /list-with-response-count（SurveyController.listWithResponseCount）
     *   的数据源就是它，删掉等于砍掉那个接口。
     *   ★ 别顺手删 mapper 的 findAll()：它同时还是 getSystemOverview() 的数据源。
     */
    public List<Survey> listAll() {
        return surveyMapper.findAll();
    }

    /** 查某个用户的问卷列表（「我的问卷」页）。 */
    /**
     * 我的问卷列表 —— 顺带回填每份问卷的**答卷数**（前端列表行显示"N 份答卷/回收率"）。
     * ★ 用一次 GROUP BY 批量取（countResponsesBySurveyIds），不是逐份 COUNT；
     *   responseCount 是 Survey 上的非数据库字段，Mapper 不映射它，只有这里回填。
     */
    public List<Survey> listByUserId(Integer userId) {
        List<Survey> mine = surveyMapper.findByUserId(userId);
        Map<Integer, Integer> counts = countResponsesBySurveyIds(mine);
        for (Survey survey : mine) {
            survey.setResponseCount(counts.getOrDefault(survey.getId(), 0));
        }
        return mine;
    }

    /** 按 id 查问卷。★ 是各处的"归属校验"基础：拿到 userId 再和登录用户比。 */
    public Survey getById(Integer id) {
        return surveyMapper.findById(id);
    }

    /** 按分享码查问卷（匿名访客打开分享链接走这里）。 */
    public Survey getByShareCode(String shareCode) {
        return surveyMapper.findByShareCode(shareCode);
    }

    /**
     * 校验答题密码（问卷设置里的 access_password）。
     *
     * ★ 没设密码直接通过（绝大多数问卷）。
     * ★ 设了就要求请求头 X-Survey-Password 的值完全一致；"没填"和"填错"用**同一句文案**，
     *   避免把"这份问卷有没有密码"变成可探测的信息。
     * ★ 放在 Service 而不是 Controller：加载题目、提交答卷是两个入口，
     *   判定口径只能有一份（同 createSurvey/updateSurvey 的收口思路）。
     */
    public void requireAccessPassword(Survey survey, String submitted) {
        if (survey == null || !survey.passwordRequired()) {
            return;
        }
        if (submitted == null || !survey.getAccessPassword().equals(submitted.trim())) {
            throw new BizException("该问卷需要答题密码（或密码不正确）");
        }
    }

    /**
     * **编辑器专用**：作者本人 / 管理员读**自己**的问卷题目时不看答题密码。
     *
     * ★★ 为什么需要这条豁免（2026-10-18 实测事故）：
     *   答题密码是**给填写人**的准入口令，不是作者对自己数据的授权。
     *   若连作者也拦，作者一在「设置」里加上密码，编辑器的
     *   {@code GET /api/question/list/{id}} 就会 400 —— 编辑器拿不到题目 →
     *   显示"还没有题目"；此时点一下「保存」（全量覆盖语义）会把题目**全部删掉**
     *   （实测留下了"删除 7 题"的历史版本）。
     *
     * ★★ 但豁免**只能限定在编辑器这条路径**（由 QuestionController 依据
     *   {@code X-Survey-Editor} 请求头判定），不能按"是不是作者"一刀切 ——
     *   一刀切的后果是：填写页判断"密码对不对"的方式就是"能不能读到题目"，
     *   作者随便输个错口令也能过门（因为后端根本不看口令），
     *   到了提交答卷（匿名入口，没有身份）才 400。用户实测到的
     *   "发布后密码失效、谁都能进，提交却要密码"正是这个原因。
     *
     * ★ 判定顺序：先放行管理员与作者，再比口令 —— 与
     *   {@link OwnershipGuard#requireVisible} 的"管理员 → 本人 → 已发布"口径一致。
     *
     * @param userId 当前登录用户 id（匿名/未登录为 null）
     * @param role   当前登录角色（来自 JwtAuthFilter 注入的 X-User-Role）
     */
    public void requireAccessPasswordForEditor(Survey survey, Integer userId, String role, String submitted) {
        if (survey == null || !survey.passwordRequired()) {
            return;
        }
        if (Role.isStaff(role)) {
            return;   // 管理端人员：已在可见性/权限点闸门放行，答题密码不再重复拦
        }
        Integer ownerId = survey.getUserId();
        if (ownerId != null && ownerId.equals(userId)) {
            return;   // 作者本人：这是他自己的问卷
        }
        requireAccessPassword(survey, submitted);
    }

    /**
     * 取消下架（恢复上架）。
     * ★ 只允许 SUSPENDED → PUBLISHED：下架是举报审核的结果，撤销也该走同一个入口，
     *   而不是让管理员随便把任意问卷改成 PUBLISHED（那会把草稿"发布"出去）。
     */
    public void restoreFromSuspend(Integer surveyId) {
        Survey survey = surveyMapper.findById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (!SurveyLifecycle.SUSPENDED.code().equals(survey.getLifecycle())) {
            throw new BizException("该问卷当前不是下架状态");
        }
        surveyMapper.updateLifecycle(surveyId, SurveyLifecycle.PUBLISHED.code());
    }

    /**
     * 作者申请恢复上架（H1 闭环的另一半：下架之后作者要有可点的出路）。
     *
     * ★ 为什么必须有这条：举报通过后问卷 lifecycle=SUSPENDED，而下面的
     *   {@link #restoreFromSuspend} 是**管理员专属**入口 —— 作者在「我的问卷」里
     *   除了看着它不能作答之外没有任何操作，只能线下找人。这条把"申请"落成一条
     *   给**全部有效管理员**的站内通知，管理员点通知就去审核台处理。
     *
     * ★ 三个判断都在这里、而不是 Controller：
     *   ① 问卷存在；② 确实处于 SUSPENDED（没被下架就没什么可申请的）；
     *   ③ 只有作者本人能替自己申请（管理员也走这条，不能替别人申请 ——
     *      管理员有 restoreFromSuspend，不需要"申请"）。
     *   ★ 归属判断用 equals 而不是 `==`：userId 是 Integer，超过 127 时 `==` 比的是引用，
     *     会随机把作者自己判成"不是你的问卷"。
     *
     * ★ 通知走 NotificationService.send（异步、内部自己兜异常），
     *   所以这里不会因为某个管理员的通知写失败而让整次申请 500。
     *
     * @param surveyId 问卷 id
     * @param userId   当前登录用户（作者）id
     */
    public void requestRestore(Integer surveyId, Integer userId) {
        Survey survey = surveyMapper.findById(surveyId);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (!SurveyLifecycle.SUSPENDED.code().equals(survey.getLifecycle())) {
            throw new BizException("该问卷没有被下架，无需申请");
        }
        if (survey.getUserId() == null || !survey.getUserId().equals(userId)) {
            throw new BizException("只能为自己的问卷申请恢复");
        }
        // 一次查出全部管理员，再逐条发通知（人数是个位数，不需要并发）
        // ★ link 带 ?status=APPROVED：申请恢复对应的那条举报早已是「已通过」，
        //   不带筛选的话管理员点通知会落在「待处理」空列表上，找不到这份问卷。
        //   审核页 ReviewManage.vue 的 mounted 会读这个 query 作为初始筛选。
        for (Integer adminId : userMapper.findAdminIds()) {
            notificationService.send(adminId, "恢复上架申请",
                    "作者申请恢复《" + survey.getTitle() + "》，请到举报审核页处理",
                    "review", "/admin/reviews?status=APPROVED");
        }
    }

    /**
     * 把「最后更新时间」推到当前时刻。
     *
     * ★ 为什么需要它：{@code t_survey.updated_time} 是
     *   {@code ON UPDATE CURRENT_TIMESTAMP}，只有真的改动了 t_survey 那一行才会走。
     *   而"保存题目" / "版本回滚"改的是 t_question / t_option，t_survey 一行都没动 ——
     *   于是编辑器的"已保存 · X 分钟前"一直显示很久以前的时间（用户实测报障）。
     *   这两个入口在成功后显式调一次即可。
     */
    public void touchUpdatedTime(Integer surveyId) {
        surveyMapper.touchUpdatedTime(surveyId);
    }

    /**
     * 发布问卷：把状态置为「已发布(1)」，没有分享码就生成一个。
     *
     * ★ 空判断用 isBlank() 而不是 isEmpty()：
     *   isEmpty() 只认长度为 0 的字符串，像 "   "（纯空格）这种脏数据会被当成「已有分享码」，
     *   结果问卷发布了、却拿着一个打不开的空白分享码。isBlank() 会把纯空白也算空，重新生成。
     *   （isBlank 是 Java 11+ 的 API，本项目用 Java 17）
     *
     * ★ 关联：分享码生成逻辑见 generateShareCode()；调用方是 SurveyController.publish()
     *   以及下面的定时任务 autoPublishAndClose()。
     *
     * @param surveyId 问卷 id
     * @return 发布后的问卷（含分享码）；★ 问卷不存在时**抛 BizException**（不是返回 null）
     *
     * ★ H5：发布要扣 1 点额度（幂等键 publish:{surveyId}，一份问卷只扣一次）。
     *   扣费与改状态在**同一事务**里：余额不足时状态不会变成"已发布"，
     *   前端拿到的是一句"额度不足…请到我的钱包充值"，而不是"发布成功了一半"。
     *   ★ 重新发布（已结束再开）不会重复扣：幂等键已存在，charge 直接返回原流水。
     */
    @Transactional
    public Survey publish(Integer surveyId) {
        Survey survey = surveyMapper.findById(surveyId);
        // ★ 目标不存在时**抛异常**而不是 return null：返回 null 会让调用方拿到一个"看起来正常"的
        //   空值，新增调用方极易 NPE；而且与 permanentDelete() 等方法的契约不一致。
        //   Controller 先查一遍只是兜底，这里才是契约。
        if (survey == null) throw new BizException("问卷不存在");

        // 先取到局部变量，避免重复调用 getter（也避免两次调用之间值变化导致判断不一致）
        // ★ 状态机守卫：发布前先挡掉两种非法来源，否则 status 会与真实可答性脱节。
        //   ① 回收站里的问卷不能被发布 —— findById **不过滤 deleted**，缺了这道判断，
        //      一份已删（deleted=1）的问卷就能被"发布"成 status=1：回收站页面把它显示成"已发布"，
        //      作者再点一次"恢复"就等于白得一次发布（绕过了正常的发布流程）。
        //   ② 已结束（status=2）的问卷不能被直接拉回已发布 —— 它的 endTime 早已过去，
        //      下一分钟的定时任务会立刻又把它关闭，于是"已发布 / 已结束"在看板上反复跳。
        //      （含事实订正：能否收答卷由 AnswerService.submit 就地比 end_time 判定，status 只作展示
        //       —— 定时任务置 2；所以这个 1 分钟窗口并不会多收答卷，乱的是看板。）
        //      （真要重新开放，正确做法是把 endTime 改到未来。）
        if (survey.getDeleted() != null && survey.getDeleted() != 0) {
            throw new BizException("问卷已在回收站中，请先恢复再发布");
        }
        if (SurveyStatus.isEnded(survey.getStatus())) {
            // ★★ 必须让 2→1 这条路径与上面那句文案自洽，否则问卷会**永久停在"已结束"**：
            //   status 已从 SurveyMapper.update 的 SET 里拿掉，"延长结束时间"改的只是 end_time，
            //   状态仍然是 2 —— 若这里直接抛 400，看板永远算 ended，界面上没有任何操作能救回来，
            //   而错误提示还在指引用户去做一件做不到的事。
            //   所以：**只要截止时间确实被延长到了未来（或清空），就允许重新发布**。
            //   （含事实订正：真正决定能否收答卷的是 AnswerService.submit 就地比 end_time，
            //    status 只作展示、由定时任务置 2。）
            boolean endExtended = survey.getEndTime() == null
                    || survey.getEndTime().isAfter(java.time.LocalDateTime.now());
            if (!endExtended) {
                throw new BizException("问卷已结束，如需重新开放请先延长结束时间");
            }
        }
        String existingCode = survey.getShareCode();
        if (existingCode == null || existingCode.isBlank()) {
            survey.setShareCode(generateShareCode());
        }
        // ★ 只改 status + share_code —— update() 的 SQL 已不含这两个字段
        //   （把"谁能改状态"收敛到这一个专用方法，见 SurveyMapper 的注释）
        // ★ H5：先扣费再改状态。同一事务内，扣费失败（余额不足）会整体回滚，
        //   不会出现"扣了钱没发布"或"发布了没扣钱"。
        walletService.chargeForPublish(survey.getId(), survey.getUserId());
        survey.setStatus(SurveyStatus.PUBLISHED.code());
        surveyMapper.updateStatusAndShareCode(survey.getId(), SurveyStatus.PUBLISHED.code(), survey.getShareCode());
        return survey;
    }

    // ===== 回收站 =====

    /**
     * 移入回收站（逻辑删除，deleted=1）。
     * ★ 同时记下 deletedBy / deletedByName，回收站页面才能显示"谁删的"。
     *
     * ★ H5：删除给作者退 1 点（只有"确实扣过发布费"的问卷才退；幂等键 refund:{surveyId}）。
     *   恢复回收站后再删除不会二次退款 —— 一次收费终身有效。
     *   退款与删除同事务：删除成功才退，退失败则删除回滚（不会出现"退了钱没删"）。
     */
    @Transactional
    public int moveToRecycle(Integer id, Integer userId) {
        Survey survey = surveyMapper.findById(id);
        int rows = surveyMapper.moveToRecycle(id, userId);
        if (rows > 0 && survey != null) {
            walletService.refundForSurvey(id, survey.getUserId());
        }
        return rows;
    }

    /** 从回收站恢复（deleted=0，并清空 deletedBy 信息）。 */
    public int restoreFromRecycle(Integer id) {
        return surveyMapper.restoreFromRecycle(id);
    }

    /**
     * 彻底删除（物理删除）。
     * ★ 前置条件：必须已经在回收站里（deleted=1），否则抛异常。
     *   这是有意的保护 —— 避免一个 DELETE 请求就把正常问卷彻底删掉。
     * ★ 必须走 cascadeDeleteSurvey：子表数据（题目/选项/答卷/明细）要一并清理，
     *   否则会留下永远查不到、也永远删不掉的子表垃圾。
     * ★ 这里必须在外层自己标 @Transactional：同类内部调用时 cascadeDeleteSurvey
     *   的事务注解不生效 → 半删状态。
     *   ★ 本方法**先查了一次问卷**（findById）再删：没有事务的话，这中间的时间窗里
     *     状态可能被别的请求改掉（检查与执行不在一个事务里）。
     */
    @Transactional
    public int permanentDelete(Integer id) {
        Survey survey = surveyMapper.findById(id);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (survey.getDeleted() == null || survey.getDeleted() == 0) {
            throw new BizException("只能彻底删除回收站中的问卷，请先将其移入回收站");
        }
        return cascadeDeleteSurvey(id);
    }

    // ★ 回收站查询只保留分页版本（pageRecycleByUser / pageRecycleAll，keyword 传 null 即等价于不分页）：
    //   再加一份不分页实现就等于多一份回收站查询口径，改一处漏一处。

    // ===== 访问次数 =====

    /** 访客打开分享链接时 +1（匿名答题页调用）。 */
    public int incrementViewCount(String shareCode) {
        return surveyMapper.incrementViewCount(shareCode);
    }

    /** 全站访客次数合计（系统总览卡片用）。 */
    public int sumViewCount() {
        return surveyMapper.sumViewCount();
    }

    // ===== 复制问卷 =====

    /**
     * 复制一份问卷（含全部题目与选项），副本归属为 userId。
     *
     * ★ 事务：问卷、题目、选项分多张表插入，中途失败必须整体回滚，
     *   否则会留下"有问卷没题目"的半成品。
     *
     * ★ 副本的初始状态：status=0（草稿）、shareCode=null（不继承原分享码，
     *   发布时才生成新的）、deleted=0、isTemplate=0、viewCount=0，标题加"-副本"后缀。
     *
     * ★ 它是「从模板创建」的底层实现（createFromTemplate 直接调它）。
     *
     * @param sourceId 被复制的问卷/模板 id
     * @param userId   副本归属的用户
     * @return 新建的问卷（含 id）；★ 源问卷不存在时**抛 BizException**（不是返回 null）
     */
    @Transactional
    public Survey copySurvey(Integer sourceId, Integer userId) {
        Survey old = surveyMapper.findById(sourceId);
        // ★ 统一抛异常，不返回 null
        if (old == null) throw new BizException("问卷不存在");

        Survey newSurvey = new Survey();
        newSurvey.setUserId(userId);
        newSurvey.setTitle(buildCopyTitle(old.getTitle()));
        newSurvey.setDescription(old.getDescription());
        newSurvey.setStatus(SurveyStatus.DRAFT.code());
        newSurvey.setShareCode(null);
        newSurvey.setDeleted(0);
        newSurvey.setIsTemplate(0);
        newSurvey.setViewCount(0);
        surveyMapper.insert(newSurvey);

        List<Question> questions = questionMapper.findBySurveyId(sourceId);
        // ★★ 跳转目标的**跨问卷映射** —— 这一步不能省，是最容易漏的地方。
        //   next_question_id 存的是**真 question.id**（不是序号），而副本里的题目是**新插入、id 全新**的 ——
        //   把源 id 直接拷过去会指向源问卷的题目（或根本不存在的 id），答题页表现为"跳转失效"。
        //   所以先建 `源题目id → 副本题目id` 的映射表，拷选项时按它翻译。
        Map<Integer, Integer> questionIdMap = new HashMap<>();
        for (Question q : questions) {
            Question newQ = new Question();
            newQ.setSurveyId(newSurvey.getId());
            newQ.setType(q.getType());
            newQ.setTitle(q.getTitle());
            newQ.setRequired(q.getRequired());
            newQ.setSortOrder(q.getSortOrder());
            // ★ 矩阵题的行列标题必须一起复制：漏了的话副本会变成"没有行列的矩阵题"，
            //   答题页渲染不出来、统计页也没有表头（而且保存时才报错，用户已以为复制成功）。
            newQ.setMatrixRows(q.getMatrixRows());
            newQ.setMatrixCols(q.getMatrixCols());
            questionMapper.insert(newQ);
            questionIdMap.put(q.getId(), newQ.getId());
        }

        // ★ 选项必须在**所有题目都插完**之后才拷：否则"第 3 题跳到第 5 题"这类**前向跳转**
        //   会因为第 5 题的副本 id 还没生成而映射不到（顺序敏感是这里唯一容易写错的地方）。
        for (Question q : questions) {
            if (!QuestionType.hasOptions(q.getType())) continue;
            Integer newQuestionId = questionIdMap.get(q.getId());
            List<Option> options = optionMapper.findByQuestionId(q.getId());
            for (Option opt : options) {
                Option newOpt = new Option();
                newOpt.setQuestionId(newQuestionId);
                newOpt.setContent(opt.getContent());
                newOpt.setSortOrder(opt.getSortOrder());
                // ★ 必须拷 nextQuestionId 并按映射表翻译目标 id：漏拷会让复制带条件跳转的问卷
                //   静默丢掉全部跳转规则。
                newOpt.setNextQuestionId(remapNextQuestionId(opt.getNextQuestionId(), questionIdMap));
                optionMapper.insert(newOpt);
            }
        }
        return newSurvey;
    }

    /**
     * 把"源问卷里的跳转目标 id"翻译成"副本里的题目 id"。
     *
     * @param target       源选项的 nextQuestionId（null=顺序、-1=结束、其它=源题目 id）
     * @param questionIdMap 源题目 id → 副本题目 id
     * @return 副本里的目标 id；-1/null 原样返回；**映射不到时返回 null（退化成"按顺序"）**
     *
     * ★ 为什么映射不到要返回 null 而不是抛异常：`copySurvey` 的题目集合来自源问卷的**当前**题目，
     *   而 `next_question_id` 是历史值 —— 库里理论上可能存在"指向已删题目"的脏数据
     *   （正常路径已被 saveQuestions 的新校验挡住，但历史数据不受它约束）。
     *   那种情况下"复制失败"体验更差（用户只是想复制一份问卷），
     *   而"这条规则的退化成顺序"是**可见且可修**的（编辑器里会显示成未设置）。
     */
    private static Integer remapNextQuestionId(Integer target, Map<Integer, Integer> questionIdMap) {
        if (target == null || target == -1) return target;
        return questionIdMap.get(target);
    }

    /**
     * 生成副本标题，并保证**不超过 {@link #MAX_TITLE_LENGTH}**。
     *
     * ★ 必须在这里就截断：copySurvey 是直接 surveyMapper.insert，**不走 validateSurveyText** ——
     *   源标题长度已到 199/200 时，副本会变成 202/203 字符，校验拦不住，
     *   最终由 MySQL 列宽（varchar(200)）报错 → 500。
     *   截断规则：`原标题前 197 字 + "-副本"`，正好 200。
     *
     * @param sourceTitle 源问卷标题（可为 null，历史脏数据里有）
     * @return 副本标题，长度 ≤ 200
     */
    private String buildCopyTitle(String sourceTitle) {
        String base = sourceTitle == null ? "" : sourceTitle;
        String suffix = "-副本";
        int max = MAX_TITLE_LENGTH;
        if (base.length() + suffix.length() <= max) {
            return base + suffix;
        }
        return base.substring(0, max - suffix.length()) + suffix;
    }

    // ===== 模板 =====

    /** 模板广场列表（可按标题/描述关键字搜索）。 */
    public List<Survey> listTemplates(String keyword) {
        // ★ 必须转义 LIKE 通配符。
        //   否则用户在搜索框里输入一个 % 就等于"匹配所有行"（筛选被静默绕过），
        //   输入 _ 会被当成"任意一个字符"；见 common/SqlLikeEscaper 的类注释。
        return surveyMapper.findTemplates(SqlLikeEscaper.escape(keyword));
    }

    /**
     * 从模板创建一份新问卷 —— 本质就是复制模板。
     * ★ 模板的 userId 一般是 0，复制后归属会改成传入的 userId（在 copySurvey 里设置）。
     *
     * ★ 为什么下面这段校验不能去掉：
     *   本接口**只要登录就能调**，而 copySurvey 对 sourceId 只做存在性判断、不校验归属 ——
     *   少了它，任何登录用户传一个别人的 surveyId（哪怕是未发布草稿），就能把它的全部题目与选项
     *   复制到自己名下，再通过自己那份问卷的题目查询接口读出来 —— 一条完整的横向越权泄露链路。
     *   （对比 /copy/{id}：那条路径在 Controller 里是有归属校验的。）
     *
     * ★ 校验放在 Service 而不是 Controller：这样"从模板创建"的契约是自洽的，
     *   将来若有别的调用方（定时任务、批量导入）也绕不过这个校验。
     *
     * 允许复制的两种来源（与 getOne 的可见性口径一致）：
     *   ① is_template = 1  —— 模板广场里的模板，本来就是给所有人用的；
     *   ② status = 1       —— 已发布问卷属于公开内容，复制它等价于"另存为"。
     */
    @Transactional
    public Survey createFromTemplate(Integer templateId, Integer userId) {
        Survey source = surveyMapper.findById(templateId);
        if (source == null) {
            throw new BizException("模板不存在");
        }
        // 回收站里的问卷不能被复制（与 findByShareCode 的口径一致）
        if (source.getDeleted() != null && source.getDeleted() != 0) {
            throw new BizException("模板不存在");
        }
        boolean isTemplate = source.getIsTemplate() != null && source.getIsTemplate() == 1;
        if (!isTemplate && !SurveyStatus.isPublished(source.getStatus())) {
            throw new BizException("该问卷不是模板，也未发布，无法复制");
        }
        Survey copy = copySurvey(templateId, userId);
        // ★ 热度统计：只有"真的用了模板"才 +1；复制一份已发布问卷（isTemplate=false）不算模板使用。
        //   与 copySurvey 在同一个事务里：复制失败回滚时计数也不会动。
        if (isTemplate) {
            surveyMapper.incrementTemplateUseCount(templateId);
        }
        return copy;
    }

    /**
     * 设为模板 / 取消模板（管理员，可顺带改模板分类）。
     *
     * ★ 两个守卫都不能省：
     *   ① **回收站里的问卷不能设为模板** —— 否则库里会出现
     *      is_template=1 且 deleted=1 的记录：它既不在模板广场（要求 deleted=0）、
     *      也不在管理员问卷列表（要求 is_template 为空或 0），只在回收站里；
     *      而作者一旦点"恢复"，它就**自动变成公共模板**了 —— 中间没有任何"确认设为模板"的动作。
     *   ② 先确认问卷存在：直接打 SQL 时问卷 id 不存在只影响 0 行，
     *      Controller 报"操作失败"，看不出其实是"不存在"。
     *
     * ★ 仍然**有意允许**把"未发布的草稿"设为模板（管理员可能想先备好模板内容）——
     *   但调用方要清楚这等于**公开它**：模板广场对全部登录用户可见，
     *   /from-template/{id} 也能被任何登录用户克隆。
     * ★ 事务：设为模板现在会"登记分类 + 更新问卷"两次写，必须一起成功/失败。
     */
    @Transactional
    public int setTemplate(Integer id, Integer isTemplate, String category) {
        Survey survey = surveyMapper.findById(id);
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (survey.getDeleted() != null && survey.getDeleted() != 0) {
            throw new BizException("问卷已在回收站中，请先恢复再设为模板");
        }
        // ★ 空白分类必须统一归一成 NULL：前端"取消模板"时若把 category 传成空串，
        //   静态 SQL 会把空串原样写进 template_category；而前端展示用 `templateCategory || '通用'`，
        //   空串恰好也是 falsy，所以**表面看不出问题** ——
        //   但库里会同时存在 NULL 与 '' 两种"没有分类"，以后按分类统计/筛选就会分裂成两组。
        //   归一之后再落库，口径只有一种。
        String normalizedCategory = (category == null || category.isBlank()) ? null : category.trim();
        // ★ 取消模板时**无条件清空分类**：即使调用方误传了 category，也不能留下
        //   "不是模板、却还挂着某个分类"的脏数据（模板广场不查它，但按分类统计会多出一组）。
        if (isTemplate != null && isTemplate == 0) {
            normalizedCategory = null;
        }
        // ★ 设为模板必须有分类：模板广场按分类聚合，NULL 分类会显示成"通用/其他"，
        //   与"作者压根没分类"混在一起，之后按分类统计就分裂了。
        //   （历史数据里已有的 NULL 分类模板不受影响；这条只挡新的"设为模板"动作。）
        if (isTemplate != null && isTemplate == 1 && normalizedCategory == null) {
            throw new BizException("设为模板时必须选择模板分类");
        }
        // ★ 设为模板时把分类自动登记进 t_template_category：
        //   前端两个入口都允许现场输入新分类（allow-create），不登记的话"模板管理"的分类清单
        //   会永远缺这个新分类，下次给别的模板设分类时又得重新手输。
        //   ★ 只在 isTemplate=1 时登记：取消模板时分类会被清空，不该往清单里写东西。
        if (isTemplate != null && isTemplate == 1) {
            templateCategoryService.ensure(normalizedCategory);
        }
        return surveyMapper.setTemplate(id, isTemplate, normalizedCategory);
    }

    // ===== 分页 =====

    /**
     * 分页查全部问卷（管理员问卷列表页，支持关键字 + 状态 + **创建时间范围**筛选）。
     * ★ PageHelper 的用法：startPage 只对**紧随其后的那一条**查询生效，
     *   所以 startPage 和 mapper 调用必须紧挨着，中间不要插别的查询。
     * ★ 时间范围由 {@link com.survey.common.DateRanges} 解析（格式错 → 400 而不是 500）：
     *   `startDate`/`endDate` 是 `yyyy-MM-dd`，闭区间，结束取当天 23:59:59。
     */
    public PageResult<Survey> pageAllSurveys(int pageNum, int pageSize, String keyword,
                                             Integer status, String startDate, String endDate) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // 同上：转义 LIKE 通配符（见 listTemplates()）。
        keyword = SqlLikeEscaper.escape(keyword);
        List<Survey> list = surveyMapper.findPageByCondition(keyword, status,
                DateRanges.startOfDay(startDate), DateRanges.endOfDay(endDate));
        PageInfo<Survey> pageInfo = new PageInfo<>(list);
        // ★ 必须回显**钳制后**的页码/页大小：分页保护 PageParams 只在 startPage 那一层生效，
        //   若把调用方传进来的原值写进响应，请求 pageSize=100000 时实际只返回 100 条、
        //   响应体却回显 100000 —— 分页组件据此算总页数/显示"每页条数"都会错；
        //   pageSize=0 或负数时同理。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /**
     * 导出用的问卷列表（**不分页**，最多 10000 份）。
     *
     * ★ 筛选条件与 {@link #pageAllSurveys} 完全一致，所以"导出的就是屏幕上筛出来的"。
     * ★ 超过 10000 份明确报错拒绝，不静默截断（静默截断会让管理员以为"导出全了"）。
     *   mapper 侧固定 `LIMIT 10001`，多取的一条只为判断是否超限。
     * ★ 本方法**不返回**答卷数：控制器若要展示，用
     *   {@link #countResponsesBySurveyIds(java.util.List)} 一次 GROUP BY 补齐，别在循环里逐份 count。
     */
    public List<Survey> exportSurveys(String keyword, Integer status, String startDate, String endDate) {
        keyword = SqlLikeEscaper.escape(keyword);
        List<Survey> surveys = surveyMapper.findForExport(keyword, status,
                DateRanges.startOfDay(startDate), DateRanges.endOfDay(endDate));
        if (surveys.size() > SURVEY_EXPORT_MAX_ROWS) {
            throw new BizException("导出结果超过 " + SURVEY_EXPORT_MAX_ROWS
                    + " 份问卷，请缩小筛选范围后再导出（导出的每一行都要进内存，不设上限会拖垮服务）");
        }
        return surveys;
    }

    /** 分页查某用户的回收站（Recycle.vue 普通用户视角）。 */
    public PageResult<Survey> pageRecycleByUser(Integer userId, int pageNum, int pageSize, String keyword) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // 同上：转义 LIKE 通配符（见 listTemplates()）。
        keyword = SqlLikeEscaper.escape(keyword);
        List<Survey> list = surveyMapper.findRecyclePageByUser(userId, keyword);
        PageInfo<Survey> pageInfo = new PageInfo<>(list);
        // 同上：回显钳制后的页码/页大小（见 pageAllSurveys）。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /** 分页查全部回收站（Recycle.vue 管理员视角）。 */
    public PageResult<Survey> pageRecycleAll(int pageNum, int pageSize, String keyword) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // 同上：转义 LIKE 通配符（见 listTemplates()）。
        keyword = SqlLikeEscaper.escape(keyword);
        List<Survey> list = surveyMapper.findRecyclePageAll(keyword);
        PageInfo<Survey> pageInfo = new PageInfo<>(list);
        // 同上：回显钳制后的页码/页大小（见 pageAllSurveys）。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /**
     * ★ 批量统计「一批问卷」各自的答卷数，返回 surveyId -> 答卷数 的映射。
     *
     * 为什么要有这个方法：getUserStatistics() 与 getSystemOverview() 若在 Java 里这样写：
     *      for (Survey s : surveys) responses += answerMapper.countBySurveyId(s.getId());
     * 200 份问卷 = 200+ 条 SQL（典型的 N+1）。改成一次 GROUP BY 之后，SQL 条数恒为 1。
     * ★ 含事实订正：SurveyController.statistics() 端点**已删除**，countByStatus 只服务
     *   getUserStatistics / getSystemOverview，这里也不再有第三个调用方。
     *
     * ★ 关联：mapper 侧是 AnswerMapper.countBySurveyIds()。
     *
     * @param surveys 问卷列表（可为 null / 空，此时直接返回空 Map）
     * @return key = 问卷 id，value = 答卷数。没有答卷的问卷不会出现在 Map 里，
     *         所以取值必须用 getOrDefault(id, 0)
     */
    public Map<Integer, Integer> countResponsesBySurveyIds(List<Survey> surveys) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (surveys == null || surveys.isEmpty()) {
            return counts;
        }
        List<Integer> ids = surveys.stream().map(Survey::getId).collect(Collectors.toList());
        for (Map<String, Object> row : answerMapper.countBySurveyIds(ids)) {
            Object sid = row.get("sid");
            Object cnt = row.get("cnt");
            if (sid instanceof Number && cnt instanceof Number) {
                counts.put(((Number) sid).intValue(), ((Number) cnt).intValue());
            }
        }
        return counts;
    }

    // ===== 统计 =====

    /**
     * 状态分布计数（total / published / draft / ended）。
     * ★ 含事实订正：SurveyController.statistics() 端点**已删除**，countByStatus 只服务
     *   getUserStatistics / getSystemOverview —— 全项目只有这一份计数实现。
     */
    public record StatusCounts(long total, long published, long draft, long ended) {
    }

    /**
     * 统计一批问卷的状态分布。
     *
     * ★ 为什么抽成一个方法：getUserStatistics() 与 getSystemOverview() 若各写一遍
     *   **完全相同**的三个 stream 过滤，以后改口径（比如新增"已暂停"状态）就得改两处，早晚漏一处。
     *   现在两边都只是「拿一批问卷 → 取计数」，口径只在这里定义。
     *
     * ★ 状态口径（与前端筛选下拉一致）：
     *     published = status 1；draft = status 为 null 或 0；ended = status 2
     */
    public StatusCounts countByStatus(List<Survey> surveys) {
        long published = surveys.stream()
                .filter(s -> SurveyStatus.isPublished(s.getStatus())).count();
        long draft = surveys.stream()
                .filter(s -> SurveyStatus.isDraft(s.getStatus())).count();
        long ended = surveys.stream()
                .filter(s -> SurveyStatus.isEnded(s.getStatus())).count();
        return new StatusCounts(surveys.size(), published, draft, ended);
    }

    /**
     * 个人统计（MyDashboard.vue）：我的问卷数、已发布/草稿/已结束、收到的答卷总数、最近 5 份，
     * 以及近 7 天每天的答卷数（trendDates / trendCounts / recent7Answers）。
     *
     * ★ 会先过滤掉模板 —— 因为模板也可能挂在某个 userId 名下，但它不是"我的问卷"。
     * ★ 答卷数走批量查询 countResponsesBySurveyIds()，不是逐份 for 循环。
     * ★ 趋势是**第二个聚合查询**（AnswerMapper.countDailyByUserId），SQL 条数恒为 2 ——
     *   不再按问卷逐份计数。
     */
    public Map<String, Object> getUserStatistics(Integer userId) {
        List<Survey> surveys = surveyMapper.findByUserId(userId);
        surveys = surveys.stream()
                .filter(s -> s.getIsTemplate() == null || s.getIsTemplate() == 0)
                .collect(Collectors.toList());

        StatusCounts c = countByStatus(surveys);

        // ★ 必须一次批量查询：逐份问卷调 countBySurveyId() 是 N+1（见 countResponsesBySurveyIds）
        Map<Integer, Integer> responseCounts = countResponsesBySurveyIds(surveys);
        int responses = 0;
        for (Survey s : surveys) {
            responses += responseCounts.getOrDefault(s.getId(), 0);
        }

        List<Survey> recent = new ArrayList<>(surveys);
        recent.sort((a, b) -> {
            if (a.getCreatedTime() == null) return 1;
            if (b.getCreatedTime() == null) return -1;
            return b.getCreatedTime().compareTo(a.getCreatedTime());
        });
        if (recent.size() > 5) recent = recent.subList(0, 5);

        // ★ 看板「最近问卷」也要显示每份的答卷数：复用上面同一份批量计数，
        //   只回填要返回的那 5 条（不额外查库、也不改其它字段）。
        for (Survey s : recent) {
            s.setResponseCount(responseCounts.getOrDefault(s.getId(), 0));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", c.total());
        result.put("published", c.published());
        result.put("draft", c.draft());
        result.put("ended", c.ended());
        result.put("responses", responses);
        result.put("recent", recent);

        // ===== 近 7 天答卷趋势 =====
        // 数据库只会返回"有答卷的那几天"，这里补齐成连续的 7 天，
        // 否则前端拿到的 x 轴会缺日子（曲线看起来像断了）。
        Map<String, Integer> daily = new HashMap<>();
        for (Map<String, Object> row : answerMapper.countDailyByUserId(userId)) {
            Object d = row.get("d");
            Object cnt = row.get("cnt");
            if (d != null && cnt instanceof Number) {
                daily.put(d.toString(), ((Number) cnt).intValue());
            }
        }
        List<String> trendDates = new ArrayList<>(7);
        List<Integer> trendCounts = new ArrayList<>(7);
        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        int recent7 = 0;
        for (int i = 6; i >= 0; i--) {
            String key = today.minusDays(i).format(fmt);
            int cnt = daily.getOrDefault(key, 0);
            trendDates.add(key);
            trendCounts.add(cnt);
            recent7 += cnt;
        }
        result.put("trendDates", trendDates);
        result.put("trendCounts", trendCounts);
        result.put("recent7Answers", recent7);

        return result;
    }

    /**
     * 系统级总览（管理员看板 / 系统信息卡片）：全站的问卷数、状态分布、答卷总数、访客总数。
     *
     * ★ 与 getUserStatistics() 的区别：这里统计**全站**（仍排除模板）。
     * ★ 答卷数同样走批量查询，SQL 条数恒为 1。
     *
     * @return 含 total / published / draft / ended / responses / views 的 Map
     */
    public Map<String, Object> getSystemOverview() {
        List<Survey> all = surveyMapper.findAll();
        List<Survey> normalSurveys = all.stream()
                .filter(s -> s.getIsTemplate() == null || s.getIsTemplate() == 0)
                .collect(Collectors.toList());

        StatusCounts c = countByStatus(normalSurveys);

        // ★ 同上：批量查一次，不能逐份循环（N+1）
        Map<Integer, Integer> responseCounts = countResponsesBySurveyIds(normalSurveys);
        int responses = 0;
        for (Survey s : normalSurveys) {
            responses += responseCounts.getOrDefault(s.getId(), 0);
        }

        int views = surveyMapper.sumViewCount();

        // ===== 2026-10 新增能力对应的真实计数（模板/举报/钱包/今日/趋势/TOP）=====
        // ★ 全部单条聚合 SQL，不按问卷循环；看板一次请求拿全。
        int templates = surveyMapper.countTemplates();
        int recycled = surveyMapper.countRecycled();
        int pendingReviews = surveyReviewMapper.countPendingAll();
        long walletBalance = walletMapper.sumBalance();
        // 对账异常是运营要盯的硬指标：>0 说明流水汇总与余额对不上，必须人工核对
        int walletMismatches = walletMapper.countMismatches();
        int todaySurveys = surveyMapper.countCreatedToday();
        int todayAnswers = answerMapper.countToday();

        // 近 7 天趋势：日期补齐成连续 7 天（没有数据的那天补 0）
        Map<String, Integer> dailySurveys = new HashMap<>();
        for (Map<String, Object> row : surveyMapper.countDailyCreated()) {
            Object d = row.get("d");
            Object cnt = row.get("cnt");
            if (d != null && cnt instanceof Number) {
                dailySurveys.put(d.toString(), ((Number) cnt).intValue());
            }
        }
        Map<String, Integer> dailyAnswers = new HashMap<>();
        for (Map<String, Object> row : answerMapper.countDailyAll()) {
            Object d = row.get("d");
            Object cnt = row.get("cnt");
            if (d != null && cnt instanceof Number) {
                dailyAnswers.put(d.toString(), ((Number) cnt).intValue());
            }
        }
        List<String> trendDates = new ArrayList<>(7);
        List<Integer> trendSurveys = new ArrayList<>(7);
        List<Integer> trendAnswers = new ArrayList<>(7);
        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = 6; i >= 0; i--) {
            String key = today.minusDays(i).format(fmt);
            trendDates.add(key);
            trendSurveys.add(dailySurveys.getOrDefault(key, 0));
            trendAnswers.add(dailyAnswers.getOrDefault(key, 0));
        }

        // TOP 5 问卷：按答卷数降序（同分按 id 升序，保证顺序稳定）
        List<Survey> ranked = new ArrayList<>(normalSurveys);
        ranked.sort((a, b) -> {
            int diff = responseCounts.getOrDefault(b.getId(), 0) - responseCounts.getOrDefault(a.getId(), 0);
            return diff != 0 ? diff : Integer.compare(a.getId(), b.getId());
        });
        if (ranked.size() > 5) {
            ranked = ranked.subList(0, 5);
        }
        List<Map<String, Object>> topSurveys = new ArrayList<>(ranked.size());
        for (Survey s : ranked) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("title", s.getTitle());
            item.put("status", s.getStatus());
            item.put("responses", responseCounts.getOrDefault(s.getId(), 0));
            topSurveys.add(item);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("total", c.total());
        result.put("published", c.published());
        result.put("draft", c.draft());
        result.put("ended", c.ended());
        result.put("responses", responses);
        result.put("views", views);
        result.put("templates", templates);
        result.put("recycled", recycled);
        result.put("pendingReviews", pendingReviews);
        result.put("walletBalance", walletBalance);
        result.put("walletMismatches", walletMismatches);
        result.put("todaySurveys", todaySurveys);
        result.put("todayAnswers", todayAnswers);
        result.put("trendDates", trendDates);
        result.put("trendSurveys", trendSurveys);
        result.put("trendAnswers", trendAnswers);
        result.put("topSurveys", topSurveys);
        return result;
    }

    // ===== 定时任务 =====

    /**
     * 每分钟跑一次：自动发布到点的问卷、自动关闭过期的问卷。
     *
     * ★ 依赖 @EnableScheduling（在 SurveyBackendApplication 上），去掉它这个任务就静默不跑了。
     * ★ 自动发布的条件由 SQL 决定（SurveyMapper.findPendingPublish）：到了 startTime 且还是草稿。
     * ★ 关闭条件（findPendingClose）：到了 endTime 且还是已发布。
     * ★ 发布逻辑与 publish() 保持一致：没有分享码（含纯空格的脏数据）才生成新的。
     */
    @Scheduled(cron = "0 * * * * ?")
    public void autoPublishAndClose() {
        List<Survey> toPublish = surveyMapper.findPendingPublish();
        for (Survey s : toPublish) {
            // ★ 每条必须独立 try-catch：循环体里任何一条出错（例如 generateShareCode 连续撞码抛
            //   IllegalStateException、或某行数据被别的连接锁住）都会**中断整批任务** ——
            //   本批次剩下的问卷既不会发布也不会关闭，而且异常只被调度器吞掉、没有任何记录。
            //   逐条容错之后一条失败不影响其余，LOG 里能看出是哪一条失败。
            try {
                // 与 publish() 保持一致：用 isBlank() 把"纯空格的脏分享码"也当作没有码
                String existingCode = s.getShareCode();
                if (existingCode == null || existingCode.isBlank()) {
                    s.setShareCode(generateShareCode());
                }
                // ★ H5：定时自动发布同样要扣额度（否则"定时发布"就成了免费通道）。
                //   先扣费再改状态；余额不足会抛异常 → 本条跳过，下一分钟重试（不会扣两次，
                //   幂等键 publish:{id} 保证扣过就不再扣）。作者会在「我的钱包」看到余额，
                //   也能从日志/发布失败提示里知道原因。
                walletService.chargeForPublish(s.getId(), s.getUserId());
                s.setStatus(SurveyStatus.PUBLISHED.code());
                surveyMapper.updateStatusAndShareCode(s.getId(), SurveyStatus.PUBLISHED.code(), s.getShareCode());
                LOG.info("⏰ 自动发布问卷：{} (id={})", s.getTitle(), s.getId());
            } catch (Exception e) {
                LOG.error("⏰ 自动发布失败（已跳过该条，继续处理其余）：id={} title={}",
                        s.getId(), s.getTitle(), e);
            }
        }

        List<Survey> toClose = surveyMapper.findPendingClose();
        for (Survey s : toClose) {
            // ★ 同上：逐条容错，避免一条坏数据把整批关闭任务带崩
            try {
                s.setStatus(SurveyStatus.ENDED.code());
                surveyMapper.updateStatusAndShareCode(s.getId(), SurveyStatus.ENDED.code(), s.getShareCode());
                LOG.info("⏰ 自动关闭问卷：{} (id={})", s.getTitle(), s.getId());
            } catch (Exception e) {
                LOG.error("⏰ 自动关闭失败（已跳过该条，继续处理其余）：id={} title={}",
                        s.getId(), s.getTitle(), e);
            }
        }
    }
}