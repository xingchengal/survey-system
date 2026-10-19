package com.survey.common;

/**
 * 计费项目录 —— **全项目唯一的价格出处**（后端扣费与前端展示共用）。
 * ★ 新增计费项 = 这里加一个枚举 + 在对应写入口调一次 WalletService.charge，不改表、不改前端价格文案。
 * 前端「计费规则」由 GET /api/wallet/pricing 动态渲染。
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
            "每份问卷前 2 名协作者免费，之后每名 1 点（同一人只扣一次）", BillingMode.PER_COLLABORATOR),

    /**
     * 进阶题型包：矩阵题 / 排序题 / NPS，2 点/份（每份问卷只扣一次）。
     * ★ 触发点是"保存题目"：问卷里出现这三种题型中的任意一种就扣一次；
     *   幂等键 advanced-types:{surveyId}，之后加题/删题/重复保存都不再扣。
     * ★ 为什么按"份"而不是按"题"：作者很难预估"加一道矩阵要多少钱"，
     *   按份买断更符合"解锁一种能力"的直觉，也和高级设置包的计价方式一致。
     */
    ADVANCED_TYPES("advanced-types", "进阶题型包（矩阵/排序/NPS）", 2,
            "矩阵题、排序题、NPS 任一种首次保存时扣 2 点；每份问卷只扣一次", BillingMode.ONCE_PER_SURVEY);

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
