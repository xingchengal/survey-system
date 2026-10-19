<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">系统统计</h2>
        <p class="page-subtitle">按模块查看全站分布、趋势与资源占用；没有权限的模块不显示</p>
      </div>
      <el-tag v-if="isSubAdmin" type="warning" effect="light" round>次级管理员</el-tag>
    </div>

    <el-skeleton v-if="!loaded && !failed" :rows="6" animated />
    <el-empty v-else-if="failed" description="统计加载失败，请刷新重试" />
    <el-empty v-else-if="!hasAnyModule" description="还没有被分配任何模块，请联系超级管理员授权" />

    <el-tabs v-else v-model="activeTab" class="stat-tabs">
      <!-- ==================== 问卷与答卷 ==================== -->
      <el-tab-pane v-if="has('survey')" label="问卷与答卷" name="survey">
        <div v-if="activeTab === 'survey'">
          <el-row :gutter="16">
            <el-col v-for="card in surveyCards" :key="card.label" :span="4" :xs="12" :sm="8" :md="4">
              <el-card shadow="hover" class="stat-card">
                <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
                  <el-icon :size="22"><component :is="card.icon" /></el-icon>
                </div>
                <div class="stat-content">
                  <div class="stat-label">{{ card.label }}</div>
                  <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
                  <div v-if="card.sub" class="stat-sub">{{ card.sub }}</div>
                </div>
              </el-card>
            </el-col>
          </el-row>

          <el-row class="mt-20 equal-height-row" :gutter="20">
            <el-col :span="10" :xs="24" :sm="24" :md="10">
              <el-card>
                <template #header>
                  <div class="flex-between">
                    <span class="text-bold">📊 问卷状态分布</span>
                    <el-tag size="small" type="info">共 {{ survey.total }} 份</el-tag>
                  </div>
                </template>
                <div v-if="survey.total > 0" ref="pieRef" class="chart-md"></div>
                <el-empty v-else description="暂无问卷数据" :image-size="80" />
              </el-card>
            </el-col>

            <el-col :span="14" :xs="24" :sm="24" :md="14">
              <el-card>
                <template #header>
                  <div class="flex-between">
                    <span class="text-bold">📈 近 7 天趋势</span>
                    <el-tag size="small" type="success">新建 {{ sum(survey.trendSurveys) }} 份 / 答卷 {{ sum(survey.trendAnswers) }} 份</el-tag>
                  </div>
                </template>
                <div ref="trendRef" class="chart-md"></div>
              </el-card>
            </el-col>
          </el-row>

          <el-row class="mt-20 equal-height-row" :gutter="20">
            <el-col :span="12" :xs="24" :sm="12">
              <el-card>
                <template #header>
                  <div class="flex-between">
                    <span class="text-bold">📦 答卷量分布</span>
                    <el-tag size="small" type="info">共 {{ survey.total }} 份问卷</el-tag>
                  </div>
                </template>
                <div ref="bucketRef" class="chart-md"></div>
              </el-card>
            </el-col>

            <el-col :span="12" :xs="24" :sm="12">
              <el-card>
                <template #header>
                  <div class="flex-between">
                    <span class="text-bold">🧩 问卷构成（模板 / 普通）</span>
                    <el-tag size="small" type="warning">模板 {{ survey.templates }} 份</el-tag>
                  </div>
                </template>
                <div ref="typeRef" class="chart-md"></div>
              </el-card>
            </el-col>
          </el-row>

          <el-row class="mt-20 equal-height-row" :gutter="20">
            <el-col :span="15" :xs="24" :sm="24" :md="15">
              <el-card>
                <template #header>
                  <div class="flex-between">
                    <span class="text-bold">🏆 问卷回收量排行 TOP 5</span>
                    <el-tag size="small" type="warning">按答卷数排序</el-tag>
                  </div>
                </template>
                <el-table class="w-full" :data="survey.topSurveys || []" stripe>
                  <el-table-column label="排名" width="70" align="center">
                    <template #default="{ $index }">
                      <span :class="['rank-badge', $index < 3 ? 'rank-top' : '']">{{ $index + 1 }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="title" label="问卷标题" min-width="180" show-overflow-tooltip />
                  <el-table-column label="状态" width="90" align="center">
                    <template #default="{ row }">
                      <el-tag v-if="row.status === 1" type="success" size="small">已发布</el-tag>
                      <el-tag v-else-if="row.status === 2" type="danger" size="small">已结束</el-tag>
                      <el-tag v-else type="info" size="small">草稿</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="答卷数" width="180" align="center">
                    <template #default="{ row }">
                      <div class="flex-center">
                        <el-progress
                          :percentage="Math.min(100, (row.responses / maxTopResponse) * 100)"
                          :stroke-width="10"
                          :show-text="false"
                          style="flex: 1; margin-right: 8px;"
                          :color="getProgressColor(row.status)"
                        />
                        <span class="response-num">{{ row.responses }}</span>
                      </div>
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-if="!survey.topSurveys || survey.topSurveys.length === 0" description="暂无数据" :image-size="60" />
              </el-card>
            </el-col>

            <el-col :span="9" :xs="24" :sm="24" :md="9">
              <el-card>
                <template #header>
                  <span class="text-bold">⚙️ 系统概况</span>
                </template>
                <div ref="gaugeRef" class="gauge-chart"></div>
                <div class="metric-list">
                  <div class="metric-row">
                    <span class="metric-name">答卷回收率</span>
                    <span class="metric-value">{{ survey.responseRate }}%</span>
                  </div>
                  <div class="metric-row">
                    <span class="metric-name">平均每份问卷答卷</span>
                    <span class="metric-value">{{ survey.avgResponses }} 份</span>
                  </div>
                  <div class="metric-row">
                    <span class="metric-name">发布率</span>
                    <span class="metric-value">{{ publishRate }}%</span>
                  </div>
                  <div class="metric-row">
                    <span class="metric-name">访客转化率</span>
                    <span class="metric-value">{{ viewConvertRate }}%</span>
                  </div>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </div>
      </el-tab-pane>

      <!-- ==================== 用户 ==================== -->
      <el-tab-pane v-if="has('user')" label="用户" name="user">
        <div v-if="activeTab === 'user'">
          <el-row :gutter="16">
            <el-col v-for="card in userCards" :key="card.label" :span="6" :xs="12" :sm="12" :md="6">
              <el-card shadow="hover" class="stat-card">
                <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
                  <el-icon :size="22"><component :is="card.icon" /></el-icon>
                </div>
                <div class="stat-content">
                  <div class="stat-label">{{ card.label }}</div>
                  <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
                  <div v-if="card.sub" class="stat-sub">{{ card.sub }}</div>
                </div>
              </el-card>
            </el-col>
          </el-row>

          <el-row class="mt-20 equal-height-row" :gutter="20">
            <el-col :span="14" :xs="24" :sm="24" :md="14">
              <el-card>
                <template #header>
                  <div class="flex-between">
                    <span class="text-bold">📈 近 7 天注册趋势</span>
                    <el-tag size="small" type="success">7 天新增 {{ sum(user.trendNew) }} 人</el-tag>
                  </div>
                </template>
                <div ref="userTrendRef" class="chart-md"></div>
              </el-card>
            </el-col>

            <el-col :span="10" :xs="24" :sm="24" :md="10">
              <el-card>
                <template #header>
                  <span class="text-bold">🧩 角色构成</span>
                </template>
                <div ref="userRoleRef" class="chart-md"></div>
              </el-card>
            </el-col>
          </el-row>
        </div>
      </el-tab-pane>

      <!-- ==================== 模板 ==================== -->
      <el-tab-pane v-if="has('template')" label="模板" name="template">
        <div v-if="activeTab === 'template'">
          <el-row :gutter="16">
            <el-col v-for="card in templateCards" :key="card.label" :span="6" :xs="12" :sm="12" :md="6">
              <el-card shadow="hover" class="stat-card">
                <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
                  <el-icon :size="22"><component :is="card.icon" /></el-icon>
                </div>
                <div class="stat-content">
                  <div class="stat-label">{{ card.label }}</div>
                  <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
                  <div v-if="card.sub" class="stat-sub">{{ card.sub }}</div>
                </div>
              </el-card>
            </el-col>
          </el-row>

          <el-card class="mt-20">
            <template #header>
              <div class="flex-between">
                <span class="text-bold">🏆 模板使用榜 TOP 5</span>
                <el-tag size="small" type="info">按「从模板创建」次数</el-tag>
              </div>
            </template>
            <el-table :data="template.topTemplates || []" stripe empty-text="还没有模板使用记录">
              <el-table-column label="排名" width="70" align="center">
                <template #default="{ $index }">
                  <span :class="['rank-badge', $index < 3 ? 'rank-top' : '']">{{ $index + 1 }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="title" label="模板" min-width="220" show-overflow-tooltip />
              <el-table-column label="被使用次数" width="300" align="center">
                <template #default="{ row }">
                  <div class="progress-cell">
                    <el-progress
                      :percentage="templatePercent(row.useCount)"
                      :stroke-width="10"
                      :show-text="false"
                      color="#8e44ad"
                    />
                    <span class="progress-num">{{ row.useCount }}</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </div>
      </el-tab-pane>

      <!-- ==================== 举报 ==================== -->
      <el-tab-pane v-if="has('review')" label="举报" name="review">
        <div v-if="activeTab === 'review'">
          <el-row :gutter="16">
            <el-col v-for="card in reviewCards" :key="card.label" :span="6" :xs="12" :sm="12" :md="6">
              <el-card shadow="hover" class="stat-card">
                <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
                  <el-icon :size="22"><component :is="card.icon" /></el-icon>
                </div>
                <div class="stat-content">
                  <div class="stat-label">{{ card.label }}</div>
                  <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
                  <div v-if="card.sub" class="stat-sub">{{ card.sub }}</div>
                </div>
              </el-card>
            </el-col>
          </el-row>

          <el-row class="mt-20" :gutter="20">
            <el-col :span="12" :xs="24" :sm="24" :md="12">
              <el-card>
                <template #header>
                  <span class="text-bold">🥧 举报处理状态</span>
                </template>
                <div v-if="review.total > 0" ref="reviewRef" class="chart-md"></div>
                <el-empty v-else description="还没有举报记录" :image-size="80" />
              </el-card>
            </el-col>
            <el-col :span="12" :xs="24" :sm="24" :md="12">
              <el-card class="review-summary-card">
                <template #header>
                  <span class="text-bold">🧭 处理概览</span>
                </template>
                <div class="review-summary">
                  <div class="review-summary-item">
                    <span>处理率</span>
                    <b>{{ review.handledRate }}%</b>
                  </div>
                  <div class="review-summary-item">
                    <span>待处理</span>
                    <b class="warn">{{ review.pending }}</b>
                  </div>
                  <div class="review-summary-item">
                    <span>已通过（下架）</span>
                    <b>{{ review.approved }}</b>
                  </div>
                  <div class="review-summary-item">
                    <span>已驳回</span>
                    <b>{{ review.rejected }}</b>
                  </div>
                </div>
                <p class="summary-tip">
                  处理率 =（已通过 + 已驳回）/ 全部举报；待处理越多，说明审核台压力越大。
                </p>
              </el-card>
            </el-col>
          </el-row>
        </div>
      </el-tab-pane>

      <!-- ==================== 钱包 ==================== -->
      <el-tab-pane v-if="has('wallet')" label="钱包" name="wallet">
        <div v-if="activeTab === 'wallet'">
          <el-row :gutter="16">
            <el-col v-for="card in walletCards" :key="card.label" :span="4" :xs="12" :sm="8" :md="4">
              <el-card shadow="hover" class="stat-card">
                <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
                  <el-icon :size="22"><component :is="card.icon" /></el-icon>
                </div>
                <div class="stat-content">
                  <div class="stat-label">{{ card.label }}</div>
                  <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
                  <div v-if="card.sub" class="stat-sub">{{ card.sub }}</div>
                </div>
              </el-card>
            </el-col>
          </el-row>

          <el-card class="mt-20">
            <template #header>
              <div class="flex-between">
                <span class="text-bold">📊 流水类型分布（笔数）</span>
                <el-tag size="small" type="info">共 {{ walletTxTotal }} 笔</el-tag>
              </div>
            </template>
            <div ref="walletRef" class="chart-lg"></div>
          </el-card>
        </div>
      </el-tab-pane>

      <!-- ==================== 日志 ==================== -->
      <el-tab-pane v-if="has('log')" label="日志" name="log">
        <div v-if="activeTab === 'log'">
          <el-row :gutter="16">
            <el-col v-for="card in logCards" :key="card.label" :span="6" :xs="12" :sm="12" :md="6">
              <el-card shadow="hover" class="stat-card">
                <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
                  <el-icon :size="22"><component :is="card.icon" /></el-icon>
                </div>
                <div class="stat-content">
                  <div class="stat-label">{{ card.label }}</div>
                  <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
                  <div v-if="card.sub" class="stat-sub">{{ card.sub }}</div>
                </div>
              </el-card>
            </el-col>
          </el-row>

          <el-card class="mt-20">
            <template #header>
              <div class="flex-between">
                <span class="text-bold">📈 近 7 天操作量</span>
                <el-tag size="small" type="success">7 天合计 {{ sum(log.trendOperations) }} 次</el-tag>
              </div>
            </template>
            <div ref="logRef" class="chart-lg"></div>
          </el-card>
        </div>
      </el-tab-pane>

      <!-- ==================== 资源 ==================== -->
      <el-tab-pane v-if="has('resource')" label="资源" name="resource">
        <div v-if="activeTab === 'resource'">
          <el-row :gutter="16">
            <el-col v-for="card in resourceCards" :key="card.label" :span="6" :xs="12" :sm="12" :md="6">
              <el-card shadow="hover" class="stat-card">
                <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
                  <el-icon :size="22"><component :is="card.icon" /></el-icon>
                </div>
                <div class="stat-content">
                  <div class="stat-label">{{ card.label }}</div>
                  <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
                  <div v-if="card.sub" class="stat-sub">{{ card.sub }}</div>
                </div>
              </el-card>
            </el-col>
          </el-row>

          <el-card class="mt-20">
            <template #header>
              <div class="flex-between">
                <span class="text-bold">🗄️ 主要业务表行数</span>
                <el-tag size="small" type="info">库大小 {{ formatBytes(resource.dbBytes) }} · {{ resource.tableCount }} 张表</el-tag>
              </div>
            </template>
            <el-table :data="resource.tables || []" stripe empty-text="暂无表数据">
              <el-table-column prop="tableName" label="表名" min-width="200">
                <template #default="{ row }">
                  <code class="table-name">{{ row.tableName }}</code>
                </template>
              </el-table-column>
              <el-table-column label="行数" min-width="320">
                <template #default="{ row }">
                  <div class="progress-cell">
                    <el-progress
                      :percentage="tableRowPercent(row.rowCount)"
                      :stroke-width="8"
                      :show-text="false"
                      color="#2f80ed"
                    />
                    <span class="progress-num">{{ row.rowCount }}</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
            <p class="summary-tip">
              版本快照占用 {{ formatBytes(resource.snapshotBytes) }}；行数为精确 COUNT，体积来自 information_schema。
            </p>
          </el-card>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script>
// 按需引入 ECharts（不要全量 import * as echarts，见 utils/echarts.js）
import echarts from '../utils/echarts'
// ★ 一次取回全部模块：后端按权限裁剪，没权限的模块不返回，前端只按字段渲染 Tab。
//   本页不 import ElMessage：失败提示统一由 http.js 弹。
import api from '../api'
import { getRole } from '../utils/auth'
import {
  chartText, chartStrong, chartSplit, chartTooltip, THEME_EVENT,
  chartPalette, chartColor, chartSurface, chartEmptyGraphic, chartNum, chartPct
} from '../utils/chartTheme'

const TAB_ORDER = ['survey', 'user', 'template', 'review', 'wallet', 'log', 'resource']

/** 流水类型 → 中文（与 WalletTxType 一一对应；新增类型必须同步这里，否则图上会出现英文） */
const WALLET_TYPE_LABELS = {
  SIGNUP_BONUS: '注册赠送',
  RECHARGE: '充值',
  PUBLISH: '发布扣费',
  AI_GENERATE: 'AI 生成',
  EXPORT: '导出',
  ADVANCED_SETTINGS: '高级设置包',
  COLLABORATOR: '协作名额',
  ADVANCED_TYPES: '进阶题型',
  REFUND: '回收退回',
  ADMIN_ADJUST: '管理员调整'
}

export default {
  data() {
    return {
      stats: {},
      loaded: false,
      failed: false,
      activeTab: '',
      role: getRole()
    }
  },
  computed: {
    isSubAdmin() {
      return this.role === 'subadmin'
    },
    hasAnyModule() {
      return Object.keys(this.stats).length > 0
    },
    survey() {
      return this.stats.survey || {}
    },
    user() {
      return this.stats.user || {}
    },
    template() {
      return this.stats.template || {}
    },
    review() {
      return this.stats.review || {}
    },
    wallet() {
      return this.stats.wallet || {}
    },
    log() {
      return this.stats.log || {}
    },
    resource() {
      return this.stats.resource || {}
    },
    maxTopResponse() {
      return Math.max(...(this.survey.topSurveys || []).map(r => r.responses), 1)
    },
    maxTemplateUse() {
      return Math.max(...(this.template.topTemplates || []).map(t => t.useCount), 1)
    },
    maxTableRows() {
      return Math.max(...(this.resource.tables || []).map(t => t.rowCount), 1)
    },
    walletTxTotal() {
      return (this.wallet.byType || []).reduce((acc, item) => acc + Number(item.cnt || 0), 0)
    },
    publishRate() {
      const s = this.survey
      if (!s.total) return 0
      return Math.round(((s.published + s.ended) / s.total) * 100)
    },
    viewConvertRate() {
      const s = this.survey
      if (!s.views) return 0
      return Math.min(100, Math.round((s.responses / s.views) * 100))
    },
    surveyCards() {
      const s = this.survey
      return [
        { label: '问卷总数', value: s.total, icon: 'Document', bg: '#f0f9eb', color: '#67c23a', sub: `已发布 ${s.published}` },
        { label: '草稿', value: s.draft, icon: 'EditPen', bg: '#fdf6ec', color: '#e6a23c', sub: '含未发布' },
        { label: '总答卷', value: s.responses, icon: 'ChatDotRound', bg: '#fef0f0', color: '#f56c6c', sub: `今日 +${s.todayAnswers}` },
        { label: '访客次数', value: s.views, icon: 'View', bg: '#f4f0ff', color: '#8e44ad', sub: '全站' },
        { label: '回收站', value: s.recycled, icon: 'Delete', bg: '#f4f4f5', color: '#909399', sub: '待清理' },
        { label: '模板数', value: s.templates, icon: 'DocumentCopy', bg: '#e8f6ff', color: '#12b0e8', sub: '模板广场' }
      ]
    },
    userCards() {
      const u = this.user
      return [
        { label: '总账号', value: u.total, icon: 'UserFilled', bg: '#ecf5ff', color: '#409eff', sub: `普通用户 ${u.normal}` },
        { label: '管理员', value: u.admins, icon: 'User', bg: '#f0f9eb', color: '#67c23a', sub: `可用 ${u.activeAdmins}` },
        { label: '被禁用', value: u.disabled, icon: 'CircleCloseFilled', bg: '#fef0f0', color: '#f56c6c', sub: '可解禁' },
        { label: '今日新增', value: u.todayNew, icon: 'Timer', bg: '#e7fbff', color: '#0e7490', sub: '今日注册' }
      ]
    },
    templateCards() {
      const t = this.template
      return [
        { label: '模板总数', value: t.total, icon: 'DocumentCopy', bg: '#e8f6ff', color: '#12b0e8', sub: '在架模板' },
        { label: '模板分类', value: t.categories, icon: 'Grid', bg: '#f4f0ff', color: '#8e44ad', sub: '分类目录' }
      ]
    },
    reviewCards() {
      const r = this.review
      return [
        { label: '待处理', value: r.pending, icon: 'Warning', bg: '#fff7e6', color: '#d97706', sub: '需要审核' },
        { label: '已通过', value: r.approved, icon: 'CircleCheckFilled', bg: '#f0f9eb', color: '#16a34a', sub: '已下架' },
        { label: '已驳回', value: r.rejected, icon: 'CircleCloseFilled', bg: '#fef0f0', color: '#dc2626', sub: '未违规' },
        { label: '处理率', value: `${r.handledRate}%`, icon: 'DataLine', bg: '#eef2ff', color: '#4338ca', sub: `共 ${r.total} 条` }
      ]
    },
    walletCards() {
      const w = this.wallet
      return [
        { label: '总余额', value: w.balance, icon: 'Wallet', bg: '#eef2ff', color: '#4338ca', sub: '点' },
        { label: '钱包数', value: w.wallets, icon: 'Files', bg: '#ecf5ff', color: '#409eff', sub: '已开通' },
        { label: '对账异常', value: w.mismatches, icon: 'Warning', bg: '#fef0f0', color: '#dc2626', sub: '需人工核对' },
        { label: '今日入账', value: w.todayIncome, icon: 'Download', bg: '#f0f9eb', color: '#16a34a', sub: `${w.todayCount} 笔` },
        { label: '今日出账', value: w.todayExpense, icon: 'TopRight', bg: '#fff7e6', color: '#d97706', sub: '点' }
      ]
    },
    logCards() {
      const l = this.log
      return [
        { label: '操作日志', value: l.operations, icon: 'Memo', bg: '#ecf5ff', color: '#409eff', sub: '审计记录' },
        { label: '今日操作', value: l.todayOperations, icon: 'Timer', bg: '#e7fbff', color: '#0e7490', sub: '今日新增' },
        { label: '异常日志', value: l.errors, icon: 'WarningFilled', bg: '#fef0f0', color: '#dc2626', sub: '保留 90 天' },
        { label: '今日异常', value: l.todayErrors, icon: 'Warning', bg: '#fff7e6', color: '#d97706', sub: '今日新增' }
      ]
    },
    resourceCards() {
      const r = this.resource
      return [
        { label: '数据库大小', value: this.formatBytes(r.dbBytes), icon: 'DataLine', bg: '#eef2ff', color: '#4338ca', sub: '数据 + 索引' },
        { label: '表数量', value: r.tableCount, icon: 'Grid', bg: '#ecf5ff', color: '#409eff', sub: '当前库' },
        { label: '版本快照', value: this.formatBytes(r.snapshotBytes), icon: 'Files', bg: '#f4f0ff', color: '#8e44ad', sub: 'JSON 占用' },
        { label: '业务行数', value: r.totalRows, icon: 'Rank', bg: '#f0f9eb', color: '#67c23a', sub: '主要表合计' }
      ]
    }
  },
  watch: {
    activeTab() {
      this.$nextTick(() => this.initTabCharts())
    }
  },
  mounted() {
    this.chartMap = {}
    this.loadAll()
    window.addEventListener('resize', this.handleResize)
    window.addEventListener(THEME_EVENT, this.handleThemeChange)
  },
  beforeUnmount() {
    this.disposeCharts()
    window.removeEventListener('resize', this.handleResize)
    window.removeEventListener(THEME_EVENT, this.handleThemeChange)
  },
  methods: {
    has(key) {
      return Object.prototype.hasOwnProperty.call(this.stats, key)
    },
    sum(list) {
      return (list || []).reduce((acc, n) => acc + Number(n || 0), 0)
    },
    formatBytes(bytes) {
      const n = Number(bytes || 0)
      if (n < 1024) return `${n} B`
      if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
      if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
      return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
    },
    templatePercent(useCount) {
      return Math.min(100, Math.round((Number(useCount || 0) / this.maxTemplateUse) * 100))
    },
    tableRowPercent(rowCount) {
      return Math.min(100, Math.round((Number(rowCount || 0) / this.maxTableRows) * 100))
    },
    getProgressColor(status) {
      if (status === 1) return '#67c23a'
      if (status === 2) return '#f56c6c'
      return '#e6a23c'
    },
    async loadAll() {
      try {
        const data = await api.system.statistics()
        this.stats = data && typeof data === 'object' ? data : {}
        this.loaded = true
        this.activeTab = TAB_ORDER.find(k => this.has(k)) || ''
        this.$nextTick(() => this.initTabCharts())
      } catch {
        this.failed = true
      }
    },

    /** 主题切换：销毁当前 Tab 的图表按新配色重画（不重新请求数据） */
    handleThemeChange() {
      this.$nextTick(() => this.initTabCharts())
    },
    handleResize() {
      Object.values(this.chartMap || {}).forEach(c => c && c.resize())
    },
    disposeCharts() {
      Object.values(this.chartMap || {}).forEach(c => c && c.dispose())
      this.chartMap = {}
    },
    /** 只初始化当前 Tab 的图表：隐藏 Tab 的容器宽度为 0，提前 init 会画出空白 */
    initTabCharts() {
      this.disposeCharts()
      if (this.activeTab === 'survey') this.initSurveyCharts()
      else if (this.activeTab === 'user') this.initUserCharts()
      else if (this.activeTab === 'review') this.initReviewChart()
      else if (this.activeTab === 'wallet') this.initWalletChart()
      else if (this.activeTab === 'log') this.initLogChart()
    },

    // ==================== 问卷与答卷 ====================
    initSurveyCharts() {
      this.initSurveyPie()
      this.initSurveyTrend()
      this.initSurveyBucket()
      this.initSurveyType()
      this.initSurveyGauge()
    },

    initSurveyPie() {
      const el = this.$refs.pieRef
      if (!el || !this.survey.total) return
      const chart = echarts.init(el)
      this.chartMap.pie = chart
      const s = this.survey
      const data = []
      if (s.published > 0) data.push({ name: '已发布', value: s.published, itemStyle: { color: chartColor(1) } })
      if (s.draft > 0) data.push({ name: '草稿', value: s.draft, itemStyle: { color: chartColor(2) } })
      if (s.ended > 0) data.push({ name: '已结束', value: s.ended, itemStyle: { color: chartColor(5) } })
      chart.setOption({
        color: chartPalette(),
        tooltip: {
          trigger: 'item',
          formatter: p => `${p.name}：${chartNum(p.value)} 份（${chartPct(p.percent)}%）`,
          ...chartTooltip()
        },
        title: {
          text: String(s.total),
          subtext: '普通问卷数',
          left: 'center',
          top: '34%',
          textStyle: { fontSize: 24, fontWeight: 700, color: chartStrong() },
          subtextStyle: { fontSize: 12, color: chartText() }
        },
        legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: chartText() } },
        series: [{
          type: 'pie',
          radius: ['54%', '76%'],
          center: ['50%', '45%'],
          avoidLabelOverlap: true,
          itemStyle: { borderColor: chartSurface(), borderWidth: 3, borderRadius: 6 },
          label: { show: true, formatter: '{b}\n{c}份', fontSize: 12, color: chartText() },
          labelLine: { length: 10, length2: 8 },
          data
        }]
      })
    },

    initSurveyTrend() {
      const el = this.$refs.trendRef
      if (!el) return
      const chart = echarts.init(el)
      this.chartMap.trend = chart
      const dates = (this.survey.trendDates || []).map(d => String(d).slice(5))
      const isEmpty = this.sum(this.survey.trendSurveys) === 0 && this.sum(this.survey.trendAnswers) === 0
      chart.setOption({
        color: chartPalette(),
        graphic: isEmpty ? chartEmptyGraphic('近 7 天暂无新增') : [],
        tooltip: { trigger: 'axis', ...chartTooltip() },
        legend: { top: 0, right: 0, data: ['新建问卷', '收到答卷'], textStyle: { color: chartText() } },
        grid: { left: 12, right: 16, top: 38, bottom: 8, containLabel: true },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: dates,
          axisLine: { lineStyle: { color: chartSplit() } },
          axisLabel: { color: chartText() }
        },
        yAxis: {
          type: 'value',
          minInterval: 1,
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: { lineStyle: { color: chartSplit() } },
          axisLabel: { color: chartText() }
        },
        series: [
          {
            name: '新建问卷',
            type: 'line',
            smooth: true,
            symbol: 'circle',
            symbolSize: 7,
            data: this.survey.trendSurveys || [],
            itemStyle: { color: chartColor(0) },
            lineStyle: { width: 2.5, color: chartColor(0) },
            areaStyle: { color: 'rgba(47, 128, 237, 0.14)' }
          },
          {
            name: '收到答卷',
            type: 'line',
            smooth: true,
            symbol: 'circle',
            symbolSize: 7,
            data: this.survey.trendAnswers || [],
            itemStyle: { color: chartColor(1) },
            lineStyle: { width: 2.5, color: chartColor(1) },
            areaStyle: { color: 'rgba(103, 194, 58, 0.12)' }
          }
        ]
      })
    },

    initSurveyBucket() {
      const el = this.$refs.bucketRef
      if (!el) return
      const chart = echarts.init(el)
      this.chartMap.bucket = chart
      const data = this.survey.responseBuckets || []
      const hasData = data.some(d => Number(d.count) > 0)
      chart.setOption({
        color: [chartColor(0)],
        graphic: hasData ? [] : chartEmptyGraphic('暂无答卷数据'),
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'shadow' },
          formatter: params => {
            const p = Array.isArray(params) ? params[0] : params
            return `${p.name}<br/>问卷：${chartNum(p.value)} 份`
          },
          ...chartTooltip()
        },
        grid: { left: 8, right: 16, top: 24, bottom: 8, containLabel: true },
        xAxis: {
          type: 'category',
          data: data.map(d => d.label),
          axisTick: { show: false },
          axisLine: { lineStyle: { color: chartSplit() } },
          axisLabel: { color: chartText() }
        },
        yAxis: {
          type: 'value',
          minInterval: 1,
          axisLabel: { color: chartText() },
          splitLine: { lineStyle: { color: chartSplit() } }
        },
        series: [{
          type: 'bar',
          data: data.map(d => d.count),
          barWidth: 34,
          itemStyle: {
            borderRadius: [8, 8, 0, 0],
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: chartColor(4) },
              { offset: 1, color: chartColor(0) }
            ])
          },
          label: { show: true, position: 'top', color: chartText() }
        }]
      })
    },

    initSurveyType() {
      const el = this.$refs.typeRef
      if (!el) return
      const chart = echarts.init(el)
      this.chartMap.type = chart
      const normal = Math.max(this.survey.total || 0, 0)
      const templates = Math.max(this.survey.templates || 0, 0)
      const data = [
        { name: '普通问卷', value: normal, itemStyle: { color: chartColor(0) } },
        { name: '模板问卷', value: templates, itemStyle: { color: chartColor(2) } }
      ].filter(d => d.value > 0)
      const option = {
        color: chartPalette(),
        tooltip: {
          trigger: 'item',
          formatter: p => `${p.name}：${chartNum(p.value)} 份（${chartPct(p.percent)}%）`,
          ...chartTooltip()
        },
        legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: chartText() } },
        title: {
          text: String(normal + templates),
          subtext: '问卷总数（含模板）',
          left: 'center',
          top: '34%',
          textStyle: { fontSize: 24, fontWeight: 700, color: chartStrong() },
          subtextStyle: { fontSize: 12, color: chartText() }
        },
        series: [{
          type: 'pie',
          radius: ['54%', '76%'],
          center: ['50%', '45%'],
          itemStyle: { borderColor: chartSurface(), borderWidth: 3, borderRadius: 6 },
          label: { formatter: '{b}\n{c}份', color: chartText(), fontSize: 12 },
          labelLine: { length: 10, length2: 8 },
          data
        }]
      }
      if (data.length === 0) {
        option.title = undefined
        option.legend = undefined
        option.series = []
        option.graphic = chartEmptyGraphic('暂无问卷数据')
      }
      chart.setOption(option)
    },

    initSurveyGauge() {
      const el = this.$refs.gaugeRef
      if (!el) return
      const chart = echarts.init(el)
      this.chartMap.gauge = chart
      const total = this.survey.total || 0
      if (total === 0) {
        chart.setOption({ graphic: chartEmptyGraphic('暂无问卷数据'), series: [] })
        return
      }
      const score = Math.round(((this.survey.published + this.survey.ended) / total) * 100)
      chart.setOption({
        color: chartPalette(),
        series: [{
          type: 'gauge',
          startAngle: 210,
          endAngle: -30,
          min: 0,
          max: 100,
          radius: '95%',
          progress: {
            show: true,
            width: 14,
            itemStyle: { color: score >= 70 ? chartColor(1) : score >= 40 ? chartColor(2) : chartColor(5) }
          },
          axisLine: { lineStyle: { width: 14, color: [[1, chartSplit()]] } },
          pointer: { show: false },
          axisTick: { show: false },
          splitLine: { show: false },
          axisLabel: { show: false },
          detail: {
            valueAnimation: true,
            formatter: '{value}\n活跃度',
            fontSize: 18,
            fontWeight: 'bold',
            color: chartStrong(),
            offsetCenter: [0, '0%'],
            lineHeight: 22
          },
          data: [{ value: score }]
        }]
      })
    },

    // ==================== 用户 ====================
    initUserCharts() {
      const trendEl = this.$refs.userTrendRef
      if (trendEl) {
        const chart = echarts.init(trendEl)
        this.chartMap.userTrend = chart
        const dates = (this.user.trendDates || []).map(d => String(d).slice(5))
        chart.setOption({
          color: chartPalette(),
          tooltip: {
            trigger: 'axis',
            formatter: params => {
              const p = Array.isArray(params) ? params[0] : params
              return `${p.name}<br/>新注册：${chartNum(p.value)} 人`
            },
            ...chartTooltip()
          },
          grid: { left: 12, right: 16, top: 24, bottom: 8, containLabel: true },
          xAxis: {
            type: 'category',
            boundaryGap: false,
            data: dates,
            axisLine: { lineStyle: { color: chartSplit() } },
            axisLabel: { color: chartText() }
          },
          yAxis: {
            type: 'value',
            minInterval: 1,
            axisLabel: { color: chartText() },
            splitLine: { lineStyle: { color: chartSplit() } }
          },
          series: [{
            type: 'line',
            smooth: true,
            symbol: 'circle',
            symbolSize: 8,
            data: this.user.trendNew || [],
            itemStyle: { color: chartColor(0) },
            lineStyle: { width: 3, color: chartColor(0) },
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(47, 128, 237, 0.35)' },
                { offset: 1, color: 'rgba(47, 128, 237, 0.02)' }
              ])
            },
            label: { show: true, position: 'top', color: chartColor(0), fontWeight: 'bold' }
          }]
        })
      }

      const roleEl = this.$refs.userRoleRef
      if (roleEl) {
        const chart = echarts.init(roleEl)
        this.chartMap.userRole = chart
        const data = [
          { name: '普通用户', value: this.user.normal || 0, itemStyle: { color: chartColor(0) } },
          { name: '管理员', value: this.user.admins || 0, itemStyle: { color: chartColor(2) } }
        ].filter(d => d.value > 0)
        chart.setOption({
          color: chartPalette(),
          tooltip: {
            trigger: 'item',
            formatter: p => `${p.name}：${chartNum(p.value)} 人（${chartPct(p.percent)}%）`,
            ...chartTooltip()
          },
          legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: chartText() } },
          title: {
            text: String(this.user.total || 0),
            subtext: '总账号',
            left: 'center',
            top: '34%',
            textStyle: { fontSize: 24, fontWeight: 700, color: chartStrong() },
            subtextStyle: { fontSize: 12, color: chartText() }
          },
          series: [{
            type: 'pie',
            radius: ['54%', '76%'],
            center: ['50%', '45%'],
            itemStyle: { borderColor: chartSurface(), borderWidth: 3, borderRadius: 6 },
            label: { formatter: '{b}\n{c}人', color: chartText(), fontSize: 12 },
            labelLine: { length: 10, length2: 8 },
            data
          }]
        })
      }
    },

    // ==================== 举报 ====================
    initReviewChart() {
      const el = this.$refs.reviewRef
      if (!el || !this.review.total) return
      const chart = echarts.init(el)
      this.chartMap.review = chart
      const data = [
        { name: '待处理', value: this.review.pending || 0, itemStyle: { color: chartColor(2) } },
        { name: '已通过', value: this.review.approved || 0, itemStyle: { color: chartColor(1) } },
        { name: '已驳回', value: this.review.rejected || 0, itemStyle: { color: chartColor(5) } }
      ].filter(d => d.value > 0)
      chart.setOption({
        color: chartPalette(),
        tooltip: {
          trigger: 'item',
          formatter: p => `${p.name}：${chartNum(p.value)} 条（${chartPct(p.percent)}%）`,
          ...chartTooltip()
        },
        legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: chartText() } },
        series: [{
          type: 'pie',
          radius: ['54%', '76%'],
          center: ['50%', '45%'],
          itemStyle: { borderColor: chartSurface(), borderWidth: 3, borderRadius: 6 },
          label: { formatter: '{b}\n{c}条', color: chartText(), fontSize: 12 },
          labelLine: { length: 10, length2: 8 },
          data
        }]
      })
    },

    // ==================== 钱包 ====================
    initWalletChart() {
      const el = this.$refs.walletRef
      if (!el) return
      const chart = echarts.init(el)
      this.chartMap.wallet = chart
      const rows = this.wallet.byType || []
      const labels = rows.map(r => WALLET_TYPE_LABELS[r.type] || r.type)
      const counts = rows.map(r => Number(r.cnt || 0))
      chart.setOption({
        color: [chartColor(0)],
        tooltip: {
          trigger: 'axis',
          axisPointer: { type: 'shadow' },
          formatter: params => {
            const p = Array.isArray(params) ? params[0] : params
            const row = rows[p.dataIndex] || {}
            return `${p.name}<br/>笔数：${chartNum(row.cnt)}<br/>金额合计：${chartNum(row.amount)} 点`
          },
          ...chartTooltip()
        },
        grid: { left: 12, right: 24, top: 24, bottom: 8, containLabel: true },
        xAxis: {
          type: 'category',
          data: labels,
          axisTick: { show: false },
          axisLine: { lineStyle: { color: chartSplit() } },
          axisLabel: { color: chartText(), interval: 0, rotate: labels.length > 6 ? 24 : 0 }
        },
        yAxis: {
          type: 'value',
          minInterval: 1,
          axisLabel: { color: chartText() },
          splitLine: { lineStyle: { color: chartSplit() } }
        },
        series: [{
          type: 'bar',
          data: counts,
          barWidth: 30,
          itemStyle: {
            borderRadius: [8, 8, 0, 0],
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: chartColor(4) },
              { offset: 1, color: chartColor(0) }
            ])
          },
          label: { show: true, position: 'top', color: chartText() }
        }]
      })
    },

    // ==================== 日志 ====================
    initLogChart() {
      const el = this.$refs.logRef
      if (!el) return
      const chart = echarts.init(el)
      this.chartMap.log = chart
      const dates = (this.log.trendDates || []).map(d => String(d).slice(5))
      chart.setOption({
        color: chartPalette(),
        tooltip: {
          trigger: 'axis',
          formatter: params => {
            const p = Array.isArray(params) ? params[0] : params
            return `${p.name}<br/>操作：${chartNum(p.value)} 次`
          },
          ...chartTooltip()
        },
        grid: { left: 12, right: 16, top: 24, bottom: 8, containLabel: true },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: dates,
          axisLine: { lineStyle: { color: chartSplit() } },
          axisLabel: { color: chartText() }
        },
        yAxis: {
          type: 'value',
          minInterval: 1,
          axisLabel: { color: chartText() },
          splitLine: { lineStyle: { color: chartSplit() } }
        },
        series: [{
          type: 'line',
          smooth: true,
          symbol: 'circle',
          symbolSize: 8,
          data: this.log.trendOperations || [],
          itemStyle: { color: chartColor(2) },
          lineStyle: { width: 3, color: chartColor(2) },
          areaStyle: {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(230, 162, 60, 0.35)' },
              { offset: 1, color: 'rgba(230, 162, 60, 0.02)' }
            ])
          },
          label: { show: true, position: 'top', color: chartColor(2), fontWeight: 'bold' }
        }]
      })
    }
  }
}
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.page-title {
  margin: 0 0 6px;
  font-size: 22px;
  color: var(--ink);
}
.page-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--ink-3);
}
.stat-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}
/* 卡片是纯展示容器：任何内部滚动条都会抢走页面滚轮，统一裁掉 */
.stat-tabs :deep(.el-card) {
  overflow: hidden;
}
.stat-tabs :deep(.el-tabs__content) {
  overflow: visible;
}

