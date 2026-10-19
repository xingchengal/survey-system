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
import com.survey.common.Permission;
import com.survey.common.SqlLikeEscaper;
import com.survey.common.PageResult;
import com.survey.common.Role;
import com.survey.entity.OperationLog;
import com.survey.entity.Option;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.mapper.AnswerDetailMapper;
import com.survey.mapper.UserMapper;
import com.survey.service.NotificationService;
import com.survey.mapper.AnswerMapper;
import com.survey.mapper.OperationLogMapper;
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
 * status 0=草稿 / 1=已发布 / 2=已结束，数字不要改；lifecycle 并存，各入口必须同步维护（下架不映射到 status）。
 * ★ 分享码服务端独占：SecureRandom 生成 6 位 + 查重，绝不接受请求体传入。
 * ★ 回收站是逻辑删除（deleted=1）；permanentDelete() 要求先移入回收站，避免手滑彻底删掉正常问卷。
 * 模板的 userId=0 属于公共资产；级联删除顺序见 cascadeDeleteSurvey（先子后父）。
 * 统计一律批量 GROUP BY，不逐份 COUNT（见 countResponsesBySurveyIds 的 N+1 说明）。
 */
@Service
public class SurveyService {

    /** 用 SLF4J：System.out/printStackTrace 的输出在生产环境会丢失。 */
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

    /** H5 额度钱包：发布扣费 / 回收站退款；扣费与状态变更同事务。 */
    @Autowired
    private WalletService walletService;

    /** 通知收件人来源：只用 findAdminIds()，已注销管理员必须被排除。 */
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private NotificationService notificationService;

    /** 模板分类登记：权限/长度统一走 TemplateCategoryService，这里不重复判断。 */
    @Autowired
    private TemplateCategoryService templateCategoryService;

    /** 彻底删除要清的后加子表；新增"从属于问卷"的表必须同步进 cascadeDeleteSurvey。 */
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

    /** 总览「最近动态」：只读最近 5 条操作日志，写入口仍在 LogAspect。 */
    @Autowired
    private OperationLogMapper operationLogMapper;

    // ===== 分享码生成 =====

    /** 分享码字符集（小写字母 + 数字） */
    private static final String SHARE_CODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    /** 分享码长度 */
    private static final int SHARE_CODE_LENGTH = 6;
    /** 撞码后最多重试几次 */
    private static final int SHARE_CODE_MAX_RETRY = 20;

    /** 用 SecureRandom：Random 可预测，码被猜到就能免登录打开未公开问卷。 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 生成数据库未占用的分享码（6 位 + 查重重试）：撞唯一索引会 500，所以先查再返回。
     * 保持 public 是给演示数据复用；另写一份不查重的会整批回滚。
     *
     * @return 全新分享码；连续 20 次仍撞码抛 IllegalStateException（兜底）
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
    /** 描述上限 = 列宽 varchar(500)；放宽前必须先 ALTER 列宽，否则超长会变成 500 而非 400。 */
    private static final int MAX_DESC_LENGTH = TextLimits.SURVEY_DESCRIPTION;

    /** 导出上限：SQL 固定 LIMIT 10001，超出明确拒绝，不静默截断（全量进内存会打满堆）。 */
    public static final int SURVEY_EXPORT_MAX_ROWS = 10_000;

