package com.survey.common;

/**
 * 问卷状态：0=草稿 / 1=已发布 / 2=已结束，**不要改这些数字**。
 *
 * ★ status 不是“能否收答卷”的判据：截止时间由 AnswerService.submit 就地判，定时任务只负责置 2。
 * ★ 列允许 NULL（历史数据），所有判断按“NULL = 草稿”处理。
 */
public enum SurveyStatus {

    /** 草稿：只有作者和管理员可见，不收答卷 */
    DRAFT(0),
    /** 已发布：可访问；能不能提交答卷还取决于是否已过截止时间（见类注释） */
    PUBLISHED(1),
    /** 已结束：不再收答卷；按截止时间由定时任务置位，仅作状态展示 */
    ENDED(2);

    private final int code;

    SurveyStatus(int code) {
        this.code = code;
    }

    /** 存库用的数值 */
    public int code() {
        return code;
    }

    /** 是否草稿（★ null 也算草稿 —— 历史数据里有 status 为 NULL 的问卷） */
    public static boolean isDraft(Integer status) {
        return status == null || status == DRAFT.code;
    }

    /** 是否已发布 */
    public static boolean isPublished(Integer status) {
        return status != null && status == PUBLISHED.code;
    }

    /** 是否已结束 */
    public static boolean isEnded(Integer status) {
        return status != null && status == ENDED.code;
    }
}
