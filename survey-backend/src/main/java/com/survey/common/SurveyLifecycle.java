package com.survey.common;

/**
 * 问卷**业务生命周期** —— 与 {@link SurveyStatus}（0/1/2 的收答卷状态）并存，由 Service 同步维护。
 * SUSPENDED（举报下架）不映射到 status：下架后 status 仍是 1，对外不可填写；另加 status=3 会让所有
 * `status == 1` 的判断失效。
 *
 * ★ publish / 自动关闭 / 移入回收站 / 恢复 / 下架这些入口都要写 lifecycle，只写 status 会让两套口径打架。
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

    /** 由库里的字符串解析；未知值返回 null（不做大小写归一，别静默猜成 DRAFT）。 */
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
     * 由"机器状态"推出应有的生命周期（回填与对账共用同一套规则）。
     * @param status  t_survey.status（0/1/2，NULL 视为草稿）
     * @param deleted t_survey.deleted（!=0 视为回收站）
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