    /**
     * 校验 title / description 长度：超长会被 MySQL 拒绝成 500，这里提前转成 400。
     * 只校验长度、不剥 HTML：转义由 Vue 模板负责，硬剥会破坏用户数据。
     *
     * @throws BizException 超长时
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
     * 新建问卷：status / shareCode / isTemplate / deleted 一律无条件归零。
     * 它们是服务端独占字段，信请求体会造出"已发布却没分享码""自选分享码绕过查重"的脏数据，
     * 其中自选分享码还能被匿名分享入口读到草稿。
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

    // 同类内部调用 cascadeDeleteSurvey 时它的 @Transactional 不生效：外层必须自己开事务，否则会半删。

    /**
     * 级联物理删除一份问卷及其全部子数据。
     * ★ 顺序必须自底向上：明细 → 答卷 → 选项 → 题目 → 版本/协作/IP/举报 → 通知 → 问卷；反过来会留孤儿。
     * 由 permanentDelete 与 UserService.deleteUser 复用；返回删除行数（0 = 本来就不存在）。
     *
     * @param surveyId 问卷 id
     */
    @Transactional
    public int cascadeDeleteSurvey(Integer surveyId) {
        if (surveyId == null) return 0;

        // 先取题目 id：题目行删掉后逐题缓存 key 就定位不到；只按具体 key 删，不做前缀扫描（会连限流计数一起清）。
        List<Integer> questionIds = questionMapper.findBySurveyId(surveyId).stream()
                .map(Question::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 1) 明细用一条 JOIN DELETE：逐份删会变成上万条 DELETE 在同一事务里持锁。
        int detailRows = answerDetailMapper.deleteBySurveyId(surveyId);
        // 2) 答卷
        int answerRows = answerMapper.deleteBySurveyId(surveyId);
        // 3) 选项
        optionMapper.deleteBySurveyId(surveyId);
        // 4) 题目
        questionMapper.deleteBySurveyId(surveyId);
        // 5) 版本快照 / 协作者 / IP 记录 / 举报：必须先于删问卷本体，否则再也定位不到。
        int versionRows = surveyVersionMapper.deleteBySurveyId(surveyId);
        int collaboratorRows = surveyCollaboratorMapper.deleteBySurveyId(surveyId);
        int ipRows = surveyIpMapper.deleteBySurveyId(surveyId);
        int reviewRows = surveyReviewMapper.deleteBySurveyId(surveyId);
        // 5.5) 通知按 link 精确清：否则"问卷不存在"的未读通知会让红点永远消不掉。
        //      通知不是"从属于问卷"的表，没有 survey_id 列，所以不能用 deleteBySurveyId。
        int notificationRows = notificationService.deleteBySurveyLink(surveyId);
        // 6) 问卷本体
        int rows = surveyMapper.deleteById(surveyId);
        // 7) 删缓存是幂等的；自增类操作绝不能在事务内做（回滚会让缓存比库多）。
        statsCacheService.evictSurvey(surveyId, questionIds);
        LOG.info("级联删除问卷 id={}：清理答卷 {} 份 / 明细 {} 条 / 版本 {} 条 / 协作者 {} 条 / "
                        + "IP {} 条 / 举报 {} 条 / 站内通知 {} 条，以及其选项与题目，删除问卷 {} 行",
                surveyId, answerRows, detailRows, versionRows, collaboratorRows, ipRows, reviewRows,
                notificationRows, rows);
        return rows;
    }

    /**
     * 更新问卷：只改 title / description / start_time / end_time。
     * update 的静态 SQL 不含 status / share_code，状态变更只走 publish() 与专用 SQL；
     * 起止时间传 null 表示清空，所以保留静态 SQL（不是动态更新）。
     */
    public int updateSurvey(Survey survey) {
        validateSurveyText(survey);
        normalizeSurveySettings(survey);
        return surveyMapper.update(survey);
    }

    /** 问卷设置归一与校验：空串归 NULL；跳转链接只放行 http/https（防 javascript: 伪协议）。 */
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

        // 考试模式只认 0/1；关闭时清掉及格线（避免幽灵阈值），开启时必须已有计分题（总分与 Grader 同口径）。
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

        // 0/null = 不限时，上限 24h；duration_ms 可伪造，拦的是正常超时，不是防作弊。
        Integer timeLimit = survey.getTimeLimitSeconds();
        if (timeLimit != null && timeLimit <= 0) {
            timeLimit = null;   // 0 与 null 同义：不限时（前端清空输入框时可能传 0）
        }
        if (timeLimit != null && timeLimit > 86400) {
            throw new BizException("答题限时不能超过 24 小时（86400 秒）；留空表示不限时");
        }
        survey.setTimeLimitSeconds(timeLimit);

        // 显示答案只对考试问卷有意义，关闭考试时强制归零。
        int showAnswer = gradeEnabled == 1 && survey.getShowAnswerAfterSubmit() != null
                && survey.getShowAnswerAfterSubmit() == 1 ? 1 : 0;
        survey.setShowAnswerAfterSubmit(showAnswer);

        // 仅限登录用户：只认 0/1，默认 0（老问卷保持匿名）。
        survey.setRequireLogin(survey.getRequireLogin() != null && survey.getRequireLogin() == 1 ? 1 : 0);