/* 指标卡 */
.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  padding: 16px;
}
.stat-icon {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 12px;
  flex-shrink: 0;
}
.stat-content {
  flex: 1;
  min-width: 0;
}
.stat-label {
  font-size: 12px;
  color: #999;
  margin-bottom: 4px;
}
.stat-value {
  font-size: 21px;
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
}
.stat-sub {
  margin-top: 4px;
  font-size: 11px;
  color: #a8abb2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 两栏等高 */
.equal-height-row :deep(.el-col) {
  display: flex;
}
.equal-height-row :deep(.el-card) {
  width: 100%;
  display: flex;
  flex-direction: column;
}
.equal-height-row :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.chart-md {
  width: 100%;
  height: 300px;
  overflow: hidden;
}
.chart-lg {
  width: 100%;
  height: 340px;
  overflow: hidden;
}
.gauge-chart {
  width: 100%;
  height: 150px;
  overflow: hidden;
}

/* TOP5 徽章与进度 */
.rank-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #f0f2f5;
  color: #909399;
  font-size: 12px;
  font-weight: 600;
}
.rank-badge.rank-top {
  background: linear-gradient(135deg, #ffd700, #ff8c00);
  color: #fff;
  box-shadow: 0 2px 6px rgba(255, 140, 0, 0.4);
}
.response-num {
  font-weight: 600;
  color: #f56c6c;
  min-width: 36px;
  text-align: right;
}
.progress-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.progress-cell :deep(.el-progress) {
  flex: 1;
}
.progress-num {
  min-width: 44px;
  text-align: right;
  font-weight: 600;
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}
.table-name {
  font-size: 12px;
  color: var(--ink-2);
}

/* 系统概况指标 */
.metric-list {
  padding: 4px 8px;
}
.metric-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 9px 0;
  border-bottom: 1px dashed #f0f2f5;
}
.metric-row:last-child {
  border-bottom: none;
}
.metric-name {
  font-size: 13px;
  color: #606266;
}
.metric-value {
  font-size: 15px;
  font-weight: 600;
  color: #409eff;
}

/* 举报概览 */
.review-summary {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.review-summary-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-radius: 10px;
  background: #f7f9fc;
  font-size: 13px;
  color: var(--ink-2);
}
.review-summary-item b {
  font-size: 18px;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.review-summary-item b.warn {
  color: #d97706;
}
.summary-tip {
  margin: 12px 2px 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-3);
}

html[data-theme="dark"] .review-summary-item {
  background: #16263f;
}
</style>
