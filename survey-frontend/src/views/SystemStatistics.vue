<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="page-title">系统统计</h2>
        <p class="page-subtitle">全站用户、问卷与答卷的整体概览</p>
      </div>
    </div>

    <!-- ==================== 顶部 6 个数据卡片 ==================== -->
    <el-row :gutter="16">
      <el-col :span="4" :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#ecf5ff;color:#409eff;">
            <el-icon :size="24"><UserFilled /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">普通用户</div>
            <div class="stat-value color-primary">{{ stats.users }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4" :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#f0f9eb;color:#67c23a;">
            <el-icon :size="24"><Document /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">问卷总数</div>
            <div class="stat-value color-success">{{ stats.total }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4" :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#f0f9eb;color:#85ce61;">
            <el-icon :size="24"><Promotion /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">已发布</div>
            <div class="stat-value" style="color:#85ce61;">{{ stats.published }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4" :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#fdf6ec;color:#e6a23c;">
            <el-icon :size="24"><EditPen /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">草稿</div>
            <div class="stat-value color-warning">{{ stats.draft }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4" :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#fef0f0;color:#f56c6c;">
            <el-icon :size="24"><ChatDotRound /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">总答卷</div>
            <div class="stat-value color-danger">{{ stats.responses }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4" :xs="12" :sm="8" :md="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#f4f0ff;color:#8e44ad;">
            <el-icon :size="24"><View /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">访客次数</div>
            <div class="stat-value color-purple">{{ stats.views }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ==================== 第一行：饼图 + 趋势图 ==================== -->
    <el-row class="mt-20" :gutter="20">
      <!-- 左：问卷状态分布 -->
      <el-col :span="10" :xs="24" :sm="24" :md="10">
        <el-card style="height: 380px;">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">📊 问卷状态分布</span>
              <el-tag size="small" type="info">共 {{ stats.total }} 份</el-tag>
            </div>
          </template>
          <div v-if="stats.total > 0" ref="pieRef" style="width: 100%; height: 290px;"></div>
          <el-empty v-else description="暂无问卷数据" :image-size="80" />
        </el-card>
      </el-col>

      <!-- 右：创建趋势 -->
      <el-col :span="14" :xs="24" :sm="24" :md="14">
        <el-card style="height: 380px;">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">📈 问卷创建趋势（近7天）</span>
              <el-tag size="small" type="success">近7天新增 {{ recent7DaysTotal }} 份</el-tag>
            </div>
          </template>
          <div ref="trendRef" style="width: 100%; height: 290px;"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ==================== 新增：答卷分布 + 问卷构成 ==================== -->
    <el-row class="mt-20" :gutter="20">
      <el-col :span="12" :xs="24" :sm="12">
        <el-card style="height: 360px;">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">📦 问卷答卷量分布</span>
              <el-tag size="small" type="info">共 {{ stats.total }} 份问卷</el-tag>
            </div>
          </template>
          <div ref="bucketRef" style="width: 100%; height: 270px;"></div>
        </el-card>
      </el-col>
      <el-col :span="12" :xs="24" :sm="12">
        <el-card style="height: 360px;">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">🧩 问卷构成（模板 / 普通）</span>
              <el-tag size="small" type="warning">模板 {{ templateCount }} 份</el-tag>
            </div>
          </template>
          <div ref="typeRef" style="width: 100%; height: 270px;"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ==================== 第二行：回收量排行 + 系统概况 ==================== -->
    <el-row class="mt-20" :gutter="20">
      <!-- 左：问卷回收量排行榜 -->
      <el-col :span="14" :xs="24" :sm="24" :md="14">
        <el-card style="height: 420px;">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">🏆 问卷回收量排行 TOP 10</span>
              <el-tag size="small" type="warning">按答卷数排序</el-tag>
            </div>
          </template>
          <el-table class="w-full" :data="ranking.slice(0, 10)" :show-header="true"
            stripe max-height="340">
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
            <el-table-column label="答卷数" width="200" align="center">
              <template #default="{ row }">
                <div class="flex-center">
                  <el-progress
                    :percentage="Math.min(100, row.responseCount / maxResponse * 100)"
                    :stroke-width="10"
                    :show-text="false"
                    style="flex: 1; margin-right: 8px;"
                    :color="getProgressColor(row.status)"
                  />
                  <span style="font-weight: 600; color: #f56c6c; min-width: 36px; text-align: right;">
                    {{ row.responseCount }}
                  </span>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="ranking.length === 0" description="暂无数据" :image-size="60" />
        </el-card>
      </el-col>

      <!-- 右：系统概况 -->
      <el-col :span="10" :xs="24" :sm="24" :md="10">
        <el-card style="height: 420px;">
          <template #header>
            <span class="text-bold">⚙️ 系统概况</span>
          </template>

          <!-- 系统健康度圆环 -->
          <div style="text-align: center; margin-bottom: 12px;">
            <div ref="healthRef" style="width: 100%; height: 150px;"></div>
          </div>

          <!-- 关键指标 -->
          <div class="metric-list">
            <div class="metric-row">
              <span class="metric-name">答卷回收率</span>
              <span class="metric-value">{{ responseRate }}%</span>
            </div>
            <div class="metric-row">
              <span class="metric-name">平均每份问卷答卷</span>
              <span class="metric-value">{{ avgResponsePerSurvey }} 份</span>
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
</template>

<script>
// ★ 按需引入 ECharts（不要全量 import * as echarts，见 utils/echarts.js）
//   本页用到：饼图、折线图、仪表盘，以及 graphic.LinearGradient 做渐变填充
import echarts from '../utils/echarts'
// ★ 统一接口层：本页只调 **2 个接口方法**（systemOverview + listWithResponseCount）——
//   概况 1 次、问卷列表 1 次；每份问卷的答卷数由后端批量 GROUP BY 一次带回，
//   而不是逐份 count（那会是 1 概况 + 1 列表 + N 次逐份答卷数请求）。
//   注意这里不 import ElMessage：catch 里的失败提示统一由 src/api/http.js 弹出，
//   页面层重复弹会一次失败弹两个框。
import api from '../api'
import {
  chartText, chartStrong, chartSplit, chartTooltip, THEME_EVENT,
  chartPalette, chartColor, chartSurface, chartEmptyGraphic, chartNum, chartPct
} from '../utils/chartTheme'
// 本页图标均为模板标签形式，已由 main.js 全局注册，无需 import

// 图标已在 main.js 全局注册，不再重复注册（View 是 HTML 保留标签名）
export default {
  data() {
    return {
      stats: { users: 0, total: 0, published: 0, draft: 0, ended: 0, responses: 0, views: 0 },
      ranking: [],          // 问卷回收量排行
      pieChart: null,
      trendChart: null,
      healthChart: null,
      bucketChart: null,
      typeChart: null,
      templateCount: 0,
      // 趋势图数据
      trendDates: [],
      trendCounts: [],
      recent7DaysTotal: 0
    }
  },
  computed: {
    maxResponse() {
      return Math.max(...this.ranking.map(r => r.responseCount), 1)
    },
    responseRate() {
      // 答卷回收率 = 总答卷 / (已发布问卷数 * 一个假设的满额数)。这里用简单公式：总答卷 / 已发布问卷数 取整
      if (this.stats.published === 0) return 0
      return Math.min(100, Math.round(this.stats.responses / (this.stats.published * 10) * 100))
    },
    avgResponsePerSurvey() {
      if (this.stats.published === 0) return 0
      return (this.stats.responses / this.stats.published).toFixed(1)
    },
    publishRate() {
      if (this.stats.total === 0) return 0
      return Math.round((this.stats.published + this.stats.ended) / this.stats.total * 100)
    },
    viewConvertRate() {
      if (this.stats.views === 0) return 0
      return Math.min(100, Math.round(this.stats.responses / this.stats.views * 100))
    },
    /** 答卷量分桶（给柱状图用） */
    buckets() {
      const ranges = [
        { label: '0 份', min: 0, max: 0 },
        { label: '1-5 份', min: 1, max: 5 },
        { label: '6-20 份', min: 6, max: 20 },
        { label: '21-50 份', min: 21, max: 50 },
        { label: '50+ 份', min: 51, max: Number.MAX_SAFE_INTEGER }
      ]
      return ranges.map(r => ({
        label: r.label,
        count: this.ranking.filter(s => s.responseCount >= r.min && s.responseCount <= r.max).length
      }))
    }
  },
  mounted() {
    this.loadAll()
    window.addEventListener('resize', this.handleResize)
    window.addEventListener(THEME_EVENT, this.handleThemeChange)
  },
  beforeUnmount() {
    if (this.pieChart) this.pieChart.dispose()
    if (this.trendChart) this.trendChart.dispose()
    if (this.healthChart) this.healthChart.dispose()
    if (this.bucketChart) this.bucketChart.dispose()
    if (this.typeChart) this.typeChart.dispose()
    window.removeEventListener('resize', this.handleResize)
    window.removeEventListener(THEME_EVENT, this.handleThemeChange)
  },
  methods: {
    async loadAll() {
      try {
        // 1. 系统概况：api.survey.systemOverview() 内部走 http.get
        //    （统一带 token、统一按状态码判成败）。
        this.stats = await api.survey.systemOverview()

        // 2. 问卷列表 + 每份的答卷数（一次请求拿到，替代逐份 count 的 N+1 做法）
        //    逐份做法是 `for (const s of surveys) { await api.answer.count(s.id) }` ——
        //    n 份问卷就要 n 次**串行**往返（10 份 = 1+1+10 次请求，越往后越慢，
        //    问卷上百时这个页面要转很久）。后端本来就批量算好了答卷数，
        //    只是缺一个"问卷 + 答卷数一起返回"的端点，本页用的就是这个端点。
        const rows = await api.survey.listWithResponseCount()

        // 3. 组装排行（字段与逐份查询时一致）
        const ranking = rows.map(s => ({
          title: s.title,
          status: s.status,
          createdTime: s.createdTime,
          responseCount: s.responseCount
        }))
        ranking.sort((a, b) => b.responseCount - a.responseCount)
        this.ranking = ranking

        // 4. 计算近7天趋势（只用到 createdTime，rows 里已经带了）
        this.calcTrend(rows)

        // 5. 模板数量（用于"模板 / 普通"构成图；失败不影响整页）
        try {
          const tpl = await api.survey.templates()
          this.templateCount = Array.isArray(tpl) ? tpl.length : 0
        } catch {
          this.templateCount = 0
        }

        // 6. 渲染图表
        this.$nextTick(() => {
          this.initPie()
          this.initTrend()
          this.initHealth()
          this.initBucket()
          this.initType()
        })
      } catch (e) {
        // ★ 失败提示由 src/api/http.js 统一弹出（非 2xx / 网络异常都会弹），
        //   页面层再弹一次会重复，所以这里只留控制台日志便于排查，属于流程控制。
        console.error(e)
      }
    },

    calcTrend(surveys) {
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      const dates = []
      const counts = []
      for (let i = 6; i >= 0; i--) {
        const d = new Date(today)
        d.setDate(d.getDate() - i)
        const pad = n => String(n).padStart(2, '0')
        const key = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
        dates.push(`${pad(d.getMonth() + 1)}-${pad(d.getDate())}`)
        // 统计这一天创建的问卷数
        const cnt = surveys.filter(s => (s.createdTime || '').startsWith(key)).length
        counts.push(cnt)
      }
      this.trendDates = dates
      this.trendCounts = counts
      this.recent7DaysTotal = counts.reduce((a, b) => a + b, 0)
    },

    /** 主题切换：销毁三张图，用新配色重画（不重新请求数据） */
    async handleThemeChange() {
      const charts = [this.pieChart, this.trendChart, this.healthChart, this.bucketChart, this.typeChart]
      charts.forEach(c => c && c.dispose())
      this.pieChart = null
      this.trendChart = null
      this.healthChart = null
      this.bucketChart = null
      this.typeChart = null
      await this.$nextTick()
      this.initPie()
      this.initTrend()
      this.initHealth()
      this.initBucket()
      this.initType()
    },

    // ==== 饼图 ====
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
          // 单位统一「份 / %」，百分比取整
          formatter: p => `${p.name}：${chartNum(p.value)} 份（${chartPct(p.percent)}%）`,
          ...chartTooltip()
        },
        // ★ 与「问卷构成」环形图保持同一套视觉：中心总数 + 底部圆形图例 + 同宽圆环
        title: {
          text: String(this.stats.total),
          subtext: '问卷总数',
          left: 'center',
          top: '34%',
          textStyle: { fontSize: 24, fontWeight: 700, color: chartStrong() },
          subtextStyle: { fontSize: 12, color: chartText() }
        },
        legend: { bottom: 0, icon: 'circle', itemWidth: 8, itemHeight: 8, textStyle: { color: chartText() } },
        series: [
          {
            type: 'pie',
            radius: ['54%', '76%'],
            center: ['50%', '45%'],
            avoidLabelOverlap: true,
            itemStyle: { borderColor: chartSurface(), borderWidth: 3, borderRadius: 6 },
            label: {
              show: true,
              formatter: '{b}\n{c}份',
              fontSize: 12,
              color: chartText()
            },
            labelLine: { length: 10, length2: 8 },
            data
          }
        ]
      })
    },

    // ==== 趋势折线图 ====
    initTrend() {
      if (!this.$refs.trendRef) return
      if (this.trendChart) this.trendChart.dispose()

      this.trendChart = echarts.init(this.$refs.trendRef)

      this.trendChart.setOption({
        color: chartPalette(),
        // 空数据态：近 7 天一条新增都没有时，别让用户对着一张全 0 的折线猜
        graphic: this.recent7DaysTotal === 0 ? chartEmptyGraphic('近 7 天暂无新增问卷') : [],
        tooltip: {
          trigger: 'axis',
          formatter: params => {
            const p = Array.isArray(params) ? params[0] : params
            return `${p.name}<br/>新建问卷：${chartNum(p.value)} 份`
          },
          ...chartTooltip()
        },
        grid: { left: '3%', right: '4%', bottom: '3%', top: '10%', containLabel: true },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: this.trendDates,
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
            symbolSize: 8,
            data: this.trendCounts,
            itemStyle: { color: chartColor(0) },
            lineStyle: { width: 3, color: chartColor(0) },
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(47, 128, 237, 0.4)' },
                { offset: 1, color: 'rgba(47, 128, 237, 0.02)' }
              ])
            },
            label: {
              show: true,
              position: 'top',
              formatter: '{c}',
              color: chartColor(0),
              fontWeight: 'bold'
            }
          }
        ]
      })
    },

    // ==== 答卷量分布（柱状） ====
    initBucket() {
      if (!this.$refs.bucketRef) return
      if (this.bucketChart) this.bucketChart.dispose()
      this.bucketChart = echarts.init(this.$refs.bucketRef)

      const data = this.buckets
      // 空数据态：所有分桶都是 0 时给一句文案，而不是一排空白柱子
      const hasData = data.some(d => Number(d.count) > 0)
      this.bucketChart.setOption({
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
        series: [
          {
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
          }
        ]
      })
    },

    // ==== 模板 / 普通问卷构成（环形） ====
    initType() {
      if (!this.$refs.typeRef) return
      if (this.typeChart) this.typeChart.dispose()
      this.typeChart = echarts.init(this.$refs.typeRef)

      const normal = Math.max((this.stats.total || 0) - this.templateCount, 0)
      const data = [
        { name: '普通问卷', value: normal, itemStyle: { color: chartColor(0) } },
        { name: '模板问卷', value: this.templateCount, itemStyle: { color: chartColor(2) } }
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
          text: String(this.stats.total || 0),
          subtext: '问卷总数',
          left: 'center',
          top: '34%',
          textStyle: { fontSize: 24, fontWeight: 700, color: chartStrong() },
          subtextStyle: { fontSize: 12, color: chartText() }
        },
        series: [
          {
            type: 'pie',
            radius: ['54%', '76%'],
            center: ['50%', '45%'],
            itemStyle: { borderColor: chartSurface(), borderWidth: 3, borderRadius: 6 },
            label: { formatter: '{b}\n{c}份', color: chartText(), fontSize: 12 },
            labelLine: { length: 10, length2: 8 },
            data
          }
        ]
      }

      if (data.length === 0) {
        // 空数据态：没有普通/模板问卷时，不画空白圆环
        option.title = undefined
        option.legend = undefined
        option.series = []
        option.graphic = chartEmptyGraphic('暂无问卷数据')
      }

      this.typeChart.setOption(option)
    },

    // ==== 系统健康度圆环 ====
    initHealth() {
      if (!this.$refs.healthRef) return
      if (this.healthChart) this.healthChart.dispose()

      this.healthChart = echarts.init(this.$refs.healthRef)

      // 计算一个综合健康分（简单加权：发布+结束的问卷占比 * 100）
      const healthScore = this.stats.total === 0 ? 0 : Math.round((this.stats.published + this.stats.ended) / this.stats.total * 100)

      // 空数据态：一份问卷都没有时，健康分没有意义，直接给文案
      if (this.stats.total === 0) {
        this.healthChart.setOption({
          graphic: chartEmptyGraphic('暂无问卷数据'),
          series: []
        })
        return
      }

      this.healthChart.setOption({
        color: chartPalette(),
        series: [
          {
            type: 'gauge',
            startAngle: 210,
            endAngle: -30,
            min: 0,
            max: 100,
            radius: '95%',
            progress: {
              show: true,
              width: 14,
              itemStyle: {
                color: healthScore >= 70 ? chartColor(1) : healthScore >= 40 ? chartColor(2) : chartColor(5)
              }
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
            data: [{ value: healthScore }]
          }
        ]
      })
    },

    handleResize() {
      if (this.pieChart) this.pieChart.resize()
      if (this.trendChart) this.trendChart.resize()
      if (this.healthChart) this.healthChart.resize()
    },

    getProgressColor(status) {
      if (status === 1) return '#67c23a'
      if (status === 2) return '#f56c6c'
      return '#e6a23c'
    }
  }
}
</script>

<style scoped>
.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  padding: 16px;
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 12px;
  flex-shrink: 0;
}
.stat-content { flex: 1; min-width: 0; }
.stat-label {
  font-size: 12px;
  color: #999;
  margin-bottom: 4px;
}
.stat-value {
  font-size: 22px;
  font-weight: bold;
  line-height: 1;
}

/* 排行榜徽章 */
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

/* 系统概况指标 */
.metric-list {
  padding: 4px 8px;
}
.metric-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
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
</style>