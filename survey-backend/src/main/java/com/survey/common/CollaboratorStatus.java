package com.survey.common;

import java.util.Set;

/**
 * 协作者邀请状态 —— **只有 ACCEPTED 参与权限判定**。
 * PENDING=待被邀请人确认 / ACCEPTED=已生效 / REJECTED=被拒绝 / REVOKED=作者撤销。
 *
 * ★ 迁移时存量行已回填 ACCEPTED；脏值（未知字符串）按"非协作者"处理，不要抛异常，
 *   一条坏数据不该让整份问卷的协作者列表打不开。
 * ★ 状态是同一行的生命周期，不新增行：唯一键 uk_survey_user 仍然保证"一人一份一行"。
 */
public final class CollaboratorStatus {

    /** 已发出邀请，等待被邀请人接受 */
    public static final String PENDING = "PENDING";

    /** 已生效：唯一参与权限判定的状态 */
    public static final String ACCEPTED = "ACCEPTED";

    /** 被邀请人拒绝 */
    public static final String REJECTED = "REJECTED";

    /** 作者撤销未处理的邀请 */
    public static final String REVOKED = "REVOKED";

    public static final Set<String> ALL = Set.of(PENDING, ACCEPTED, REJECTED, REVOKED);

    private CollaboratorStatus() {
    }

    /** 是否已生效（权限判定的唯一口径） */
    public static boolean isAccepted(String status) {
        return ACCEPTED.equals(status);
    }
}
