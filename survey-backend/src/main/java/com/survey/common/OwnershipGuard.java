package com.survey.common;

import com.survey.entity.Survey;

/**
 * 问卷归属校验 —— "这份问卷能不能被这个人操作/看到"的唯一口径。
 *
 * ── 它解决什么 ────────────────────────────────────────
 *   1. **NPE 隐患**：`!survey.getUserId().equals(userId)` 这种写法没有判空。
 *      ★ 注意口径：`t_survey.user_id` 实际是 **NOT NULL**（预置模板存的是 **0** 而不是 NULL），
 *        所以那个 null 分支**在当前库上不可达**。之所以仍写成判空形式：
 *        这是全项目最要紧的安全判断，不该依赖"某张表的某个约束"
 *        （约束将来可能变，实体也可能由非数据库来源构造），而判空的成本是零。
 *        该分支由 OwnershipGuardTest 覆盖（nullOwner / anonymous 相关用例）。
 *   2. **重复**：同一段"管理员放行 / 否则必须是本人"的判断一旦在各端点逐行复制，
 *      新增角色或修改归属规则时就要改很多处，漏一处就是越权口径不一致。
 *      ⇒ 口径只定义在这里，端点只调用不重写。
 *
 * ── 与 @RequireAdmin / @RequirePermission 的分工（别混淆）────
 *   · {@code @RequireAdmin}：只看"角色"这一半（是不是管理员），切面在方法执行前统一拦；
 *   · {@code @RequirePermission}：看"账号有没有这项能力"（survey:edit 等），同样是切面；
 *   · 本类：看"**对象**归谁"——需要先按 id 查库拿到 Survey 才能判，所以留在调用点显式执行。
 *   ★ 完整的授权 = 能力（切面）× 对象（本类）。缺任何一半都是横向越权。
 *
 * ── 协作者（H2 新增）───────────────────────────────────
 *   "是不是这份问卷的协作者"要查 t_survey_collaborator，静态工具类不该拿 Mapper。
 *   所以这里只提供**纯函数**（把协作者角色码当参数传进来，可为 null），
 *   由 service/SurveyAccessService 负责查库后调用。
 *   ★ 作者本人拥有全部权限（含删除/发布），**不查权限点表** ——
 *     作者对自己数据的支配权不该由某张配置表决定（配置漏了就变成"作者删不了自己的问卷"）。
 *
 * ── 失败怎么表达 ──────────────────────────────────────
 *   统一抛 {@link BizException} → 400 + {success:false,message}，且**不写异常日志**
 *   （"不是你操作的东西"是可预期的业务拒绝，不是程序出错）。
 */
public final class OwnershipGuard {

    private OwnershipGuard() {
    }

