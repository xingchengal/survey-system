<template>
  <div class="dash">
    <!-- ==================== 骨架屏 ==================== -->
    <div v-if="loading" class="dash-skeleton">
      <div class="sk-block" style="height: 168px;"></div>
      <el-row :gutter="20" class="mt-20">
        <el-col v-for="i in 4" :key="i" :span="6" :xs="12" :sm="12" :md="6">
          <div class="sk-block" style="height: 112px;"></div>
        </el-col>
      </el-row>
      <el-row :gutter="20" class="mt-20">
        <el-col :span="10" :xs="24" :sm="24" :md="10"><div class="sk-block" style="height: 380px;"></div></el-col>
        <el-col :span="14" :xs="24" :sm="24" :md="14"><div class="sk-block" style="height: 380px;"></div></el-col>
      </el-row>
    </div>

    <template v-else>
      <!-- ==================== 欢迎横幅 ==================== -->
      <section class="hero">
        <div class="hero-glow hero-glow-1"></div>
        <div class="hero-glow hero-glow-2"></div>
        <div class="hero-content">
          <div class="hero-eyebrow">MY DASHBOARD</div>
          <h2 class="hero-title">👋 你好，{{ userName }}</h2>
          <p class="hero-sub">欢迎回来，这里是你的问卷数据总览</p>
          <div class="hero-actions">
            <!-- ★ 只留一个按钮：原来「新建问卷」和「查看我的问卷」都 push 同一个 /my/surveys，
                 属于重复入口（侧栏本来就有「我的问卷」，下方最近问卷卡也有「查看全部」）。
                 现在这个直接带 ?create=1，落地就打开新建弹窗，按钮语义才成立。 -->
            <el-button
              class="hero-btn hero-btn-primary"
              @click="$router.push('/my/surveys?create=1')"
            >
              ＋ 新建问卷
            </el-button>
          </div>
        </div>
        <div class="hero-stats">
          <div class="hero-stat">
            <b>{{ stats.total }}</b>
            <span>问卷</span>
          </div>
          <div class="hero-stat">
            <b>{{ stats.published }}</b>
            <span>已发布</span>
          </div>
          <div class="hero-stat">
            <b>{{ stats.responses }}</b>
            <span>答卷</span>
          </div>
        </div>
      </section>

      <!-- ==================== 数据卡片 ==================== -->
      <el-row :gutter="20" class="mt-20">
        <el-col v-for="card in statCards" :key="card.key" :span="6" :xs="12" :sm="12" :md="6">
          <el-card shadow="hover" class="metric-card">
            <div class="metric-icon" :style="{ background: card.iconBg, color: card.color }">
              <el-icon :size="26"><component :is="card.icon" /></el-icon>
            </div>
            <div class="metric-body">
              <div class="metric-label">{{ card.label }}</div>
              <div class="metric-value" :style="{ color: card.color }">{{ card.value }}</div>
            </div>
            <div class="metric-glow" :style="{ background: card.color }"></div>
          </el-card>
        </el-col>
      </el-row>

      <!-- ==================== 下方两块 ==================== -->
      <el-row class="mt-20 equal-row" :gutter="20">
        <!-- 左：状态分布 -->
        <el-col :span="10" :xs="24" :sm="24" :md="10">
          <el-card class="panel-card">
            <template #header>
              <div class="flex-between">
                <span class="panel-title">📊 问卷状态分布</span>
                <el-tag size="small" effect="plain" round>共 {{ stats.total }} 份</el-tag>
              </div>
            </template>
            <div v-if="stats.total > 0" ref="pieRef" class="pie"></div>
            <el-empty v-else description="暂无问卷" :image-size="90" />

            <!-- 环形图下方的三个派生指标：让"统计"不止一张饼图 -->
            <div class="kpi-grid">
              <div class="kpi">
                <div class="kpi-label">发布率</div>
                <div class="kpi-value">{{ publishRate }}%</div>
                <div class="kpi-bar"><i :style="{ width: publishRate + '%' }"></i></div>
              </div>
              <div class="kpi">
                <div class="kpi-label">平均答卷</div>
                <div class="kpi-value">{{ avgResponses }}</div>
                <div class="kpi-hint">份 / 问卷</div>
              </div>
              <div class="kpi">
                <div class="kpi-label">已结束</div>
                <div class="kpi-value">{{ stats.ended }}</div>
                <div class="kpi-hint">份</div>
              </div>
            </div>

            <!-- 近 7 天答卷趋势：补上"变化"这个维度（上面三格都是当前快照） -->
            <div class="trend-block">
              <div class="trend-head">
                <span class="trend-title">近 7 天答卷</span>
                <span class="trend-total">{{ stats.recent7Answers || 0 }} 份</span>
              </div>
              <div v-if="trendHasData" class="trend-chart">
                <svg viewBox="0 0 280 64" preserveAspectRatio="none" aria-hidden="true">
                  <defs>
                    <linearGradient id="trend-area" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stop-color="rgba(47, 128, 237, 0.28)" />
                      <stop offset="100%" stop-color="rgba(47, 128, 237, 0)" />
                    </linearGradient>
                  </defs>
                  <path :d="trendAreaPath" fill="url(#trend-area)" />
                  <path
                    :d="trendLinePath"
                    fill="none"
                    stroke="var(--sky-500)"
                    stroke-width="2.5"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    vector-effect="non-scaling-stroke"
                  />
                </svg>
                <div class="trend-axis">
                  <span>{{ stats.trendDates[0] }}</span>
                  <span>{{ stats.trendDates[stats.trendDates.length - 1] }}</span>
                </div>
              </div>
              <div v-else class="trend-empty">近 7 天还没有收到答卷</div>
            </div>
          </el-card>
        </el-col>

        <!-- 右：最近问卷 -->
        <el-col :span="14" :xs="24" :sm="24" :md="14">
          <el-card class="panel-card">
            <template #header>
              <div class="flex-between">
                <span class="panel-title">📝 最近创建的问卷</span>
                <el-button type="primary" link @click="$router.push('/my/surveys')">
                  查看全部 →
                </el-button>
              </div>
            </template>

            <div v-if="stats.recent && stats.recent.length" class="recent-list">
              <div
                v-for="row in stats.recent"
                :key="row.id"
                class="recent-row"
                @click="goEdit(row)"
              >
                <div class="recent-icon">
                  <el-icon><Document /></el-icon>
                </div>
                <div class="recent-main">
                  <div class="recent-title">{{ row.title }}</div>
                  <div class="recent-time">{{ row.createdTime }}</div>
                </div>
                <el-tag v-if="row.status === 1" type="success" size="small" round>已发布</el-tag>
                <el-tag v-else-if="row.status === 2" type="danger" size="small" round>已结束</el-tag>
                <el-tag v-else type="info" size="small" round>草稿</el-tag>
                <el-icon class="recent-arrow"><TopRight /></el-icon>
              </div>
            </div>
            <el-empty v-else description="还没有创建过问卷" :image-size="90" />
          </el-card>
        </el-col>
      </el-row>
      <!-- ==================== 快捷入口 ==================== -->
      <el-row class="mt-20" :gutter="20">
        <el-col v-for="a in quickActions" :key="a.title" :span="6" :xs="12" :sm="12" :md="6">
          <div class="quick-tile" @click="go(a.path)">
            <div class="quick-ico" :style="{ background: a.bg, color: a.color }">
              <el-icon :size="22"><component :is="a.icon" /></el-icon>
            </div>
            <div class="quick-main">
              <div class="quick-title">{{ a.title }}</div>
              <div class="quick-desc">{{ a.desc }}</div>
            </div>
            <el-icon class="quick-arrow"><TopRight /></el-icon>
          </div>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script>
