package com.survey.common;

import java.util.Set;

/**
 * 额度流水的业务类型 —— **全项目唯一出处**（t_wallet_transaction.type 只允许出现这里的值）。
 *
 * ★ 为什么用常量类而不是枚举：type 要原样落库、也原样返回给前端做中文映射；
 *   枚举会多一层 code↔name 的转换，改错一处就出现"库里 PUBLISH、前端显示 undefined"。
 * ★ 新增类型时**必须**同步前端 WalletManage/MyWallet 的中文映射与审计文档，
 *   否则列表里会出现一串英文。
 */
public final class WalletTxType {

    /** 注册赠送（迁移补发与注册赠送共用，幂等键 signup:{userId}） */
    public static final String SIGNUP_BONUS = "SIGNUP_BONUS";

    /** 模拟充值（用户自助，演示用，不做真实支付） */
    public static final String RECHARGE = "RECHARGE";

    /** 发布问卷扣费（幂等键 publish:{surveyId}，一份问卷只扣一次） */
    public static final String PUBLISH = "PUBLISH";

    /** AI 生成问卷扣费（预扣 + 失败退回；幂等键 ai:{uuid}） */
    public static final String AI_GENERATE = "AI_GENERATE";

    /** 导出答卷扣费（进阶功能，每次导出 1 点；幂等键 export:{surveyId}:{userId}:{uuid}） */
    public static final String EXPORT = "EXPORT";

    /** 高级设置包扣费（密码/限量/公开结果，每份问卷一次性；幂等键 advanced:{surveyId}） */
    public static final String ADVANCED_SETTINGS = "ADVANCED_SETTINGS";

    /** 协作者名额扣费（前 2 名免费；幂等键 collab:{surveyId}:{targetUserId}） */
    public static final String COLLABORATOR = "COLLABORATOR";

    /** 移入回收站退回（幂等键 refund:{surveyId}，一份问卷只退一次） */
    public static final String REFUND = "REFUND";

    /** 管理员调整（补偿 / 修正，amount 可正可负） */
    public static final String ADMIN_ADJUST = "ADMIN_ADJUST";

    /** 全部类型（不可变；测试里用它钉住"没有漏登记的新类型"） */
    public static final Set<String> ALL = Set.of(
            SIGNUP_BONUS, RECHARGE, PUBLISH, AI_GENERATE, EXPORT,
            ADVANCED_SETTINGS, COLLABORATOR, REFUND, ADMIN_ADJUST);

    private WalletTxType() {
    }
}
