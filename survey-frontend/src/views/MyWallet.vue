<!--
  我的钱包：顶部余额、流水（amount 有符号，支出为负）、模拟充值。
  ★ 充值 1~1000 点，提交必须带幂等键：每次打开弹窗生成一个新 key，连点/重试只到账一次。
-->
<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="page-title">我的钱包</h2>
        <p class="page-subtitle">发布一份问卷消耗 1 点 · 移入回收站退回 1 点 · 注册赠送 10 点</p>
      </div>
      <el-button type="primary" @click="openRecharge">模拟充值</el-button>
    </div>

    <div class="wallet-cards">
      <div class="wallet-card balance-card">
        <div class="card-label">可用额度</div>
        <div class="card-value">{{ balance }}<span class="unit">点</span></div>
        <div class="card-hint">额度不足时发布会失败，并提示你去充值</div>
      </div>
      <div class="wallet-card">
        <div class="card-label">计费规则</div>
        <ul class="rule-list">
          <li v-for="p in pricing" :key="p.code">
            {{ p.label }}：<b>{{ p.cost }} 点</b>
            <span class="rule-desc">{{ p.description }}</span>
          </li>
          <li v-if="pricing.length === 0" class="rule-desc">加载中…</li>
        </ul>
      </div>
      <div class="wallet-card">
        <div class="card-label">说明</div>
        <p class="card-hint" style="margin-top: 8px;">
          这是「模拟付费」：充值只增加额度，不涉及任何真实支付。
          对账口径为「流水金额之和 = 余额」，管理员可随时核对。
        </p>
      </div>
    </div>

    <div class="table-card">
      <el-table v-loading="loading" :data="rows" empty-text="还没有额度流水">
        <el-table-column prop="createdTime" label="时间" width="180" />
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="typeTag(row.type)">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <!-- 金额有符号：正数显示 +，支出按负数展示 -->
        <el-table-column label="变动" width="110" align="right">
          <template #default="{ row }">
            <span :class="row.amount > 0 ? 'amount-in' : 'amount-out'">
              {{ row.amount > 0 ? '+' : '' }}{{ row.amount }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="balanceAfter" label="余额" width="100" align="right" />
        <el-table-column prop="remark" label="说明" min-width="260" show-overflow-tooltip />
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

    <!-- 模拟充值 -->
    <el-dialog v-model="rechargeVisible" title="模拟充值" width="460px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="演示环境：这里不会产生真实支付，点击确认即到账"
        class="mb-16"
      />
      <p class="recharge-label">选择充值点数</p>
      <el-radio-group v-model="rechargeAmount">
        <el-radio-button :value="10">10 点</el-radio-button>
        <el-radio-button :value="30">30 点</el-radio-button>
        <el-radio-button :value="50">50 点</el-radio-button>
        <el-radio-button :value="100">100 点</el-radio-button>
      </el-radio-group>
      <p class="recharge-label" style="margin-top: 14px;">或自定义（1 ~ 1000）</p>
      <el-input-number v-model="rechargeAmount" :min="1" :max="1000" :step="10" style="width: 180px;" />
      <template #footer>
        <el-button @click="rechargeVisible = false">取消</el-button>
        <el-button type="primary" :loading="recharging" @click="submitRecharge">确认充值</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import walletApi from '../api/wallet'

/** 类型 → 中文（与后端 common/WalletTxType 一一对应） */
const TYPE_LABELS = {
  SIGNUP_BONUS: '注册赠送',
  RECHARGE: '模拟充值',
  PUBLISH: '发布扣费',
  AI_GENERATE: 'AI 创建',
  EXPORT: '导出答卷',
  ADVANCED_SETTINGS: '高级设置包',
  COLLABORATOR: '协作者名额',
  ADVANCED_TYPES: '进阶题型包',
  REFUND: '退回',
  ADMIN_ADJUST: '管理员调整'
}

const TYPE_TAGS = {
  SIGNUP_BONUS: 'success',
  RECHARGE: 'primary',
  PUBLISH: 'warning',
  AI_GENERATE: 'danger',
  EXPORT: 'warning',
  ADVANCED_SETTINGS: 'warning',
  COLLABORATOR: 'warning',
  ADVANCED_TYPES: 'danger',
  REFUND: 'success',
  ADMIN_ADJUST: 'info'
}

/** 生成幂等键：同一操作重试时复用，避免重复到账 */
function newIdempotencyKey(prefix) {
  const rand = Math.random().toString(36).slice(2, 10)
  return `${prefix}:${Date.now()}-${rand}`
}

export default {
  name: 'MyWallet',
  data() {
    return {
      balance: 0,
      rows: [],
      total: 0,
      pageNum: 1,
      pageSize: 10,
      loading: false,
      /** 计费项目录（后端 WalletFeature 下发，前端不硬编码价格） */
      pricing: [],
      rechargeVisible: false,
      rechargeAmount: 30,
      recharging: false,
      /** 本次充值弹窗的幂等键（打开弹窗时生成；重试复用同一个） */
      rechargeKey: ''
    }
  },
  mounted() {
    this.loadPricing()
    this.load().then(() => this.loadTransactions())
  },
  methods: {
    async loadPricing() {
      try {
        this.pricing = await walletApi.pricing()
      } catch {
        // 失败提示由接口层统一弹出；规则区显示"加载中…"不影响余额/流水
        this.pricing = []
      }
    },
    async load() {
      try {
        const wallet = await walletApi.my()
        this.balance = wallet && wallet.balance != null ? wallet.balance : 0
      } catch {
        // 失败提示由接口层统一弹出
      }
    },
    async loadTransactions() {
      this.loading = true
      try {
        const page = await walletApi.transactions({ pageNum: this.pageNum, pageSize: this.pageSize })
        this.rows = page.list || []
        this.total = page.total || 0
      } catch {
        this.rows = []
      } finally {
        this.loading = false
      }
    },
    handlePageChange(page) {
      this.pageNum = page
      this.loadTransactions()
    },
    handleSizeChange(size) {
      this.pageSize = size
      this.pageNum = 1
      this.loadTransactions()
    },
    typeLabel(type) {
      return TYPE_LABELS[type] || type
    },
    typeTag(type) {
      return TYPE_TAGS[type] || 'info'
    },
    openRecharge() {
      this.rechargeAmount = 30
      this.rechargeKey = newIdempotencyKey('recharge')
      this.rechargeVisible = true
    },
    async submitRecharge() {
      if (!this.rechargeAmount || this.rechargeAmount < 1 || this.rechargeAmount > 1000) {
        ElMessage.warning('充值点数必须在 1 ~ 1000 之间')
        return
      }
      this.recharging = true
      try {
        await walletApi.recharge(this.rechargeAmount, this.rechargeKey)
        ElMessage.success(`已充值 ${this.rechargeAmount} 点`)
        this.rechargeVisible = false
        this.pageNum = 1
        await this.load()
        await this.loadTransactions()
      } catch {
        // 失败提示由接口层统一弹出
      } finally {
        this.recharging = false
      }
    }
  }
}
</script>

<style scoped>
.wallet-cards {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
.wallet-card {
  padding: 18px 20px;
  background: #fff;
  border: 1px solid var(--stroke);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-sm);
}
.balance-card {
  background: linear-gradient(140deg, #eff6ff, #eef2ff);
  border-color: #dbeafe;
}
.card-label {
  font-size: 12px;
  color: var(--ink-3);
  margin-bottom: 8px;
}
.card-value {
  font-size: 34px;
  font-weight: 700;
  color: #1d4ed8;
  line-height: 1.1;
}
.card-value .unit {
  font-size: 14px;
  margin-left: 6px;
  color: var(--ink-3);
  font-weight: 400;
}
.card-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.7;
}
.rule-list {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--ink-2);
}
.rule-desc {
  display: block;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.6;
}
.amount-in { color: #16a34a; font-weight: 600; }
.amount-out { color: #dc2626; font-weight: 600; }
.pager-row {
  display: flex;
  justify-content: flex-end;
  padding: 14px 4px 2px;
}
.recharge-label {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--ink-2);
}
</style>
