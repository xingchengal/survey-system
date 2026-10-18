package com.survey.common;

/**
 * 问卷**业务生命周期** —— 与 {@link SurveyStatus}（0/1/2 的收答卷机器状态）并存、由 Service 同步维护。
 *
 * ── 为什么要新增一套，而不是复用 status ──────────────────
 *   status 的 0/1/2 被定时任务、前端路由、大量 SQL 依赖，改它的数字语义 = 全站回归。
 *   而业务上还需要"被举报下架（SUSPENDED）"这类**不改变 status 数字**的状态：
 *     下架后 status 仍是 1（已发布），但对外不可填写 —— 若硬塞成 status=3，
 *     所有 `status == 1` 的判断都会失效（这是明令禁止的做法）。
 *
 * ── 取值与映射 ────────────────────────────────────────
 *   DRAFT      ← 新建 / status=0（含 NULL）
 *   PUBLISHED  ← status=1 且未被下架
 *   SUSPENDED  ← 被举报审核下架（H1；此时 status 仍可能是 1）
 *   ENDED      ← status=2（到点自动关闭或作者手动结束）
 *   ARCHIVED   ← deleted=1（回收站；优先级高于 status）
 *
 * ★ 同步责任：publish / 自动发布 / 自动关闭 / 移入回收站 / 恢复 / 举报下架
 *   这几个入口**都要写 lifecycle**，只写 status 会造成两套口径打架。
 * ★ 对账 SQL（发现不一致时先查这里，别猜）：
 *   SELECT COUNT(*) FROM t_survey
 *   WHERE (deleted = 1 AND lifecycle <> 'ARCHIVED')
 *      OR (deleted = 0 AND status = 1 AND lifecycle NOT IN ('PUBLISHED', 'SUSPENDED'))
 *      OR (deleted = 0 AND status = 2 AND lifecycle <> 'ENDED')
 *      OR (deleted = 0 AND (status = 0 OR status IS NULL) AND lifecycle <> 'DRAFT');
 */
public enum SurveyLifecycle {

    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED"),
    SUSPENDED("SUSPENDED"),
    ENDED("ENDED"),
    ARCHIVED("ARCHIVED");

    private final String code;

    SurveyLifecycle(String code) {
        this.code = code;
    }

    /** 存库用的字符串（大写，与 migration-survey-lifecycle.sql 的回填值一致） */
    public String code() {
        return code;
    }

    /**
     * 由库里的字符串解析；未知值返回 null。
     * ★ 与其它枚举一致：不做大小写归一，脏数据交给调用方按"未知"处理（别静默猜成 DRAFT）。
     */
    public static SurveyLifecycle fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (SurveyLifecycle v : values()) {
            if (v.code.equals(code)) {
                return v;
            }
        }
        return null;
    }

    /**
     * 由"机器状态"推出应有的生命周期（回填与对账的**同一套**规则）。
     *
     * @param status   t_survey.status（0/1/2，NULL 视为草稿）
     * @param deleted  t_survey.deleted（!=0 视为回收站）
     */
    public static SurveyLifecycle of(Integer status, Integer deleted) {
        if (deleted != null && deleted != 0) {
            return ARCHIVED;   // ★ 回收站优先级最高：只把 deleted 置 1、不改 status
        }
        if (status != null && status == 1) {
            return PUBLISHED;
        }
        if (status != null && status == 2) {
            return ENDED;
        }
        return DRAFT;
    }
}
