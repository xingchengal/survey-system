/**
 * 额度钱包接口（后端 WalletController，基础路径 /api/wallet）。
 *
 * ★ 约定：
 *   · 充值/调整都必须带 **idempotencyKey**：它是后端的幂等键，
 *     重复提交（连点、网络重试）只会到账一次；
 *   · 金额单位是"点"（整数），不做真实支付；
 *   · 我的钱包/流水只按 token 里的用户查，**没有任何"查别人"的参数**。
 */
import http from './http'

export default {
  /** 我的钱包（余额） */
  my: () => http.get('/api/wallet'),

  /** 我的流水（分页）：params = { pageNum, pageSize } */
  transactions: (params) => http.get('/api/wallet/transactions', params),

  /**
   * 计费项目录（价格与说明）。
   * ★ 价格唯一出处是后端 common/WalletFeature；前端不要硬编码数字，
   *   以后新增计费项（新的进阶题型包等）页面会自动出现。
   */
  pricing: () => http.get('/api/wallet/pricing'),

  /** 模拟充值：amount 1~1000 点；idempotencyKey 由前端生成 */
  recharge: (amount, idempotencyKey) =>
    http.post('/api/wallet/recharge', { amount, idempotencyKey }),

  // ===== 管理端 =====

  /** 用户钱包列表（分页）：params = { pageNum, pageSize, keyword } */
  page: (params) => http.get('/api/wallet/admin/page', params),

  /** 调整额度：{ userId, amount, reason, idempotencyKey }（amount 为正加、为负扣） */
  adjust: (data) => http.post('/api/wallet/admin/adjust', data),

  /** 对账：返回 { walletCount, mismatchCount, mismatches[] } */
  reconcile: () => http.get('/api/wallet/admin/reconcile')
}