    /**
     * 要求：管理员，或该问卷归属于 userId。否则抛 BizException。
     *
     * 适用：删除 / 修改 / 发布 / 复制 / 移入回收站 / 恢复 / 彻底删除 / 保存题目。
     * ★ H2 之后，除"复制"外的调用点都已改为
     *   {@link #requirePermission}（协作者按权限点放行）；
     *   复制仍只给作者/管理员 —— 复制会把内容搬进**调用者自己的**问卷，属于数据外带，不开放给协作者。
     *
     * ★ 判空顺序：先看 role，再判 ownerId 是否为 null。
     *   （user_id 当前是 NOT NULL，所以那个 null 分支实际不可达 ——
     *    保留它是为了不依赖 schema 约束，理由见类注释第 1 条。）
     *
     * @param survey 目标问卷（由调用方按 id 查出，可为 null）
     * @param userId 当前登录用户 id（匿名时为 null）
     * @param role   当前登录角色（来自 JwtAuthFilter 注入的 X-User-Role）
     * @param action 动作名，用于拼错误文案，如 "删除" → "无权限删除他人问卷"
     */
    public static void requireOwnerOrAdmin(Survey survey, Integer userId, String role, String action) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (Role.isAdmin(role)) {
            return;   // 管理员放行
        }
        if (isOwner(survey, userId)) {
            return;   // 作者本人
        }
        throw new BizException("无权限" + action + "他人问卷");
    }

    /**
     * 是否拥有某个问卷级权限：**管理员 / 作者 / 协作者（按角色映射的权限点）** 三选一。
     *
     * ★ 纯函数：不查库、不抛异常 —— 协作者角色码由调用方（SurveyAccessService）查好传进来。
     *   null 表示"不是协作者"，此时只剩管理员/作者能过。
     *
     * @param permission 取值见 {@link Permission}（survey:edit / survey:publish / survey:data / survey:delete）
     */
    public static boolean canAccess(Survey survey, Integer userId, String role,
                                    String permission, String collaboratorRoleCode) {
        if (survey == null) {
            return false;
        }
        if (Role.isAdmin(role) || isOwner(survey, userId)) {
            return true;   // 管理员与作者：全权（作者不依赖权限点表）
        }
        // ★★ 匿名（userId=null）不可能有协作者身份：即便调用方误传了角色码也必须拒绝。
        //   这条是**防御性**的 —— 线上 SurveyAccessService 在 userId 为 null 时根本不查库，
        //   但纯函数不能依赖调用方守规矩（单测先把它钉住了）。
        if (userId == null) {
            return false;
        }
        CollaboratorRole collaborator = CollaboratorRole.fromCode(collaboratorRoleCode);
        return collaborator != null && collaborator.has(permission);
    }

    /**
     * 要求某问卷级权限，否则抛 **「无权限{action}他人问卷」**。
     * ★ 文案格式与 {@link #requireOwnerOrAdmin} 完全一致：EndpointAuthMatrixTest 与前端都按它断言。
     *
     * @param action 动作名（"修改" / "发布" / "删除" / "查看版本历史" …）
     */
    public static void requirePermission(Survey survey, Integer userId, String role,
                                         String permission, String collaboratorRoleCode, String action) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (!canAccess(survey, userId, role, permission, collaboratorRoleCode)) {
            throw new BizException("无权限" + action + "他人问卷");
        }
    }

    /**
     * 要求：管理员 / 本人 / 问卷已发布 —— 三者之一才允许**读取**；**回收站中的问卷另有规则**。
     *
     * 适用：GET /api/survey/{id}、GET /api/question/list/{surveyId}。
     * ★ "已发布即可读"与分享链接的口径一致：已发布问卷本来就是公开内容，不算私密。
     * ★ **回收站优先**：只要 deleted != 0，就只放行管理员与作者本人，**不再看 status**。
     *   原因：moveToRecycle 只把 deleted 置 1、**不改 status**，
     *   若还按"已发布即可读"放行，一份已发布问卷被删进回收站后仍会对所有登录用户可见
     *   （连题目与选项都能读到）—— 逻辑删除会形同虚设。详见方法体内注释。
     *
     * @param message 不通过时抛出的文案（两处调用点的说法不同，故由调用方传入，
     *                避免为了统一实现而改变对用户可见的提示）
     */
    public static void requireVisible(Survey survey, Integer userId, String role, String message) {
        requireVisible(survey, userId, role, null, message);
    }

    /**
     * 带协作者身份的可见性判定（H2 新增）。
     *
     * ★ 与上面同名方法的差别只有一处：**协作者（任何角色）可以读非回收站问卷** ——
     *   否则"被邀请来看"的人连问卷都打不开。
     * ★ 回收站里**不算**协作者：删进回收站的问卷只给作者/管理员看，协作者一律挡掉。
     */
    public static void requireVisible(Survey survey, Integer userId, String role,
                                      String collaboratorRoleCode, String message) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        // ★ **回收站中的问卷只有管理员与作者本人能读**（作者要能看到自己删掉的东西）。
        if (isInRecycleBin(survey)) {
            if (Role.isAdmin(role) || isOwner(survey, userId)) {
                return;
            }
            throw new BizException("问卷已在回收站中");
        }
        if (Role.isAdmin(role) || isOwner(survey, userId)) {
            return;
        }
        // ★ 同上：匿名没有协作者身份，误传角色码也不能放行。
        if (userId != null && CollaboratorRole.fromCode(collaboratorRoleCode) != null) {
            return;   // 协作者：被邀请来看的人至少能打开问卷
        }
        if (SurveyStatus.isPublished(survey.getStatus())) {
            return;   // 已发布 = 公开内容
        }
        throw new BizException(message);
    }

    /**
     * 要求：问卷**不在回收站**（deleted = 0 或 NULL）。
     *
     * ★ "回收站"这件事必须在**所有入口**都挡，不能只挡一部分 ——
     *   发布（SurveyService.publish）、按 id 读（requireVisible）、提交答卷（AnswerService.submit）
     *   都挡；而**编辑问卷（改标题/描述/时间）与保存题目**如果没挡，会出现：
     *   一份问卷移入回收站后作者仍能改它的标题，甚至用"全量覆盖"的保存接口把题目数组换掉
     *   （没传的旧题会被删掉 → 历史答卷的 question_id 就此悬空、相关统计口径全变）。
     *   现在把这个判断收成统一入口，新入口照抄即可，别再各写一份 if。
     *
     * @param action 动作名，用于拼文案，如 "编辑" → "问卷已在回收站中，无法编辑"
     */
    public static void requireNotInRecycleBin(Survey survey, String action) {
        if (survey == null) {
            throw new BizException("问卷不存在");
        }
        if (isInRecycleBin(survey)) {
            throw new BizException("问卷已在回收站中，无法" + action);
        }
    }

    /** 是否作者本人（统一判空口径，不要在各处写 `survey.getUserId().equals(userId)`） */
    private static boolean isOwner(Survey survey, Integer userId) {
        Integer ownerId = survey.getUserId();
        return ownerId != null && ownerId.equals(userId);
    }

    /**
     * 问卷是否在回收站（deleted = 1）。
     *
     * ★ deleted 允许为 NULL（历史数据），这里按"**NULL = 未删除**"处理：
     *   与 SurveyMapper 里到处写的 deleted = 0 查询口径保持一致 ——
     *   NULL 那几条在正常列表里确实查不到，但它们并不是"被删进回收站"的状态，
     *   不能在这里被误判成回收站问卷（否则作者会突然读不到自己的问卷）。
     */
    private static boolean isInRecycleBin(Survey survey) {
        return survey.getDeleted() != null && survey.getDeleted() != 0;
    }
}
