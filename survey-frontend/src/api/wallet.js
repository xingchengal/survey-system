/**
 * 额度钱包接口（后端 WalletController，基础路径 /api/wallet）。
 * ★ 充值 / 调整都必须带 idempotencyKey（后端幂等键），重复提交只到账一次；
 *   金额单位是整数"点"；余额与流水只按当前用户查，没有查别人的参数。
 */
import http from './http'

export default {
  /** 我的钱包（余额） */
  my: () => http.get('/api/wallet'),

  /** 我的流水（分页）：params = { pageNum, pageSize } */
  transactions: (params) => http.get('/api/wallet/transactions', params),

  /** 计费项目录（价格与说明）。★ 价格唯一出处是后端 common/WalletFeature，前端不要硬编码 */
  pricing: () => http.get('/api/wallet/pricing'),

  /** 模拟充值：amount 1~1000 点；idempotencyKey 由前端生成 */
  recharge: (amount, idempotencyKey) =>
    http.post('/api/wallet/recharge', { amount, idempotencyKey }),

  // ===== 管理端 =====

  /** 用户钱包列表（管理员，分页）：params = { pageNum, pageSize, keyword } */
  page: (params) => http.get('/api/wallet/admin/page', params),

  /** 调整额度（管理员）：{ userId, amount, reason, idempotencyKey }；amount 正加负扣 */
  adjust: (data) => http.post('/api/wallet/admin/adjust', data),

  /** 对账：返回 { walletCount, mismatchCount, mismatches[] } */
  reconcile: () => http.get('/api/wallet/admin/reconcile')
}
