<!--
  钱包管理（管理员，H5）。

  ── 三个作用 ──────────────────────────────────────────
  1. 看全站用户余额（可疑的"余额特别高/特别低"一眼可见）；
  2. 手动调整（补偿、修正；amount 为正加、为负扣，必须带幂等键）；
  3. 一键对账 —— 口径只有一条：SUM(流水金额) 必须等于余额，不等就列出来。

  ★ 对账只报告、不自动"修"：自动修会把错误藏起来，应该由人确认后再调整。
-->
<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="page-title">钱包管理</h2>
        <p class="page-subtitle">用户额度、手动调整与对账（模拟付费，不接真实支付）</p>
      </div>
      <div class="header-actions">
        <el-button @click="runReconcile" :loading="reconciling">对账</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert
      class="mb-16"
      type="info"
      :closable="false"
      show-icon
      title="对账口径：每个用户的「流水金额之和」必须等于钱包余额。不一致会列出来，但不会自动修改 —— 确认原因后再用「调整」补账。"
    />

    <div class="toolbar-card">
      <el-input
        v-model="keyword"
        placeholder="搜索用户名或昵称..."
        clearable
        style="width: 260px;"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-button type="primary" @click="handleSearch">搜索</el-button>
    </div>

    <div class="table-card">
      <el-table v-loading="loading" :data="rows" empty-text="没有钱包数据">
        <el-table-column prop="userId" label="用户 ID" width="90" align="center" />
        <el-table-column label="用户" min-width="180">
          <template #default="{ row }">{{ row.nickname || row.username || '—' }}<span class="muted">（{{ row.username || '—' }}）</span></template>
        </el-table-column>
        <el-table-column label="余额" width="120" align="right">
          <template #default="{ row }">
            <span class="balance">{{ row.balance }} 点</span>
          </template>
        </el-table-column>
        <el-table-column prop="updatedTime" label="更新时间" width="180" />
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openAdjust(row)">调整额度</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager-row">
        <el-pagination
          :current-page="pageNum"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          background
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </div>

    <!-- 调整额度 -->
    <el-dialog v-model="adjustVisible" title="调整额度" width="460px">
      <p class="text-secondary" style="margin-top: 0;">
        用户：{{ adjustForm.username || adjustForm.userId }}（当前余额 {{ adjustForm.balance }} 点）
      </p>
      <el-form label-width="90px">
        <el-form-item label="调整点数" required>
          <el-input-number v-model="adjustForm.amount" :min="-100000" :max="100000" :step="10" style="width: 180px;" />
          <span class="form-hint">正数加、负数扣</span>
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="adjustForm.reason" maxlength="100" show-word-limit placeholder="例如：对账补偿 / 活动赠送" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjusting" @click="submitAdjust">确认调整</el-button>
      </template>
    </el-dialog>

    <!-- 对账结果 -->
    <el-dialog v-model="reconcileVisible" title="对账结果" width="680px">
      <div class="reconcile-summary">
        <div>钱包总数：<b>{{ reconcileResult.walletCount }}</b></div>
        <div :class="reconcileResult.mismatchCount > 0 ? 'bad' : 'good'">
          不一致：<b>{{ reconcileResult.mismatchCount }}</b>
          <span v-if="reconcileResult.mismatchCount === 0">（账目一致 ✅）</span>
        </div>
      </div>
      <el-table v-if="reconcileResult.mismatches && reconcileResult.mismatches.length" :data="reconcileResult.mismatches" size="small" max-height="320">
        <el-table-column prop="userId" label="用户 ID" width="90" />
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="balance" label="钱包余额" width="110" align="right" />
        <el-table-column prop="ledger" label="流水汇总" width="110" align="right" />
        <el-table-column label="差额" width="100" align="right">
          <template #default="{ row }">
            <span class="bad">{{ row.balance - row.ledger }}</span>
          </template>
        </el-table-column>
      </el-table>
      <p v-else class="muted" style="margin: 8px 0 0;">没有不一致的钱包。</p>
      <template #footer>
        <el-button type="primary" @click="reconcileVisible = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import walletApi from '../api/wallet'

function newIdempotencyKey() {
  const rand = Math.random().toString(36).slice(2, 10)
  return `adjust:${Date.now()}-${rand}`
}

export default {
  name: 'WalletManage',
  data() {
    return {
      keyword: '',
      rows: [],
      total: 0,
      pageNum: 1,
      pageSize: 10,
      loading: false,
      adjustVisible: false,
      adjusting: false,
      adjustForm: {
        userId: null,
        username: '',
        balance: 0,
        amount: 10,
        reason: '',
        idempotencyKey: ''
      },
      reconciling: false,
      reconcileVisible: false,
      reconcileResult: { walletCount: 0, mismatchCount: 0, mismatches: [] }
    }
  },
  mounted() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const page = await walletApi.page({
          pageNum: this.pageNum,
          pageSize: this.pageSize,
          keyword: this.keyword.trim()
        })
        this.rows = page.list || []
        this.total = page.total || 0
      } catch {
        this.rows = []
      } finally {
        this.loading = false
      }
    },
    handleSearch() {
      this.pageNum = 1
      this.load()
    },
    handlePageChange(page) {
      this.pageNum = page
      this.load()
    },
    handleSizeChange(size) {
      this.pageSize = size
      this.pageNum = 1
      this.load()
    },
    openAdjust(row) {
      this.adjustForm = {
        userId: row.userId,
        username: row.nickname || row.username,
        balance: row.balance,
        amount: 10,
        reason: '',
        // 每次打开弹窗生成一个幂等键：同一个弹窗里重试不会重复调整
        idempotencyKey: newIdempotencyKey()
      }
      this.adjustVisible = true
    },
    async submitAdjust() {
      if (!this.adjustForm.amount) {
        ElMessage.warning('调整点数不能为 0')
        return
      }
      this.adjusting = true
      try {
        await walletApi.adjust({
          userId: this.adjustForm.userId,
          amount: this.adjustForm.amount,
          reason: this.adjustForm.reason.trim(),
          idempotencyKey: this.adjustForm.idempotencyKey
        })
        ElMessage.success(`已调整 ${this.adjustForm.amount} 点`)
        this.adjustVisible = false
        this.load()
      } catch {
        // 失败提示由接口层统一弹出
      } finally {
        this.adjusting = false
      }
    },
    async runReconcile() {
      this.reconciling = true
      try {
        this.reconcileResult = await walletApi.reconcile()
        this.reconcileVisible = true
      } catch {
        // 失败提示由接口层统一弹出
      } finally {
        this.reconciling = false
      }
    }
  }
}
</script>

<style scoped>
.balance { font-weight: 600; color: #1d4ed8; }
.muted { color: var(--ink-3); font-size: 12px; }
.form-hint { margin-left: 10px; font-size: 12px; color: var(--ink-3); }
.reconcile-summary {
  display: flex;
  gap: 28px;
  margin-bottom: 14px;
  font-size: 14px;
}
.reconcile-summary .good { color: #16a34a; }
.reconcile-summary .bad { color: #dc2626; }
</style>