// ★ 按需引入 ECharts（不要全量 import * as echarts，见 utils/echarts.js）
import echarts from '../utils/echarts'
import { Document, Promotion, EditPen, ChatDotRound, MagicStick, Delete, User } from '@element-plus/icons-vue'
// ★ 统一接口层：统计数据走 api.survey.myStatistics()。
//   本文件在 src/views/ 下，api 在 src/api/，所以相对路径是 '../api'。
import api from '../api'
import { getUser } from '../utils/auth'
import {
  chartText, chartStrong, chartTooltip, THEME_EVENT,
  chartPalette, chartColor, chartSurface, chartNum, chartPct
} from '../utils/chartTheme'

export default {
  data() {
    return {
      userName: '',
      /** 首次进入时的骨架屏；数据回来（成功或失败）就关掉，避免一直转圈 */
      loading: true,
      stats: {
        total: 0,
        published: 0,
        draft: 0,
        ended: 0,
        responses: 0,
        recent: [],
        // 近 7 天答卷趋势（后端 /my-statistics 返回；旧后端可能没有，兜底为空数组）
        trendDates: [],
        trendCounts: [],
        recent7Answers: 0
      },
      pieChart: null,
      /** 图表容器 ref 的兜底重试标记（只重试一次，避免死循环） */
      pieRetried: false
    }
  },
  computed: {
    /**
     * 顶部四张数据卡。
     * ★ 每张卡有自己的颜色与图标：问卷 / 已发布 / 草稿 / 答卷 ——
     *   颜色不是装饰，是"同一功能在全站都用这个色"的记忆点（与列表徽标、图表一致）。
     */
    statCards() {
      return [
        { key: 'total', label: '我的问卷', value: this.stats.total, icon: Document, color: '#2f80ed', iconBg: 'linear-gradient(135deg,#e8f3ff,#d7e9ff)' },
        { key: 'published', label: '已发布', value: this.stats.published, icon: Promotion, color: '#16a34a', iconBg: 'linear-gradient(135deg,#e9f9ef,#d6f3e3)' },
        { key: 'draft', label: '草稿', value: this.stats.draft, icon: EditPen, color: '#d97706', iconBg: 'linear-gradient(135deg,#fff5e6,#ffe9c7)' },
        { key: 'responses', label: '总答卷数', value: this.stats.responses, icon: ChatDotRound, color: '#7c3aed', iconBg: 'linear-gradient(135deg,#f1ecff,#e4dcff)' }
      ]
    },
    /** 发布率：已发布 / 总数（0 份时按 0% 处理） */
    publishRate() {
      if (!this.stats.total) return 0
      return Math.round((this.stats.published / this.stats.total) * 100)
    },
    /** 平均每份问卷收到的答卷数 */
    avgResponses() {
      if (!this.stats.total) return '0.0'
      return (this.stats.responses / this.stats.total).toFixed(1)
    },
    /**
     * 近 7 天趋势的折线坐标。
     * ★ 用纯 SVG 画而不是再开一个 ECharts 实例：这张图只有一个折线 + 面积，
     *   引 chart 实例反而要多一套 init / resize / 主题重画的代码；
     *   SVG 的 stroke 直接用 CSS 变量，深色模式自动跟随，不需要 THEME_EVENT。
     */
    trendPoints() {
      const counts = this.stats.trendCounts || []
      if (counts.length === 0) return []
      const w = 280
      const h = 64
      const padX = 4
      const padY = 10
      const max = Math.max(1, ...counts)
      const step = counts.length > 1 ? (w - padX * 2) / (counts.length - 1) : 0
      return counts.map((c, i) => ({
        x: padX + i * step,
        y: h - padY - (Number(c) / max) * (h - padY * 2)
      }))
    },
    trendLinePath() {
      return this.trendPoints
        .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`)
        .join(' ')
    },
    trendAreaPath() {
      const pts = this.trendPoints
      if (!pts.length) return ''
      const first = pts[0]
      const last = pts[pts.length - 1]
      return `${this.trendLinePath} L${last.x.toFixed(1)},64 L${first.x.toFixed(1)},64 Z`
    },
    /** 7 天全是 0 时不画一条贴底的直线，直接给文案 */
    trendHasData() {
      return (this.stats.trendCounts || []).some(c => Number(c) > 0)
    },
    /**
     * 底部快捷入口。
     * ★ 新建 / AI 创建都带 query，MySurveys 挂载时会据此直接打开对应弹窗，
     *   用户少点一次；图标颜色沿用功能语义（蓝=问卷、紫=AI、红=回收站、青=个人）。
     */
    quickActions() {
      return [
        { title: '新建问卷', desc: '从空白开始创建', path: '/my/surveys?create=1', icon: Document, bg: 'linear-gradient(135deg,#e8f3ff,#d7e9ff)', color: '#2f80ed' },
        { title: 'AI 创建', desc: '一句话生成整份问卷', path: '/my/surveys?ai=1', icon: MagicStick, bg: 'linear-gradient(135deg,#f3ecff,#e6dbff)', color: '#7c3aed' },
        { title: '回收站', desc: '恢复或彻底删除', path: '/my/recycle', icon: Delete, bg: 'linear-gradient(135deg,#ffefef,#ffe0e0)', color: '#ef4444' },
        { title: '个人中心', desc: '资料与账号安全', path: '/my/profile', icon: User, bg: 'linear-gradient(135deg,#e7fbff,#d6f4ff)', color: '#0891b2' }
      ]
    }
  },
  mounted() {
    // ★ 读本地用户走 utils/auth.getUser()
    const user = getUser() || {}
    this.userName = user.nickname || user.username || '用户'
    this.loadStats()
    // 深/浅色切换后重画饼图（canvas 读不到 CSS 变量）
    window.addEventListener(THEME_EVENT, this.handleThemeChange)
  },
  beforeUnmount() {
    window.removeEventListener(THEME_EVENT, this.handleThemeChange)
    window.removeEventListener('resize', this.handleResize)
    if (this.pieChart) this.pieChart.dispose()
  },
  methods: {
    async loadStats() {
      try {
        // ★ api.survey.myStatistics() 里 http.js 自动带 Authorization，
        //   成功时 resolve 出响应体（后端返回的统计对象），直接赋给 this.stats。
        //   趋势字段做个兜底：旧后端没有这三个字段时，页面退化成"近 7 天暂无数据"，不会报错。
        const data = await api.survey.myStatistics()
        this.stats = {
          ...this.stats,
          ...data,
          trendDates: Array.isArray(data.trendDates) ? data.trendDates : [],
          trendCounts: Array.isArray(data.trendCounts) ? data.trendCounts : [],
          recent7Answers: Number(data.recent7Answers) || 0
        }
      } catch {
        // 失败提示已由 http.js 用后端 message 弹过，这里只做流程控制：
        // 保持 stats 为初始值（页面显示 0 / 空图表），不渲染饼图。
      } finally {
        this.loading = false
      }

      // ★★ 顺序很重要（本轮踩过的 bug）：图表容器在 `v-else` 分支里，
      //    `loading = false` 之后的那一帧才挂到 DOM。如果在这之前 init，
      //    `$refs.pieRef` 是 undefined → 直接 return，页面表现就是"卡片一片空白、控制台也不报错"。
      await this.$nextTick()
      this.initPie()
      // flex 等高布局下首帧宽度可能还没算完；再补一次 resize，避免画到 0 宽画布
      requestAnimationFrame(() => {
        if (this.pieChart) this.pieChart.resize()
      })
    },

    initPie() {
      if (this.stats.total === 0) return

      const el = this.$refs.pieRef
      // ★ 容器宽度为 0 时（首帧布局未完成）init 出来的画布也是 0 宽 —— 页面同样表现为空白。
      //   与 ref 缺失共用同一次重试，避免无限循环。
      if (!el || !el.clientWidth) {
        if (!this.pieRetried) {
          this.pieRetried = true
          setTimeout(() => this.initPie(), 120)
        }
        return
      }
      this.pieRetried = false

      if (this.pieChart) this.pieChart.dispose()
      this.pieChart = echarts.init(el)

      // 先移除旧监听再绑定：HMR / 重复 init 时不会叠加
      window.removeEventListener('resize', this.handleResize)
      window.addEventListener('resize', this.handleResize)

      const data = []
      if (this.stats.published > 0) data.push({ name: '已发布', value: this.stats.published, itemStyle: { color: chartColor(1) } })
      if (this.stats.draft > 0) data.push({ name: '草稿', value: this.stats.draft, itemStyle: { color: chartColor(2) } })
      if (this.stats.ended > 0) data.push({ name: '已结束', value: this.stats.ended, itemStyle: { color: chartColor(5) } })

      this.pieChart.setOption({
        color: chartPalette(),
        tooltip: {
          trigger: 'item',
          // 单位统一「份 / %」，百分比取整
          formatter: p => `${p.name}：${chartNum(p.value)} 份（${chartPct(p.percent)}%）`,
          ...chartTooltip()
        },
        // 圆环中心显示总数：比只看图例更直观
        title: {
          text: String(this.stats.total),
          subtext: '问卷总数',
          left: 'center',
          top: '34%',
          textStyle: { fontSize: 26, fontWeight: 700, color: chartStrong() },
          subtextStyle: { fontSize: 12, color: chartText() }
        },
        legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: chartText() } },
        series: [
          {
            type: 'pie',
            radius: ['54%', '76%'],
            center: ['50%', '44%'],
            avoidLabelOverlap: true,
            itemStyle: { borderColor: chartSurface(), borderWidth: 3, borderRadius: 6 },
            label: { formatter: '{b}\n{c}份', color: chartText(), fontSize: 12 },
            labelLine: { length: 12, length2: 10 },
            data
          }
        ]
      })
    },

    handleResize() {
      if (this.pieChart) this.pieChart.resize()
    },

    /** 主题切换：用新配色重画（dispose 旧实例，避免旧 canvas 残留） */
    handleThemeChange() {
      this.initPie()
    },

    goEdit(row) {
      this.$router.push(`/my/surveys/${row.id}/edit`)
    },

    go(path) {
      this.$router.push(path)
    }
  }
}
</script>

<style scoped>
/* ==================== 欢迎横幅 ==================== */
.hero {
  position: relative;
  overflow: hidden;
  border-radius: var(--r-xl);
  padding: 30px 34px;
  color: #fff;
  background:
    radial-gradient(120% 160% at 90% -20%, rgba(255, 255, 255, 0.28) 0%, transparent 55%),
    var(--brand-gradient);
  box-shadow: 0 18px 40px rgba(37, 99, 235, 0.28);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hero-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(2px);
  pointer-events: none;
}
.hero-glow-1 {
  width: 260px;
  height: 260px;
  right: -60px;
  top: -120px;
  background: rgba(255, 255, 255, 0.18);
}
.hero-glow-2 {
  width: 180px;
  height: 180px;
  left: 40%;
  bottom: -120px;
  background: rgba(86, 204, 242, 0.3);
}
.hero-content {
  position: relative;
  z-index: 1;
}
.hero-eyebrow {
  font-size: 11px;
  letter-spacing: 2px;
  opacity: 0.75;
  margin-bottom: 8px;
}
.hero-title {
  margin: 0 0 8px;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.4px;
}
.hero-sub {
  margin: 0 0 18px;
  opacity: 0.88;
  font-size: 14px;
}
.hero-actions {
  display: flex;
  gap: 12px;
}
.hero-btn {
  height: 38px;
  border-radius: 10px;
  font-weight: 600;
  border: none;
}
.hero-btn-primary {
  background: #fff;
  color: #1d4ed8;
}
.hero-btn-primary:hover {
  background: #f2f8ff;
  color: #1d4ed8;
  transform: translateY(-1px);
}
/* 右侧玻璃速览：用真实数据做"第一眼信息"，比单纯的插画更有分量 */
.hero-stats {
  position: relative;
  z-index: 1;
  display: flex;
  gap: 10px;
  padding: 12px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.14);
  border: 1px solid rgba(255, 255, 255, 0.35);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  box-shadow: 0 12px 30px rgba(9, 40, 105, 0.18);
}
.hero-stat {
  min-width: 74px;
  padding: 8px 14px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.14);
  text-align: center;
}
.hero-stat b {
  display: block;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
}
.hero-stat span {
  font-size: 11px;
  opacity: 0.82;
}

/* 一道很淡的扫光：只在横幅上，7 秒一个来回，不抢内容 */
.hero::after {
  content: '';
  position: absolute;
  inset: -30% -10%;
  background: linear-gradient(115deg, transparent 42%, rgba(255, 255, 255, 0.16) 50%, transparent 58%);
  transform: translateX(-35%);
  animation: hero-sheen 7s ease-in-out infinite;
  pointer-events: none;
}
@keyframes hero-sheen {
  0%,
  100% {
    transform: translateX(-35%);
  }
  50% {
    transform: translateX(35%);
  }
}

/* ==================== 数据卡片 ==================== */
.metric-card {
  position: relative;
  overflow: hidden;
}
.metric-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  padding: 20px;
}
.metric-icon {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 16px;
  flex-shrink: 0;
}
.metric-label {
  font-size: 13px;
  color: var(--ink-3);
  margin-bottom: 6px;
}
.metric-value {
  font-size: 30px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.5px;
  animation: num-pop 0.55s var(--ease) both;
  transform-origin: left bottom;
}
@keyframes num-pop {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.92);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}
.metric-glow {
  position: absolute;
  right: -26px;
  top: -26px;
  width: 90px;
  height: 90px;
  border-radius: 50%;
  opacity: 0.08;
}

/* ==================== 下方面板 ==================== */
.panel-card {
  height: 100%;
}
.panel-title {
  font-weight: 600;
}
.pie {
  width: 100%;
  height: 260px;
}

/* 环形图下方三个派生指标 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 10px;
}
.kpi {
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: linear-gradient(160deg, #f8fbff, #f2f8ff);
  border: 1px solid var(--stroke);
  transition:
    transform 0.22s var(--ease),
    box-shadow 0.22s var(--ease);
}
.kpi:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-sm);
}
.kpi-label {
  font-size: 12px;
  color: var(--ink-3);
}
.kpi-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--ink);
  margin-top: 4px;
  line-height: 1.2;
}
.kpi-hint {
  font-size: 11px;
  color: var(--ink-3);
  margin-top: 2px;
}
.kpi-bar {
  height: 5px;
  border-radius: 999px;
  background: #e6effc;
  margin-top: 9px;
  overflow: hidden;
}
.kpi-bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--brand-gradient);
  transition: width 0.6s var(--ease);
}

/* ==================== 近 7 天趋势 ==================== */
.trend-block {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--stroke);
}
.trend-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 6px;
}
.trend-title {
  font-size: 12px;
  color: var(--ink-3);
}
.trend-total {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
}
.trend-chart svg {
  display: block;
  width: 100%;
  height: 64px;
}
.trend-axis {
  display: flex;
  justify-content: space-between;
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-3);
}
.trend-empty {
  padding: 16px 0 6px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-3);
}

.recent-list {
  display: flex;
  flex-direction: column;
}
.recent-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 8px;
  border-bottom: 1px solid #f1f5fd;
  border-radius: 10px;
  cursor: pointer;
  transition:
    background 0.18s var(--ease),
    transform 0.18s var(--ease);
}
.recent-row:last-child {
  border-bottom: none;
}
.recent-row:hover {
  background: var(--sky-50);
  transform: translateX(2px);
}
.recent-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--sky-100);
  color: var(--sky-600);
  flex-shrink: 0;
}
.recent-main {
  flex: 1;
  min-width: 0;
}
.recent-title {
  font-weight: 500;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recent-time {
  font-size: 12px;
  color: var(--ink-3);
  margin-top: 2px;
}
.recent-arrow {
  color: var(--ink-3);
  font-size: 13px;
}

/* 两栏等高（右栏内容更长时左栏跟着变高） */
.equal-row :deep(.el-col) {
  display: flex;
}
.equal-row :deep(.el-card) {
  width: 100%;
}

/* ==================== 快捷入口 ==================== */
.quick-tile {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  border-radius: var(--r-lg);
  border: 1px solid var(--stroke);
  background: #fff;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition:
    transform 0.22s var(--ease),
    box-shadow 0.22s var(--ease),
    border-color 0.22s var(--ease);
}
.quick-tile:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-md);
  border-color: var(--sky-200);
}
.quick-ico {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.quick-main {
  flex: 1;
  min-width: 0;
}
.quick-title {
  font-weight: 600;
  color: var(--ink);
}
.quick-desc {
  font-size: 12px;
  color: var(--ink-3);
  margin-top: 2px;
}
.quick-arrow {
  color: var(--ink-3);
  font-size: 13px;
  transition:
    transform 0.2s var(--ease),
    color 0.2s var(--ease);
}
.quick-tile:hover .quick-arrow {
  transform: translate(2px, -2px);
  color: var(--sky-600);
}

@media (max-width: 1100px) {
  .hero-illustration {
    display: none;
  }
}
</style>