        // 签到核销 / 公开报名名单默认关闭。
        survey.setCheckinEnabled(survey.getCheckinEnabled() != null && survey.getCheckinEnabled() == 1 ? 1 : 0);
        survey.setShowParticipants(survey.getShowParticipants() != null && survey.getShowParticipants() == 1 ? 1 : 0);
    }

    /** 去空白；空串返回 null（"清空"在库里统一表示为 NULL） */
    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 系统统计列表口径（不含模板）：与 getSystemOverview 的问卷总数保持一致。 */
    public List<Survey> listNormal() {
        return surveyMapper.findNormal();
    }

    /** 我的问卷：用一次 GROUP BY 批量回填答卷数（responseCount 非数据库字段，只有这里赋值）。 */
    public List<Survey> listByUserId(Integer userId) {
        List<Survey> mine = surveyMapper.findByUserId(userId);
        Map<Integer, Integer> counts = countResponsesBySurveyIds(mine);
        for (Survey survey : mine) {
            survey.setResponseCount(counts.getOrDefault(survey.getId(), 0));
        }
        return mine;
    }

    /** 按 id 查问卷：各处归属校验的基础。 */
    public Survey getById(Integer id) {
        return surveyMapper.findById(id);
    }

    /** 按分享码查问卷（匿名访客打开分享链接走这里）。 */
    public Survey getByShareCode(String shareCode) {
        return surveyMapper.findByShareCode(shareCode);
    }

    /**
     * 校验答题密码：未设直接通过；没填与填错用同一句文案，避免探测"这份问卷有没有密码"。
     * 放在 Service 是因为加载题目与提交答卷两个入口共用同一份判定。
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
     * 编辑器专用：管理员/作者读自己的题目时跳过答题密码。
     * 豁免只能限定在编辑器路径：按"是否作者"一刀切会让填写页把"能读到题目"当成密码正确，
     * 作者输错也能进、提交时才 400。userId 匿名时为 null，role 来自 X-User-Role。
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

    /** 取消下架：只允许 SUSPENDED → PUBLISHED，不能让管理员把任意问卷改成已发布。 */
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
     * 作者申请恢复上架：校验存在、处于 SUSPENDED、且是本人后，通知所有有效管理员（surveyId + userId）。
     * 归属比较用 equals：userId 是 Integer，== 超过 127 会变成比引用。
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
        // link 带 ?status=APPROVED：不带筛选会落在"待处理"空列表，管理员找不到这份问卷。
        // 只发给有 review:handle 的 staff：没权限的次级管理员收到这条通知也只能干瞪眼。
        for (Integer staffId : userMapper.findStaffIdsByPermission(Permission.REVIEW_HANDLE)) {
            notificationService.send(staffId, "恢复上架申请",
                    "作者申请恢复《" + survey.getTitle() + "》，请到举报审核页处理",
                    "review", "/admin/reviews?status=APPROVED");
        }
    }

    /** 手动推 t_survey.updated_time：保存题目/版本回滚只改子表，ON UPDATE 不会触发。 */
    public void touchUpdatedTime(Integer surveyId) {
        surveyMapper.touchUpdatedTime(surveyId);
    }

    /**
     * 发布问卷：没有分享码（含纯空白）就生成一个，扣 1 点额度后置为已发布（同事务，余额不足整体回滚）。
     * 回收站里的不能发布；已结束的只有截止时间被延到未来（或清空）才允许重新发布。
     * 问卷不存在抛 BizException；返回发布后的问卷（含分享码）。
     *
     * @param surveyId 问卷 id
     */
    @Transactional
    public Survey publish(Integer surveyId) {
        Survey survey = surveyMapper.findById(surveyId);
        // 不存在抛异常而不是返回 null：调用方不会拿到一个"看起来正常"的空值。
        if (survey == null) throw new BizException("问卷不存在");

        // 状态机守卫：回收站里的问卷不能发布（findById 不过滤 deleted，否则会绕出一次假发布）；
        // 已结束的问卷只有把 endTime 改到未来（或清空）才允许回 2→1，否则会永久卡在已结束。
        if (survey.getDeleted() != null && survey.getDeleted() != 0) {
            throw new BizException("问卷已在回收站中，请先恢复再发布");
        }
        if (SurveyStatus.isEnded(survey.getStatus())) {
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
        // 状态只能走专用 SQL；先扣费再改状态，同事务回滚。
        walletService.chargeForPublish(survey.getId(), survey.getUserId());
        survey.setStatus(SurveyStatus.PUBLISHED.code());
        surveyMapper.updateStatusAndShareCode(survey.getId(), SurveyStatus.PUBLISHED.code(), survey.getShareCode());
        return survey;
    }

    // ===== 回收站 =====

    /** 移入回收站（逻辑删除，记 deletedBy）；同事务退 1 点，幂等键保证只退一次。 */
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
     * 彻底删除：必须先移入回收站（deleted=1），否则抛异常；必须走 cascadeDeleteSurvey 清子表。
     * 外层自己标 @Transactional：同类调用 cascadeDeleteSurvey 的事务注解不生效。
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

    // 回收站只保留分页查询：keyword 传 null 即等价于全量，不再写第二份口径。

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
     * 复制问卷及其题目/选项：副本归 userId，status=0、shareCode=null、isTemplate=0。
     * 跳转目标 next_question_id 存的是真 id，必须按"源题目 id → 副本题目 id"映射。
     * 源问卷不存在抛 BizException；返回新问卷（含 id）。
     *
     * @param sourceId 源问卷/模板 id，userId 为副本归属
     */
    @Transactional
    public Survey copySurvey(Integer sourceId, Integer userId) {
        Survey old = surveyMapper.findById(sourceId);
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
        // 跳转目标是真 question.id：先建"源题目 id → 副本题目 id"映射，直接拷 id 会指向原问卷。
        Map<Integer, Integer> questionIdMap = new HashMap<>();
        for (Question q : questions) {
            Question newQ = new Question();
            newQ.setSurveyId(newSurvey.getId());
            newQ.setType(q.getType());
            newQ.setTitle(q.getTitle());
            newQ.setRequired(q.getRequired());
            newQ.setSortOrder(q.getSortOrder());
            // 矩阵行列标题必须一起复制，否则副本渲染不出题目。
            newQ.setMatrixRows(q.getMatrixRows());
            newQ.setMatrixCols(q.getMatrixCols());
            questionMapper.insert(newQ);
            questionIdMap.put(q.getId(), newQ.getId());
        }

        // 选项最后拷：前向跳转依赖所有新题目 id 已生成。
        for (Question q : questions) {
            if (!QuestionType.hasOptions(q.getType())) continue;
            Integer newQuestionId = questionIdMap.get(q.getId());
            List<Option> options = optionMapper.findByQuestionId(q.getId());
            for (Option opt : options) {
                Option newOpt = new Option();
                newOpt.setQuestionId(newQuestionId);
                newOpt.setContent(opt.getContent());
                newOpt.setSortOrder(opt.getSortOrder());
                // 漏拷 nextQuestionId 会静默丢掉全部跳转规则。
                newOpt.setNextQuestionId(remapNextQuestionId(opt.getNextQuestionId(), questionIdMap));
                optionMapper.insert(newOpt);
            }
        }
        return newSurvey;
    }

    /**
     * 把源跳转目标 id 翻译成副本题目 id；-1/null 原样返回，映射不到返回 null（退化成顺序）。
     * 存量脏数据指向已删题目时，复制失败比降级更糟。
     *
     * @param target 源 nextQuestionId（null=顺序、-1=结束）；questionIdMap 为源→副本题目 id
     */
    private static Integer remapNextQuestionId(Integer target, Map<Integer, Integer> questionIdMap) {
        if (target == null || target == -1) return target;
        return questionIdMap.get(target);
    }

    /**
     * 生成副本标题并截断到 MAX_TITLE_LENGTH：copySurvey 不走 validateSurveyText，超长会被 MySQL 拒成 500。
     *
     * @param sourceTitle 源问卷标题（可为 null）
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
        // 必须转义 LIKE 通配符：否则 % 匹配全表、_ 匹配任意字符，筛选被静默绕过。
        return surveyMapper.findTemplates(SqlLikeEscaper.escape(keyword));
    }

    /**
     * 从模板创建：本质是复制模板，只允许 is_template=1 或已发布的问卷。
     * 校验必须在 Service：copySurvey 不校验归属，少了它任何登录用户都能复制走他人的草稿。
     */
    @Transactional
    public Survey createFromTemplate(Integer templateId, Integer userId) {
        Survey source = surveyMapper.findById(templateId);
        if (source == null) {
            throw new BizException("模板不存在");
        }
        // 回收站里的问卷不能复制。
        if (source.getDeleted() != null && source.getDeleted() != 0) {
            throw new BizException("模板不存在");
        }
        boolean isTemplate = source.getIsTemplate() != null && source.getIsTemplate() == 1;
        if (!isTemplate && !SurveyStatus.isPublished(source.getStatus())) {
            throw new BizException("该问卷不是模板，也未发布，无法复制");
        }
        Survey copy = copySurvey(templateId, userId);
        // 只有真的用了模板才 +1；与 copySurvey 同事务，复制失败回滚时计数不动。
        if (isTemplate) {
            surveyMapper.incrementTemplateUseCount(templateId);
        }
        return copy;
    }

    /**
     * 设为模板 / 取消模板（管理员）。
     * 回收站里的问卷不能设为模板：否则恢复后会不经确认就变成公共模板；取消模板时无条件清空分类。
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
        // 空白分类归 NULL：库里不能同时有 NULL 与 '' 两种"没有分类"。
        String normalizedCategory = (category == null || category.isBlank()) ? null : category.trim();
        // 取消模板时无条件清分类，避免"不是模板却挂着分类"的脏数据。
        if (isTemplate != null && isTemplate == 0) {
            normalizedCategory = null;
        }
        // 设为模板必须有分类，否则模板广场的分类统计会多出一组。
        if (isTemplate != null && isTemplate == 1 && normalizedCategory == null) {
            throw new BizException("设为模板时必须选择模板分类");
        }
        // 现场输入的新分类要登记进清单（只在 isTemplate=1 时登记）。
        if (isTemplate != null && isTemplate == 1) {
            templateCategoryService.ensure(normalizedCategory);
        }
        return surveyMapper.setTemplate(id, isTemplate, normalizedCategory);
    }

    // ===== 分页 =====

    /**
     * 分页查全部问卷（管理员列表，支持关键字/状态/创建时间范围）。
     * PageHelper.startPage 只对紧随其后的那条查询生效；时间格式错误由 DateRanges 转成 400。
     */
    public PageResult<Survey> pageAllSurveys(int pageNum, int pageSize, String keyword,
                                             Integer status, String startDate, String endDate) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // 同 listTemplates：转义 LIKE 通配符。
        keyword = SqlLikeEscaper.escape(keyword);
        List<Survey> list = surveyMapper.findPageByCondition(keyword, status,
                DateRanges.startOfDay(startDate), DateRanges.endOfDay(endDate));
        PageInfo<Survey> pageInfo = new PageInfo<>(list);
        // 回显钳制后的页码/页大小，否则 pageSize=100000 时响应与数据对不上。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /**
     * 导出问卷（不分页，最多 10000 份）：筛选口径与 pageAllSurveys 一致。
     * 超出上限明确拒绝，不静默截断；需要答卷数由调用方批量补，别逐份 count。
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
        // 同 listTemplates：转义 LIKE 通配符。
        keyword = SqlLikeEscaper.escape(keyword);
        List<Survey> list = surveyMapper.findRecyclePageByUser(userId, keyword);
        PageInfo<Survey> pageInfo = new PageInfo<>(list);
        // 同 pageAllSurveys：回显钳制后的页码/页大小。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /** 分页查全部回收站（Recycle.vue 管理员视角）。 */
    public PageResult<Survey> pageRecycleAll(int pageNum, int pageSize, String keyword) {
        PageHelper.startPage(PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
        // 同 listTemplates：转义 LIKE 通配符。
        keyword = SqlLikeEscaper.escape(keyword);
        List<Survey> list = surveyMapper.findRecyclePageAll(keyword);
        PageInfo<Survey> pageInfo = new PageInfo<>(list);
        // 同 pageAllSurveys：回显钳制后的页码/页大小。
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getList(),
                PageParams.pageNum(pageNum), PageParams.pageSize(pageSize));
    }

    /**
     * 批量统计一批问卷的答卷数，返回 surveyId → 答卷数；一次 GROUP BY，SQL 条数恒为 1。
     * 没有答卷的问卷不会出现在 Map 里，取值必须用 getOrDefault(id, 0)。
     *
     * @param surveys 问卷列表（null/空时返回空 Map）
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

    /** 状态分布计数（published=1、draft=null/0、ended=2）。 */
    public record StatusCounts(long total, long published, long draft, long ended) {
    }

    /** 统计一批问卷的状态分布；口径只在这里定义，个人看板与系统总览共用。 */
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
     * 个人看板：问卷数 / 状态分布 / 答卷总数 / 最近 5 份 / 近 7 天趋势（模板不计入）。
     * 答卷数走批量查询，趋势是第二条聚合 SQL，SQL 条数恒为 2。
     */
    public Map<String, Object> getUserStatistics(Integer userId) {
        List<Survey> surveys = surveyMapper.findByUserId(userId);
        surveys = surveys.stream()
                .filter(s -> s.getIsTemplate() == null || s.getIsTemplate() == 0)
                .collect(Collectors.toList());

        StatusCounts c = countByStatus(surveys);

        // 一次批量查询：逐份调是 N+1。
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

        // 最近 5 份回填答卷数：复用上面的批量计数，不额外查库。
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

        // 近 7 天趋势：数据库只返回有数据的日子，这里补齐成连续 7 天，否则 x 轴会缺日子。
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
     * 全站总览（管理端人员）：按模块权限返回字段，没权限的模块连查询都不做。
     * ★ `system:view` **不再**等于全站业务数据：它只解锁系统统计页的资源模块；
     *   想看问卷/用户/模板/举报/钱包/日志，必须分别拥有对应模块权限。
     * users 由 Controller 按 user:manage 补。
     * 字段 → 权限：问卷/答卷/访客/回收站/今日=survey:manage · 模板=template:manage ·
     * 待审=review:handle · 钱包=wallet:manage · 最近动态=log:view。
     */
    public Map<String, Object> getSystemOverview(Set<String> permissions) {
        Set<String> perms = permissions == null ? Set.of() : permissions;

        Map<String, Object> result = new HashMap<>();

        if (perms.contains(Permission.SURVEY_MANAGE)) {
            List<Survey> normalSurveys = surveyMapper.findNormal();
            StatusCounts c = countByStatus(normalSurveys);

            // 批量查一次，不能逐份循环（N+1）。
            Map<Integer, Integer> responseCounts = countResponsesBySurveyIds(normalSurveys);
            int responses = 0;
            for (Survey s : normalSurveys) {
                responses += responseCounts.getOrDefault(s.getId(), 0);
            }

            result.put("total", c.total());
            result.put("published", c.published());
            result.put("draft", c.draft());
            result.put("ended", c.ended());
            result.put("responses", responses);
            result.put("views", surveyMapper.sumViewCount());
            result.put("recycled", surveyMapper.countRecycled());
            result.put("todaySurveys", surveyMapper.countCreatedToday());
            result.put("todayAnswers", answerMapper.countToday());
        }

        if (perms.contains(Permission.TEMPLATE_MANAGE)) {
            result.put("templates", surveyMapper.countTemplates());
        }
        if (perms.contains(Permission.REVIEW_HANDLE)) {
            result.put("pendingReviews", surveyReviewMapper.countPendingAll());
        }
        if (perms.contains(Permission.WALLET_MANAGE)) {
            result.put("walletBalance", walletMapper.sumBalance());
            // >0 表示流水汇总与余额对不上，必须人工核对。
            result.put("walletMismatches", walletMapper.countMismatches());
        }
        if (perms.contains(Permission.LOG_VIEW)) {
            result.put("recentActivities", recentActivities());
        }
        return result;
    }

    /** 总览「最近动态」：最近 5 条操作日志，时间格式与其它列表接口一致。 */
    private List<Map<String, Object>> recentActivities() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (OperationLog log : operationLogMapper.findRecent(5)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("username", log.getUsername());
            item.put("operation", log.getOperation());
            item.put("createTime", log.getCreateTime() == null ? null : log.getCreateTime().format(fmt));
            rows.add(item);
        }
        return rows;
    }

    // ===== 定时任务 =====

    /**
     * 每分钟自动发布到点的问卷、关闭过期的问卷。
     * 先查再改不是原子操作：本任务假定单实例部署，多实例会重复处理同一批数据。
     * 发布逻辑与 publish() 一致；每条独立 try-catch，一条失败不影响其余。
     */
    @Scheduled(cron = "0 * * * * ?")
    public void autoPublishAndClose() {
        List<Survey> toPublish = surveyMapper.findPendingPublish();
        for (Survey s : toPublish) {
            // 逐条容错：任一条抛异常都会中断整批任务，而且异常只被调度器吞掉。
            try {
                // 纯空格的脏分享码也当作没有码。
                String existingCode = s.getShareCode();
                if (existingCode == null || existingCode.isBlank()) {
                    s.setShareCode(generateShareCode());
                }
                // 自动发布同样扣额度（否则定时发布是免费通道）；幂等键保证不会重复扣。
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
            // 同上：逐条容错。
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