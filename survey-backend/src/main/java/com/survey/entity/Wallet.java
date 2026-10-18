package com.survey.entity;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 额度钱包（t_wallet）—— 一人一包，balance 是**可用点数**。
 *
 * ★ 为什么余额不放在 t_user 上：
 *   钱包的读写频率、并发要求与用户资料完全不同；拆表后扣减只锁这一行，
 *   不会与"改昵称/头像"这类高频用户写操作互相阻塞。
 * ★ balance 的单位是"点"（整数）：发布一份问卷扣 1 点，退款/充值也是整数。
 *   不做真实货币（不接支付渠道），所以不需要分/元换算与金额精度问题。
 */
public class Wallet {

    private Integer userId;
    private Integer balance;
    private Integer frozen;
    private Integer version;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedTime;

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public Integer getBalance() { return balance; }
    public void setBalance(Integer balance) { this.balance = balance; }

    public Integer getFrozen() { return frozen; }
    public void setFrozen(Integer frozen) { this.frozen = frozen; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }

    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
}
