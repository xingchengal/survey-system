package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 额度流水（t_wallet_transaction）—— **只追加、不修改**的账本。
 *
 * ★ amount 有符号：入账为正、出账为负。因此对账只有一条等式：
 *     SUM(amount) = t_wallet.balance
 * ★ balance_after 是本笔之后的余额快照。它对"对账"不是必需的（SUM 就能对），
 *   但对排查有用：一眼就能看出是哪一笔把余额写歪的，不用回放整串流水。
 * ★ idempotencyKey 全表唯一：同一笔业务重复请求只落一条流水。
 */
public class WalletTransaction {

    private Long id;
    private Integer userId;
    /** 取值见 common/WalletTxType（SIGNUP_BONUS / RECHARGE / PUBLISH / REFUND / ADMIN_ADJUST） */
    private String type;
    /** 有符号：入账为正、出账为负 */
    private Integer amount;
    private Integer balanceAfter;
    /** 关联业务 id（如 surveyId），可为 null */
    private String bizId;
    private String idempotencyKey;
    private String remark;
    /** ★ 必须显式格式化：Jackson 对 LocalDateTime 默认输出 ISO（带 T），前端要的是"YYYY-MM-DD HH:mm:ss" */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getAmount() { return amount; }
    public void setAmount(Integer amount) { this.amount = amount; }

    public Integer getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(Integer balanceAfter) { this.balanceAfter = balanceAfter; }

    public String getBizId() { return bizId; }
    public void setBizId(String bizId) { this.bizId = bizId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
}
