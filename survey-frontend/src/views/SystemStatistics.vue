<template>
  <div>
    <!-- ==================== 顶部 6 个数据卡片 ==================== -->
    <el-row :gutter="16">
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#ecf5ff;color:#409eff;">
            <el-icon :size="24"><UserFilled /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">普通用户</div>
            <div class="stat-value" style="color:#409eff;">{{ stats.users }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#f0f9eb;color:#67c23a;">
            <el-icon :size="24"><Document /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">问卷总数</div>
            <div class="stat-value" style="color:#67c23a;">{{ stats.total }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
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
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#fdf6ec;color:#e6a23c;">
            <el-icon :size="24"><EditPen /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">草稿</div>
            <div class="stat-value" style="color:#e6a23c;">{{ stats.draft }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#fef0f0;color:#f56c6c;">
            <el-icon :size="24"><ChatDotRound /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">总答卷</div>
            <div class="stat-value" style="color:#f56c6c;">{{ stats.responses }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#f4f0ff;color:#8e44ad;">
            <el-icon :size="24"><View /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">访客次数</div>
            <div class="stat-value" style="color:#8e44ad;">{{ stats.views }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ==================== 第一行：饼图 + 趋势图 ==================== -->
    <el-row :gutter="20" style="margin-top: 20px;">
      <!-- 左：问卷状态分布 -->
      <el-col :span="10">
        <el-card style="height: 380px;">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: bold;">📊 问卷状态分布</span>
              <el-tag size="small" type="info">共 {{ stats.total }} 份</el-tag>
            </div>
          </template>
          <div v-if="stats.total > 0" ref="pieRef" style="width: 100%; height: 290px;"></div>
          <el-empty v-else description="暂无问卷数据" :image-size="80" />
        </el-card>
      </el-col>

      <!-- 右：创建趋势 -->
      <el-col :span="14">
        <el-card style="height: 380px;">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: bold;">📈 问卷创建趋势（近7天）</span>
              <el-tag size="small" type="success">近7天新增 {{ recent7DaysTotal }} 份</el-tag>
            </div>
          </template>
          <div ref="trendRef" style="width: 100%; height: 290px;"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ==================== 第二行：回收量排行 + 系统概况 ==================== -->
    <el-row :gutter="20" style="margin-top: 20px;">
      <!-- 左：问卷回收量排行榜 -->
      <el-col :span="14">
        <el-card style="height: 420px;">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: bold;">🏆 问卷回收量排行 TOP 10</span>
              <el-tag size="small" type="warning">按答卷数排序</el-tag>
            </div>
          </template>
          <el-table
            :data="ranking.slice(0, 10)"
            :show-header="true"
            stripe
            style="width: 100%;"
            max-height="340"
          >
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
                <div style="display: flex; align-items: center;">
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
      <el-col :span="10">
        <el-card style="height: 420px;">
          <template #header>
            <span style="font-weight: bold;">⚙️ 系统概况</span>
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
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { UserFilled, Document, Promotion, EditPen, ChatDotRound, View } from '@element-plus/icons-vue'

export default {
  components: { UserFilled, Document, Promotion, EditPen, ChatDotRound, View },
  data() {
    return {
      stats: { users: 0, total: 0, published: 0, draft: 0, ended: 0, responses: 0, views: 0 },
      ranking: [],          // 问卷回收量排行
      pieChart: null,
      trendChart: null,
      healthChart: null,
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
    }
  },
  mounted() {
    this.loadAll()
    window.addEventListener('resize', this.handleResize)
  },
  beforeUnmount() {
    if (this.pieChart) this.pieChart.dispose()
    if (this.trendChart) this.trendChart.dispose()
    if (this.healthChart) this.healthChart.dispose()
    window.removeEventListener('resize', this.handleResize)
  },
  methods: {
    async loadAll() {
      try {
        // 1. 系统概况
        const overviewRes = await fetch('/api/survey/system-overview')
        this.stats = await overviewRes.json()

        // 2. 问卷列表
        const listRes = await fetch('/api/survey/list')
        const surveys = await listRes.json()

        // 3. 为每份问卷查询答卷数
        const ranking = []
        for (const s of surveys) {
          const countRes = await fetch(`/api/answer/count/${s.id}`)
          const count = await countRes.json()
          ranking.push({
            title: s.title,
            status: s.status,
            createdTime: s.createdTime,
            responseCount: count
          })
        }
        ranking.sort((a, b) => b.responseCount - a.responseCount)
        this.ranking = ranking

        // 4. 计算近7天趋势
        this.calcTrend(surveys)

        // 5. 渲染图表
        this.$nextTick(() => {
          this.initPie()
          this.initTrend()
          this.initHealth()
        })
      } catch (e) {
        console.error(e)
        ElMessage.error('加载数据失败')
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

    // ==== 饼图 ====
    initPie() {
      if (this.stats.total === 0 || !this.$refs.pieRef) return
      if (this.pieChart) this.pieChart.dispose()

      this.pieChart = echarts.init(this.$refs.pieRef)

      const data = []
      if (this.stats.published > 0) data.push({ name: '已发布', value: this.stats.published, itemStyle: { color: '#67c23a' } })
      if (this.stats.draft > 0) data.push({ name: '草稿', value: this.stats.draft, itemStyle: { color: '#e6a23c' } })
      if (this.stats.ended > 0) data.push({ name: '已结束', value: this.stats.ended, itemStyle: { color: '#f56c6c' } })

      this.pieChart.setOption({
        tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
        legend: { bottom: 0, itemWidth: 12, itemHeight: 12 },
        series: [
          {
            type: 'pie',
            radius: ['45%', '70%'],
            center: ['50%', '45%'],
            avoidLabelOverlap: true,
            label: {
              show: true,
              formatter: '{b}\n{c}份',
              fontSize: 13
            },
            labelLine: { length: 10, length2: 10 },
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
        tooltip: {
          trigger: 'axis',
          formatter: '{b}<br/>新建问卷：{c} 份'
        },
        grid: { left: '3%', right: '4%', bottom: '3%', top: '10%', containLabel: true },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: this.trendDates,
          axisLine: { lineStyle: { color: '#dcdfe6' } },
          axisLabel: { color: '#909399' }
        },
        yAxis: {
          type: 'value',
          minInterval: 1,
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: { lineStyle: { color: '#f0f2f5' } },
          axisLabel: { color: '#909399' }
        },
        series: [
          {
            name: '新建问卷',
            type: 'line',
            smooth: true,
            symbol: 'circle',
            symbolSize: 8,
            data: this.trendCounts,
            itemStyle: { color: '#409eff' },
            lineStyle: { width: 3, color: '#409eff' },
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(64, 158, 255, 0.4)' },
                { offset: 1, color: 'rgba(64, 158, 255, 0.02)' }
              ])
            },
            label: {
              show: true,
              position: 'top',
              formatter: '{c}',
              color: '#409eff',
              fontWeight: 'bold'
            }
          }
        ]
      })
    },

    // ==== 系统健康度圆环 ====
    initHealth() {
      if (!this.$refs.healthRef) return
      if (this.healthChart) this.healthChart.dispose()

      this.healthChart = echarts.init(this.$refs.healthRef)

      // 计算一个综合健康分（简单加权：发布+结束的问卷占比 * 100）
      const healthScore = this.stats.total === 0 ? 0 : Math.round((this.stats.published + this.stats.ended) / this.stats.total * 100)

      this.healthChart.setOption({
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
                color: healthScore >= 70 ? '#67c23a' : healthScore >= 40 ? '#e6a23c' : '#f56c6c'
              }
            },
            axisLine: { lineStyle: { width: 14, color: [[1, '#f0f2f5']] } },
            pointer: { show: false },
            axisTick: { show: false },
            splitLine: { show: false },
            axisLabel: { show: false },
            detail: {
              valueAnimation: true,
              formatter: '{value}\n活跃度',
              fontSize: 18,
              fontWeight: 'bold',
              color: '#303133',
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