package com.survey.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理员调整额度请求。
 * ★ amount 有符号：正数加、负数扣，为 0 由 Service 拒绝；必须带 idempotencyKey，重复提交只生效一次。
 * reason 可选，只进流水备注。
 */
public class WalletAdjustRequest {

    @NotNull(message = "用户 id 不能为空")
    private Integer userId;

    @NotNull(message = "调整点数不能为空")
    @Min(value = -100000, message = "单次调整不能超过 100000 点")
    @Max(value = 100000, message = "单次调整不能超过 100000 点")
    private Integer amount;

    @Size(max = 100, message = "原因最多 100 字")
    private String reason;

    @NotBlank(message = "缺少幂等键")
    @Size(max = 128, message = "幂等键最多 128 字符")
    private String idempotencyKey;

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public Integer getAmount() { return amount; }
    public void setAmount(Integer amount) { this.amount = amount; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
