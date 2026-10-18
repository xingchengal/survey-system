<template>
  <div class="result-page">
    <!-- 加载骨架 -->
    <div v-if="loading" class="result-card">
      <div class="sk-block" style="height: 120px; margin-bottom: 16px;"></div>
      <div v-for="i in 3" :key="i" class="sk-block" style="height: 88px; margin-bottom: 12px;"></div>
    </div>

    <!-- 未公开 / 链接失效 -->
    <div v-else-if="!data" class="result-card">
      <el-result icon="warning" title="结果暂不可查看">
        <template #sub-title>
          <p class="muted">这份问卷没有开启公开结果，或链接已经失效。</p>
        </template>
        <template #extra>
          <el-button @click="goHome">返回首页</el-button>
        </template>
      </el-result>
    </div>

    <template v-else>
      <!-- 顶部：标题 + 概览 -->
      <header class="result-hero">
        <div class="hero-glow"></div>
        <div class="hero-badge">PUBLIC RESULT · 公开结果</div>
        <h1 class="hero-title">{{ data.title || '问卷结果' }}</h1>
        <div class="hero-stats">
          <div class="hero-stat">
            <b>{{ data.answerCount }}</b>
            <span>份答卷</span>
          </div>
          <div class="hero-stat">
            <b>{{ questions.length }}</b>
            <span>道题目</span>
          </div>
        </div>
        <div class="hero-actions">
          <el-button class="hero-btn" @click="goFill">我也要填这份问卷</el-button>
        </div>
      </header>

      <!-- 逐题结果 -->
      <div class="result-card">
        <div v-if="questions.length === 0" class="muted center">这份问卷还没有题目</div>

        <section v-for="(item, index) in questions" :key="item.question.id" class="q-block">
          <div class="q-head">
            <span class="q-index">{{ index + 1 }}</span>
            <span class="q-title">{{ item.question.title }}</span>
            <el-tag size="small" effect="plain" round>{{ typeLabel(item.question.type) }}</el-tag>
          </div>

          <!-- 矩阵：行 × 列分布 -->
          <div v-if="isMatrix(item.question.type)">
            <div v-if="item.chartData && item.chartData.length" class="matrix-wrap">
              <table class="pub-matrix">
                <thead>
                  <tr>
                    <th></th>
                    <th v-for="(col, ci) in matrixCols(item)" :key="'pc' + ci">{{ col }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(row, ri) in matrixRows(item)" :key="'pr' + ri">
                    <th>{{ row }}</th>
                    <td v-for="(col, ci) in matrixCols(item)" :key="'pd' + ci">
                      {{ matrixCell(item, row, col) }}<small> · {{ matrixPct(item, row, col) }}%</small>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else class="muted center">暂无回答数据</div>
          </div>

          <!-- 排序：平均名次 -->
          <div v-else-if="isRanking(item.question.type)">
            <div v-if="item.chartData && item.chartData.length" class="bars">
              <div v-for="(row, ri) in sortedRanking(item)" :key="'rk' + ri" class="bar-row">
                <span class="bar-label">{{ ri + 1 }}. {{ row.content }}</span>
                <div class="bar-track">
                  <i :style="{ width: rankingBarWidth(item, row) }"></i>
                </div>
                <span class="bar-value">平均 {{ rankingAvg(row) }} 名</span>
              </div>
            </div>
            <div v-else class="muted center">暂无回答数据</div>
          </div>

          <!-- NPS：净推荐值 + 分布 -->
          <div v-else-if="isNps(item.question.type)">
            <div v-if="item.chartData && item.chartData.length" class="bars">
              <p class="q-avg nps-public">
                NPS {{ npsOf(item).score == null ? '—' : npsOf(item).score }} ·
                推荐者 {{ npsOf(item).promoters }} / 被动者 {{ npsOf(item).passives }} / 贬损者 {{ npsOf(item).detractors }}
              </p>
              <div v-for="row in item.chartData" :key="'nps' + row.content" class="bar-row">
                <span class="bar-label">{{ row.content }} 分</span>
                <div class="bar-track">
                  <i :class="'nps-fill-' + npsBucket(row.content)"
                     :style="{ width: percent(item.chartData, row.count) + '%' }"></i>
                </div>
                <span class="bar-value">{{ row.count }} 人 · {{ percent(item.chartData, row.count) }}%</span>
              </div>
            </div>
            <div v-else class="muted center">暂无回答数据</div>
          </div>

          <!-- 普通选项 / 评分：横向条形 -->
          <template v-else-if="hasChart(item.question.type)">
            <div v-if="item.chartData && item.chartData.length" class="bars">
              <div v-for="row in item.chartData" :key="row.content" class="bar-row">
                <span class="bar-label">{{ barLabel(item.question.type, row.content) }}</span>
                <div class="bar-track">
                  <i :style="{ width: percent(item.chartData, row.count) + '%' }"></i>
                </div>
                <span class="bar-value">{{ row.count }} 人 · {{ percent(item.chartData, row.count) }}%</span>
              </div>
              <p v-if="isRating(item.question.type)" class="q-avg">
                平均 {{ ratingAverage(item.chartData) == null ? '—' : ratingAverage(item.chartData).toFixed(1) }} 分
              </p>
            </div>
            <div v-else class="muted center">暂无回答数据</div>
          </template>

          <!-- 文本类：公开页不展示原文（可能含个人信息） -->
          <div v-else class="text-hidden">
            该题是开放填写，回答原文不公开
          </div>
        </section>
      </div>

      <p class="footer-note">数据来自真实答卷 · 由问卷作者开启「公开结果」后展示</p>
    </template>
  </div>
</template>

<script>
// ★ 公开结果页：**匿名可访问**，所以不引用任何需要登录态的组件/接口。
//   后端只返回脱敏统计（不含填空题原文），这里也不做任何"补充查询"。
import api from '../api'
import {
  typeLabel, hasChart, isRating, ratingAverage,
  isMatrix, isRanking, isNps, parseMatrixAxis,
  npsSummary, npsBucket
} from '../utils/questionTypes'

export default {
  name: 'PublicResult',
  data() {
    return {
      loading: true,
      shareCode: '',
      data: null,
      ratingAverage
    }
  },
  computed: {
    questions() {
      return (this.data && this.data.questions) || []
    }
  },
  mounted() {
    this.shareCode = this.$route.params.shareCode
    this.load()
  },
  methods: {
    typeLabel,
    hasChart,
    isRating,
    isMatrix,
    isRanking,
    isNps,
    npsBucket,
    async load() {
      try {
        this.data = await api.survey.result(this.shareCode)
      } catch {
        // 未开启公开结果 / 链接失效 / 网络异常：统一走"结果暂不可查看"空态
        this.data = null
      } finally {
        this.loading = false
      }
    },
    /** 评分题把 content（"1"~"5"）补上"分"；其他题型保持原文 */
    barLabel(type, content) {
      return isRating(type) ? `${content} 分` : content
    },
    percent(list, count) {
      const total = (list || []).reduce((sum, item) => sum + Number(item.count || 0), 0)
      if (!total) return 0
      return Math.round((Number(count || 0) / total) * 100)
    },

    // ===== 矩阵 =====
    matrixRows(item) {
      return parseMatrixAxis(item.question.matrixRows)
    },
    matrixCols(item) {
      return parseMatrixAxis(item.question.matrixCols)
    },
    matrixCell(item, row, col) {
      const found = (item.chartData || []).find(d => d.row === row && d.content === col)
      return found ? Number(found.count || 0) : 0
    },
    matrixRowTotal(item, row) {
      return (item.chartData || [])
        .filter(d => d.row === row)
        .reduce((sum, d) => sum + Number(d.count || 0), 0)
    },
    matrixPct(item, row, col) {
      const total = this.matrixRowTotal(item, row)
      return total > 0 ? Math.round(this.matrixCell(item, row, col) * 100 / total) : 0
    },

    // ===== 排序 =====
    sortedRanking(item) {
      return (item.chartData || []).slice().sort((a, b) => Number(a.avgRank) - Number(b.avgRank))
    },
    rankingAvg(row) {
      const avg = Number(row.avgRank)
      return Number.isFinite(avg) ? avg.toFixed(2) : '—'
    },
    rankingBarWidth(item, row) {
      const total = (item.chartData || []).length
      const avg = Number(row.avgRank)
      if (!total || !Number.isFinite(avg)) return '0%'
      return `${Math.max(8, Math.round((1 - (avg - 1) / total) * 100))}%`
    },

    // ===== NPS =====
    npsOf(item) {
      return npsSummary(item.chartData)
    },
    goFill() {
      this.$router.push(`/s/${this.shareCode}`)
    },
    goHome() {
      this.$router.push('/')
    }
  }
}
</script>

<style scoped>
.result-page {
  min-height: 100vh;
  padding: 32px 20px 48px;
  background:
    radial-gradient(900px 460px at 10% 0%, #eaf5ff 0%, transparent 60%),
    radial-gradient(760px 420px at 95% 10%, #e7edff 0%, transparent 55%),
    var(--surface-2);
}
.result-card {
  max-width: 760px;
  margin: 0 auto 18px;
  padding: 22px 24px;
  background: #fff;
  border: 1px solid var(--stroke);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-sm);
}
.result-hero {
  position: relative;
  overflow: hidden;
  max-width: 760px;
  margin: 0 auto 18px;
  padding: 30px 32px;
  border-radius: var(--r-xl);
  color: #fff;
  background:
    radial-gradient(120% 160% at 90% -20%, rgba(255, 255, 255, 0.28) 0%, transparent 55%),
    var(--brand-gradient);
  box-shadow: 0 18px 40px rgba(37, 99, 235, 0.28);
}
.hero-glow {
  position: absolute;
  width: 240px;
  height: 240px;
  right: -70px;
  bottom: -120px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.18);
  pointer-events: none;
}
.hero-badge {
  font-size: 11px;
  letter-spacing: 2px;
  opacity: 0.8;
}
.hero-title {
  margin: 10px 0 18px;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.3px;
}
.hero-stats {
  display: flex;
  gap: 12px;
  margin-bottom: 18px;
}
.hero-stat {
  min-width: 96px;
  padding: 10px 16px;
  border-radius: 14px;
  text-align: center;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.32);
}
.hero-stat b {
  display: block;
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
}
.hero-stat span {
  font-size: 12px;
  opacity: 0.85;
}
.hero-btn {
  border: none;
  border-radius: 10px;
  background: #fff;
  color: #1d4ed8;
  font-weight: 600;
}
.q-block {
  padding: 18px 0;
  border-bottom: 1px dashed var(--stroke);
}
.q-block:last-child {
  border-bottom: none;
}
.q-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.q-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 8px;
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 700;
  color: #fff;
  background: var(--brand-gradient);
}
.q-title {
  flex: 1;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1.5;
}
.bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
/* 矩阵：小表格（公开页空间更窄，字号比后台统计页小一号） */
.matrix-wrap {
  overflow-x: auto;
}
.pub-matrix {
  width: 100%;
  border-collapse: collapse;
}
.pub-matrix th,
.pub-matrix td {
  padding: 7px 10px;
  border: 1px solid var(--stroke);
  font-size: 12px;
  text-align: center;
}
.pub-matrix thead th {
  background: var(--surface-2);
  color: var(--ink-2);
  font-weight: 600;
}
.pub-matrix tbody th {
  text-align: left;
  font-weight: 500;
  white-space: nowrap;
  color: var(--ink-2);
}
.pub-matrix small {
  color: var(--ink-3);
}
.nps-public {
  margin-bottom: 4px;
}
.nps-fill-promoter {
  background: #34d399;
}
.nps-fill-passive {
  background: #fbbf24;
}
.nps-fill-detractor {
  background: #f87171;
}
.bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.bar-label {
  width: 120px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bar-track {
  flex: 1;
  height: 12px;
  border-radius: 999px;
  background: #eef4fd;
  overflow: hidden;
}
.bar-track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--btn-gradient);
  transition: width 0.5s var(--ease);
}
.bar-value {
  width: 110px;
  flex-shrink: 0;
  text-align: right;
  font-size: 12px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.q-avg {
  margin: 2px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: #b45309;
}
.text-hidden {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--surface-2);
  font-size: 13px;
  color: var(--ink-3);
}
.muted {
  color: var(--ink-3);
  font-size: 13px;
}
.center {
  text-align: center;
  padding: 16px 0;
}
.footer-note {
  max-width: 760px;
  margin: 6px auto 0;
  text-align: center;
  font-size: 12px;
  color: var(--ink-3);
}
@media (max-width: 640px) {
  .bar-label {
    width: 84px;
  }
  .bar-value {
    width: 88px;
  }
}
</style>
