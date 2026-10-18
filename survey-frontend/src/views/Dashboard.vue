<template>
  <div>
    <!-- 顶部 8 个指标卡：从「只有规模」升级为「规模 + 运营待办 + 新能力」 -->
    <el-row :gutter="16">
      <el-col
        v-for="card in statCards"
        :key="card.key"
        :span="6"
        :xs="12"
        :sm="8"
        :md="6"
      >
        <el-card
          shadow="hover"
          class="stat-card"
          :class="{ clickable: !!card.path }"
          @click="openCard(card)"
        >
          <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
            <el-icon :size="26"><component :is="card.icon" /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">{{ card.label }}</div>
            <div class="stat-value" :style="{ color: card.color }">{{ card.value }}</div>
            <div v-if="card.sub" class="stat-sub">{{ card.sub }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 中间：状态分布 + 近 7 天趋势 -->
    <el-row class="mt-20 equal-height-row" :gutter="20">
      <el-col :span="9" :xs="24" :sm="24" :md="9">
        <el-card>
          <template #header>
            <div class="flex-between">
              <span class="text-bold">📊 问卷状态分布</span>
              <span class="panel-hint">共 {{ stats.total }} 份普通问卷</span>
            </div>
          </template>
          <div v-if="stats.total > 0" ref="pieRef" class="chart-lg"></div>
          <el-empty v-else description="暂无问卷数据" :image-size="80" />
        </el-card>
      </el-col>

      <el-col :span="15" :xs="24" :sm="24" :md="15">
        <el-card>
          <template #header>
            <div class="flex-between">
              <span class="text-bold">📈 近 7 天新增（新建问卷 / 收到答卷）</span>
              <span class="panel-hint">按自然日统计</span>
            </div>
          </template>
          <div ref="trendRef" class="chart-lg"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 下：热门问卷 TOP 5 + 运营待办/系统信息 -->
    <el-row class="mt-20 equal-height-row" :gutter="20">
      <el-col :span="15" :xs="24" :sm="24" :md="15">
        <el-card>
          <template #header>
            <div class="flex-between">
              <span class="text-bold">🏆 热门问卷 TOP 5</span>
              <el-button type="primary" link @click="$router.push('/admin/surveys')">
                全部问卷 →
              </el-button>
            </div>
          </template>

          <el-table :data="stats.topSurveys" size="small" empty-text="还没有问卷">
            <el-table-column type="index" label="#" width="52" align="center" />
            <el-table-column prop="title" label="问卷" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <el-link type="primary" :underline="false" @click="goStatistics(row)">
                  {{ row.title }}
                </el-link>
              </template>
            </el-table-column>
            <el-table-column prop="responses" label="答卷" width="90" align="right">
              <template #default="{ row }">
                <b class="response-count">{{ row.responses }}</b>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="statusMeta(row.status).tag" size="small" effect="light" round>
                  {{ statusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="center">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="goStatistics(row)">
                  统计
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="9" :xs="24" :sm="24" :md="9">
        <el-card>
          <template #header>
            <div class="flex-between">
              <span class="text-bold">⚙️ 运营与系统</span>
              <el-tag :type="healthTagType" size="small">
                <span
                  :style="{
                    display: 'inline-block',
                    width: '6px',
                    height: '6px',
                    borderRadius: '50%',
                    background: healthDotColor,
                    marginRight: '4px',
                    verticalAlign: 'middle'
                  }"
                ></span>
                {{ healthText }}
              </el-tag>
            </div>
          </template>

          <!-- 待办：点击直达对应页面（数字全部来自 /system-overview，不是假数据） -->
          <div class="ops-list">
            <div class="ops-item" @click="$router.push('/admin/reviews')">
              <span class="ops-label">待处理举报</span>
              <span class="ops-value" :class="{ warn: stats.pendingReviews > 0 }">
                {{ stats.pendingReviews }}
              </span>
            </div>
            <div class="ops-item" @click="$router.push('/admin/recycle')">
              <span class="ops-label">回收站问卷</span>
              <span class="ops-value">{{ stats.recycled }}</span>
            </div>
            <div class="ops-item" @click="$router.push('/admin/templates')">
              <span class="ops-label">在架模板</span>
              <span class="ops-value">{{ stats.templates }}</span>
            </div>
            <div class="ops-item" @click="$router.push('/admin/wallets')">
              <span class="ops-label">全站额度总量</span>
              <span class="ops-value">{{ stats.walletBalance }} 点</span>
            </div>
          </div>

          <el-divider class="my-12" />

          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="系统版本">v2.3.0</el-descriptions-item>
            <el-descriptions-item label="当前用户">
              {{ currentUser.nickname || currentUser.username }}
            </el-descriptions-item>
            <el-descriptions-item label="登录时间">{{ loginTime }}</el-descriptions-item>
            <el-descriptions-item label="数据库">MySQL 8 · 21 张表</el-descriptions-item>
            <el-descriptions-item label="缓存 / 限流">Redis 统计缓存 + 分布式限流 + 熔断</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>

    <!-- 底部：快捷操作（按新能力补齐入口） -->
    <el-card class="mt-20">
      <template #header>
        <span class="text-bold">⚡ 快捷操作</span>
      </template>
      <el-row :gutter="16">
        <el-col
          v-for="action in quickActions"
          :key="action.path"
          :span="4"
          :xs="12"
          :sm="8"
          :md="4"
        >
          <div class="quick-action" @click="$router.push(action.path)">
            <el-icon :size="28" :color="action.color">
              <component :is="action.icon" />
            </el-icon>
            <div class="text-subtitle">{{ action.title }}</div>
            <div class="text-hint">{{ action.desc }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 技术栈与系统信息：沿用原总览里那张"项目用了什么"的表，放到页面最底部，
         不抢运营指标的第一屏；样式与上面的卡片一致（el-card + el-descriptions + 标签）。 -->
    <el-card class="mt-20">
      <template #header>
        <div class="flex-between">
          <span class="text-bold">🧩 技术栈与系统信息</span>
          <span class="panel-hint">v2.3.0 · 94 端点 · 324 测试 · 21 张表</span>
        </div>
      </template>

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="前端">
          Vue 3.5（全 Options API）· Vite · Element Plus · ECharts · qrcode · Vue Router
        </el-descriptions-item>
        <el-descriptions-item label="后端">
          Spring Boot 4 · MyBatis · PageHelper · OkHttp · JJWT · Redis（Lettuce）
        </el-descriptions-item>
        <el-descriptions-item label="数据库">
          MySQL 8.x（survey_db · 21 张表 · utf8mb4）
        </el-descriptions-item>
        <el-descriptions-item label="缓存 / 限流">
          Redis 5：统计缓存 + 分布式限流 + 熔断降级（不可用时回退进程内计数）
        </el-descriptions-item>
        <el-descriptions-item label="安全机制" :span="2">
          JWT 双 token（access 12h + refresh 7d）· RBAC 权限点 + AOP 切面 · PBKDF2 加盐哈希 ·
          滑块验证码 · token_valid_after 吊销 · HttpOnly + SameSite Cookie
        </el-descriptions-item>
        <el-descriptions-item label="进阶能力" :span="2">
          Spring AOP · @Scheduled 定时发布/截止 · @Async 站内通知 · 版本快照/回滚 · 答卷质控 ·
          矩阵/排序/NPS 统计 · 条件跳转 · 额度钱包
        </el-descriptions-item>
        <el-descriptions-item label="AI / 工具" :span="2">
          Qwen2.5-7B-Instruct（SiliconFlow）· Knife4j（OpenAPI 3）· EasyExcel ·
          Docker Compose · GitHub Actions（3 个 CI 作业）
        </el-descriptions-item>
      </el-descriptions>

      <div class="feature-tags">
        <el-tag v-for="tag in featureTags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag>
      </div>
    </el-card>
  </div>
</template>

<script>
// ★ 按需引入 ECharts（只打包用得到的图表）：整库 import 会把全部图表打进产物。
import echarts from '../utils/echarts'
// ★ 统一接口层：本页的请求一律走 src/api/（token、401 跳转、错误提示都由它处理）。
import api from '../api'
import {
  chartText, chartSplit, chartTooltip, THEME_EVENT,
  chartPalette, chartColor, chartNum, chartPct
} from '../utils/chartTheme'
// ★ 失败提示由 src/api/http.js 统一弹出，所以本文件不需要 import ElMessage
import { getUser } from '../utils/auth'

// 图标已在 main.js 全局注册（新增 Wallet），模板里用 <component :is="'Xxx'" /> 即可
export default {
  data() {
    return {
      currentUser: getUser() || {},
      loginTime: '',
      stats: {
        users: 0,
        total: 0,
        published: 0,
        draft: 0,
        ended: 0,
        responses: 0,
        views: 0,
        templates: 0,
        recycled: 0,
        pendingReviews: 0,
        walletBalance: 0,
        todaySurveys: 0,
        todayAnswers: 0,
        trendDates: [],
        trendSurveys: [],
        trendAnswers: [],
        topSurveys: []
      },
      pieChart: null,
      trendChart: null,
      serverHealthy: true,
      healthTimer: null,
      /** 项目当前能力标签（与 README 功能特性同步；只作展示，不参与逻辑） */
      featureTags: [
        '十种题型',
        '矩阵/排序/NPS',
        'RBAC 权限',
        '协作者',
        '举报审核',
        '额度钱包',
        '模板热度',
        '版本历史',
        '答卷质控',
        'Excel 导出',
        'AI 生成',
        'Redis 限流'
      ]
    }
  },
  computed: {
    /** 8 个指标卡：值 / 图标 / 颜色 / 副标题 / 跳转全部在这里定义 */
    statCards() {
      const s = this.stats
      return [
        {
          key: 'users', label: '总用户', value: s.users, icon: 'UserFilled',
          bg: '#ecf5ff', color: '#409eff', sub: '注册账号'
        },
        {
          key: 'surveys', label: '总问卷', value: s.total, icon: 'Document',
          bg: '#f0f9eb', color: '#67c23a', sub: `已发布 ${s.published}`
        },
        {
          key: 'answers', label: '总答卷', value: s.responses, icon: 'ChatDotRound',
          bg: '#fef0f0', color: '#f56c6c', sub: `今日 +${s.todayAnswers}`
        },
        {
          key: 'views', label: '访客次数', value: s.views, icon: 'View',
          bg: '#f4f0ff', color: '#8e44ad', sub: `回收站 ${s.recycled}`
        },
        {
          key: 'templates', label: '在架模板', value: s.templates, icon: 'DocumentCopy',
          bg: '#e8f6ff', color: '#12b0e8', sub: '模板广场'
        },
        {
          key: 'reviews', label: '待处理举报', value: s.pendingReviews, icon: 'Warning',
          bg: '#fff7e6', color: '#d97706',
          sub: s.pendingReviews > 0 ? '点击处理' : '暂无待办',
          path: '/admin/reviews'
        },
        {
          key: 'wallet', label: '全站额度', value: s.walletBalance, icon: 'Wallet',
          bg: '#eef2ff', color: '#4338ca', sub: '点', path: '/admin/wallets'
        },
        {
          key: 'today', label: '今日新增问卷', value: s.todaySurveys, icon: 'Timer',
          bg: '#e7fbff', color: '#0e7490', sub: '今日新建'
        }
      ]
    },
    quickActions() {
      return [
        { title: '用户管理', desc: '账号 / 注销恢复', path: '/users', icon: 'UserFilled', color: '#409eff' },
        { title: '全部问卷', desc: '查看 / 模板 / 删除', path: '/admin/surveys', icon: 'Document', color: '#67c23a' },
        { title: '模板管理', desc: '分类 / 热度 / 内容', path: '/admin/templates', icon: 'DocumentCopy', color: '#12b0e8' },
        { title: '举报审核', desc: '下架 / 恢复上架', path: '/admin/reviews', icon: 'Warning', color: '#e6a23c' },
        { title: '钱包管理', desc: '余额 / 调整 / 对账', path: '/admin/wallets', icon: 'Wallet', color: '#8e44ad' },
        { title: '系统监控', desc: '操作 / 异常日志', path: '/admin/logs', icon: 'Memo', color: '#f56c6c' }
      ]
    },
    healthTagType() {
      return this.serverHealthy ? 'success' : 'danger'
    },
    healthDotColor() {
      return this.serverHealthy ? '#67c23a' : '#f56c6c'
    },
    healthText() {
      return this.serverHealthy ? '服务运行正常' : '服务已离线'
    }
  },
  mounted() {
    this.loginTime = this.formatNow()
    this.loadStats()
    this.checkHealth()
    this.healthTimer = setInterval(this.checkHealth, 5000)
    window.addEventListener(THEME_EVENT, this.handleThemeChange)
  },
  beforeUnmount() {
    if (this.pieChart) this.pieChart.dispose()
    if (this.trendChart) this.trendChart.dispose()
    window.removeEventListener('resize', this.handleResize)
    window.removeEventListener(THEME_EVENT, this.handleThemeChange)
    if (this.healthTimer) clearInterval(this.healthTimer)
  },
  methods: {
    openCard(card) {
      if (card && card.path) this.$router.push(card.path)
    },

    /** 窗口尺寸变化时两张图都要 resize */
    handleResize() {
      if (this.pieChart) this.pieChart.resize()
      if (this.trendChart) this.trendChart.resize()
    },

    formatNow() {
      const d = new Date()
      const pad = n => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
    },

    // ===== 后端健康检查（运营卡片的绿点/红点）=====
    async checkHealth() {
      try {
        await api.misc.health()
        this.serverHealthy = true
      } catch {
        this.serverHealthy = false
      }
    },

    // ===== 一次拉全总览，再画两张图 =====
    async loadStats() {
      try {
        const data = await api.survey.systemOverview()
        this.stats = {
          ...this.stats,
          ...data,
          trendDates: Array.isArray(data.trendDates) ? data.trendDates : [],
          trendSurveys: Array.isArray(data.trendSurveys) ? data.trendSurveys : [],
          trendAnswers: Array.isArray(data.trendAnswers) ? data.trendAnswers : [],
          topSurveys: Array.isArray(data.topSurveys) ? data.topSurveys : []
        }
        this.$nextTick(() => this.initCharts())
      } catch {
        // 失败提示由 src/api/http.js 统一弹出；保持 0 / 空状态
      }
    },

    handleThemeChange() {
      this.initCharts()
    },

    initCharts() {
      this.initPie()
      this.initTrend()
      // 两张图共用一套 resize 监听（先移除再注册，避免重复叠加）
      window.removeEventListener('resize', this.handleResize)
      window.addEventListener('resize', this.handleResize)
    },

    initPie() {
      if (this.stats.total === 0 || !this.$refs.pieRef) return
      if (this.pieChart) this.pieChart.dispose()

      this.pieChart = echarts.init(this.$refs.pieRef)
      const data = []
      if (this.stats.published > 0) data.push({ name: '已发布', value: this.stats.published, itemStyle: { color: chartColor(1) } })
      if (this.stats.draft > 0) data.push({ name: '草稿', value: this.stats.draft, itemStyle: { color: chartColor(2) } })
      if (this.stats.ended > 0) data.push({ name: '已结束', value: this.stats.ended, itemStyle: { color: chartColor(5) } })

      this.pieChart.setOption({
        color: chartPalette(),
        tooltip: {
          trigger: 'item',
          formatter: p => `${p.name}：${chartNum(p.value)} 份（${chartPct(p.percent)}%）`,
          ...chartTooltip()
        },
        legend: { bottom: 0, textStyle: { color: chartText() } },
        series: [
          {
            type: 'pie',
            radius: ['45%', '70%'],
            center: ['50%', '45%'],
            label: { formatter: '{b}\n{c}份', color: chartText() },
            data
          }
        ]
      })
    },

    initTrend() {
      if (!this.$refs.trendRef) return
      if (this.trendChart) this.trendChart.dispose()

      this.trendChart = echarts.init(this.$refs.trendRef)
      const dates = (this.stats.trendDates || []).map(d => String(d).slice(5))
      this.trendChart.setOption({
        color: chartPalette(),
        tooltip: { trigger: 'axis', ...chartTooltip() },
        legend: {
          top: 0,
          right: 0,
          data: ['新建问卷', '收到答卷'],
          textStyle: { color: chartText() }
        },
        grid: { left: 12, right: 16, top: 38, bottom: 8, containLabel: true },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: dates,
          axisLabel: { color: chartText() },
          axisLine: { lineStyle: { color: chartSplit() } }
        },
        yAxis: {
          type: 'value',
          minInterval: 1,
          axisLabel: { color: chartText() },
          splitLine: { lineStyle: { color: chartSplit() } }
        },
        series: [
          {
            name: '新建问卷',
            type: 'line',
            smooth: true,
            symbolSize: 6,
            data: this.stats.trendSurveys,
            areaStyle: { opacity: 0.12 }
          },
          {
            name: '收到答卷',
            type: 'line',
            smooth: true,
            symbolSize: 6,
            data: this.stats.trendAnswers,
            areaStyle: { opacity: 0.12 }
          }
        ]
      })
    },

    goStatistics(row) {
      this.$router.push(`/my/surveys/${row.id}/statistics`)
    },

    /** 表格状态标签（与「我的问卷」同一口径：NULL 视为草稿） */
    statusMeta(status) {
      if (status === 1) return { label: '已发布', tag: 'success' }
      if (status === 2) return { label: '已结束', tag: 'info' }
      return { label: '草稿', tag: 'warning' }
    }
  }
}
</script>

<style scoped>
/* 两栏等高：el-row 是 flex，把 el-col 也变成 flex 容器后，
   里面的 el-card 会拉伸到整行高度 —— 右侧内容变高时左侧图表卡自动跟上。 */
.equal-height-row :deep(.el-col) {
  display: flex;
}
.equal-height-row :deep(.el-card) {
  width: 100%;
}

.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  padding: 16px;
}
.stat-card.clickable {
  cursor: pointer;
}
.stat-card.clickable:hover {
  transform: translateY(-2px);
}
.stat-icon {
  width: 50px;
  height: 50px;
  border-radius: 10px;
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
  font-size: 22px;
  font-weight: 700;
  line-height: 1.1;
}
.stat-sub {
  margin-top: 4px;
  font-size: 11px;
  color: #a8abb2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-lg {
  width: 100%;
  height: 330px;
}
.panel-hint {
  font-size: 12px;
  color: var(--ink-3);
}

.response-count {
  color: var(--sky-600, #2563eb);
  font-size: 13px;
}

/* 运营待办 */
.ops-list {
  display: flex;
  flex-direction: column;
}
.ops-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color 0.16s var(--ease);
}
.ops-item:hover {
  background: var(--sky-50, #eff6ff);
}
.ops-label {
  font-size: 13px;
  color: var(--ink-2);
}
.ops-value {
  font-size: 15px;
  font-weight: 700;
  color: var(--ink);
}
.ops-value.warn {
  color: #d97706;
}
.my-12 {
  margin: 12px 0;
}

.feature-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.quick-action {
  text-align: center;
  padding: 18px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  border: 1px solid #f0f2f5;
}
.quick-action:hover {
  background: #f8fafc;
  border-color: #409eff;
  transform: translateY(-2px);
}

@media (max-width: 900px) {
  .chart-lg {
    height: 260px;
  }
}
</style>
