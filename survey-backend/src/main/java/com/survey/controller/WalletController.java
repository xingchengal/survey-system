package com.survey.controller;

import com.survey.annotation.OperationLog;
import com.survey.annotation.RequirePermission;
import com.survey.common.PageResult;
import com.survey.common.Permission;
import com.survey.dto.WalletAdjustRequest;
import com.survey.dto.WalletRechargeRequest;
import com.survey.entity.Wallet;
import com.survey.entity.WalletTransaction;
import com.survey.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 额度钱包（模拟付费）：余额、流水、计费目录、充值与管理员调整/对账。
 * 用户侧只按 X-User-Id，没有任何"查别人钱包"的入口；管理侧按 wallet:manage。
 * 充值/调整必须带幂等键，重复提交不会重复到账；不做真实支付，流水里写明"演示用"。
 */
@Tag(name = "额度钱包", description = "余额、流水、模拟充值，以及管理端调整与对账")
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    @Autowired
    private WalletService walletService;

    @Operation(summary = "我的钱包（余额）")
    @GetMapping
    public Wallet myWallet(@RequestHeader("X-User-Id") Integer userId) {
        return walletService.getOrCreate(userId);
    }

    @Operation(summary = "我的额度流水（分页）")
    @GetMapping("/transactions")
    public PageResult<WalletTransaction> transactions(
            @RequestHeader("X-User-Id") Integer userId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return walletService.listTransactions(userId, pageNum, pageSize);
    }

    /**
     * 计费项目录：价格唯一出处是 common/WalletFeature，前端据此渲染，新增计费项无需改前端。
     */
    @Operation(summary = "计费项目录（价格与说明）")
    @GetMapping("/pricing")
    public List<Map<String, Object>> pricing() {
        return walletService.pricing();
    }

    @OperationLog("模拟充值")
    @Operation(summary = "模拟充值（演示用，不接真实支付）")
    @PostMapping("/recharge")
    public WalletTransaction recharge(@Valid @RequestBody WalletRechargeRequest req,
                                      @RequestHeader("X-User-Id") Integer userId) {
        return walletService.recharge(userId, req.getAmount(), req.getIdempotencyKey());
    }

    // ==================== 管理员 ====================

    @RequirePermission(Permission.WALLET_MANAGE)
    @Operation(summary = "用户钱包列表（管理员，分页）")
    @GetMapping("/admin/page")
    public PageResult<Map<String, Object>> pageWallets(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        return walletService.pageWallets(keyword, pageNum, pageSize);
    }

    @OperationLog("调整用户额度")
    @RequirePermission(Permission.WALLET_MANAGE)
    @Operation(summary = "调整用户额度（管理员；amount 为正加、为负扣）")
    @PostMapping("/admin/adjust")
    public WalletTransaction adjust(@Valid @RequestBody WalletAdjustRequest req) {
        return walletService.adjust(req.getUserId(), req.getAmount(), req.getReason(),
                req.getIdempotencyKey());
    }

    @RequirePermission(Permission.WALLET_MANAGE)
    @Operation(summary = "对账：余额与流水汇总不一致的钱包（管理员）")
    @GetMapping("/admin/reconcile")
    public Map<String, Object> reconcile() {
        return walletService.reconcile();
    }
}
