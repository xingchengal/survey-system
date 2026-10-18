package com.survey.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 模拟充值请求（演示用，不接真实支付）。
 *
 * ★ idempotencyKey 必填：前端每次点击生成一个 UUID，重复提交（网络重试、连点）
 *   只到账一次。服务端把它落成 t_wallet_transaction 的唯一键。
 * ★ amount 上限与 WalletService.RECHARGE_MAX 对齐（1~1000 点），
 *   避免误点/脚本把余额刷成天文数字。
 */
public class WalletRechargeRequest {

    @NotNull(message = "充值点数不能为空")
    @Min(value = 1, message = "单次充值至少 1 点")
    @Max(value = 1000, message = "单次充值最多 1000 点")
    private Integer amount;

    @NotBlank(message = "缺少幂等键")
    @Size(max = 128, message = "幂等键最多 128 字符")
    private String idempotencyKey;

    public Integer getAmount() { return amount; }
    public void setAmount(Integer amount) { this.amount = amount; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
