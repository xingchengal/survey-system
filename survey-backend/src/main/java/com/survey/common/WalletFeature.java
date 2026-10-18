package com.survey.common;

/**
 * 计费项目录 —— **全项目唯一的价格出处**（后端扣费与前端展示共用）。
 *
 * ── 为什么要有这层，而不是到处写数字 ───────────────────
 *   计费点会越加越多（发布、AI、导出、高级设置、协作者名额，以后还有新题型包）。
 *   价格如果散落在 WalletService 常量、Controller 文案、前端硬编码三处，
 *   改一次价就要改三处，而且必然漏一处。现在：
 *     · 后端扣费用 {@code WalletFeature.XXX.getCost()}；
 *     · 前端「计费规则」由 {@code GET /api/wallet/pricing} 动态渲染；
 *     · 文档里引用本枚举的 label/cost。
 *   ★ 新增计费项 = 这里加一个枚举 + 在对应写入口调用一次 WalletService 的 charge 方法，
 *     不需要改表、也不需要改前端的价格文案。
 *
 * ── 计费模式 ──────────────────────────────────────────
 *   ONCE_PER_SURVEY  ：每份问卷只扣一次（发布、高级设置包）
 *   PER_REQUEST      ：每次操作都扣（AI 生成、导出）
 *   PER_COLLABORATOR ：按"人"扣，同一份问卷 + 同一个人只扣一次（协作者名额）
 *
 * 关联：service/WalletService（扣费实现）、controller/WalletController（pricing 接口）、
 *      前端 MyWallet.vue（动态渲染计费规则）
 */
public enum WalletFeature {

    /** 发布问卷：基础功能，1 点/份（每份问卷终身只扣一次） */
    PUBLISH("publish", "发布问卷", 1, "每份问卷只扣一次；移入回收站退回 1 点", BillingMode.ONCE_PER_SURVEY),

    /** AI 创建：进阶功能，3 点/次（失败自动退回） */
    AI_SURVEY("ai", "AI 创建问卷", 3, "生成失败自动退回，净扣 0", BillingMode.PER_REQUEST),

    /** 导出答卷：进阶功能，1 点/次（管理员免费） */
    EXPORT("export", "导出答卷", 1, "每次导出 1 点，管理员免费", BillingMode.PER_REQUEST),

    /** 高级设置包：进阶功能，1 点/份（解锁答题密码 / 限收份数 / 公开结果） */
    ADVANCED_SETTINGS("advanced", "高级设置包", 1,
            "解锁答题密码、限收份数、公开结果；每份问卷只扣一次", BillingMode.ONCE_PER_SURVEY),

    /** 协作者名额：进阶功能，前 2 名免费，之后 1 点/人（同一人只扣一次） */
    COLLABORATOR("collaborator", "协作者名额", 1,
            "每份问卷前 2 名协作者免费，之后每名 1 点（同一人只扣一次）", BillingMode.PER_COLLABORATOR);

    /** 每份问卷免费的协作者名额（名额收费从第 3 名开始） */
    public static final int FREE_COLLABORATORS = 2;

    /** 计费模式（只用于展示与文档；具体幂等键由 WalletService 生成） */
    public enum BillingMode {
        ONCE_PER_SURVEY,
        PER_REQUEST,
        PER_COLLABORATOR
    }

    private final String code;
    private final String label;
    private final int cost;
    private final String description;
    private final BillingMode mode;

    WalletFeature(String code, String label, int cost, String description, BillingMode mode) {
        this.code = code;
        this.label = label;
        this.cost = cost;
        this.description = description;
        this.mode = mode;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public int getCost() { return cost; }
    public String getDescription() { return description; }
    public BillingMode getMode() { return mode; }
}
