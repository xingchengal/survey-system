<template>
  <div class="stats-page">
    <!-- ==================== 头部 ==================== -->
    <div class="page-header">
      <div>
        <h2 class="page-title">答卷统计</h2>
        <p class="page-subtitle">{{ surveyTitle || '加载中…' }} · 数据来自真实答卷</p>
      </div>
      <div class="header-actions">
        <el-button @click="$router.push('/my/surveys')">返回</el-button>
        <el-button :disabled="responseCount === 0" @click="openAnswerList">
          <el-icon><View /></el-icon> 查看答卷
        </el-button>
        <el-button :disabled="responseCount === 0" @click="openShare">
          <el-icon><Share /></el-icon> 分享报告
        </el-button>
        <el-tooltip content="导出消耗 1 点额度 / 次（管理员免费）" placement="top">
          <el-button type="primary" :disabled="responseCount === 0" @click="exportExcel">
            <el-icon><Download /></el-icon> 导出 Excel
          </el-button>
        </el-tooltip>
      </div>
    </div>

    <!-- ==================== 骨架屏 ==================== -->
    <div v-if="loading" class="stats-skeleton">
      <el-row :gutter="20" class="mb-20">
        <el-col v-for="i in 4" :key="i" :span="6"><div class="sk-block" style="height: 96px;"></div></el-col>
      </el-row>
      <div class="sk-block" style="height: 340px; margin-bottom: 20px;"></div>
      <div class="sk-block" style="height: 340px;"></div>
    </div>

    <template v-else>
      <!-- ==================== 四个概览 ==================== -->
      <el-row :gutter="20" class="mb-20">
        <el-col v-for="tile in tiles" :key="tile.key" :span="scoreSummary ? 4 : 6">
          <div class="stat-tile">
            <div class="stat-ico" :style="{ background: tile.bg, color: tile.color }">
              <el-icon :size="22"><component :is="tile.icon" /></el-icon>
            </div>
            <div>
              <div class="stat-label">{{ tile.label }}</div>
              <div class="stat-num" :style="{ color: tile.color }">{{ tile.value }}</div>
            </div>
          </div>
        </el-col>
      </el-row>

      <!-- ==================== 质控条 ==================== -->
      <div v-if="quality && quality.suspicious > 0" class="quality-bar">
        <div class="quality-main">
          <div class="quality-icon"><el-icon :size="20"><Warning /></el-icon></div>
          <div>
            <div class="quality-title">
              可疑样本 {{ quality.suspicious }} 份
              <span class="quality-rate">（占 {{ quality.suspiciousRate }}%）</span>
            </div>
            <div class="quality-reasons" :title="qualityHint">
              <el-tag
                v-for="r in quality.reasons"
                :key="r.code"
                size="small"
                effect="plain"
                round
                class="reason-tag"
              >
                {{ r.label }} · {{ r.count }}
              </el-tag>
            </div>
          </div>
        </div>
        <div class="quality-switch">
          <span class="quality-switch-label">统计时剔除可疑样本</span>
          <el-switch v-model="excludeInvalid" @change="reloadAll" />
        </div>
      </div>

      <!-- ==================== 题目列表 ==================== -->
      <el-empty v-if="questions.length === 0" description="该问卷还没有题目" />

      <div v-else class="question-list">
        <el-card v-for="(q, qIndex) in questions" :key="q.id" class="q-card">
          <div class="q-head">
            <div class="q-index">{{ qIndex + 1 }}</div>
            <div class="q-title-wrap">
              <div class="q-title">{{ q.title }}</div>
              <div class="q-meta">
                <el-tag :type="typeTag(q.type)" size="small" effect="light" round>{{ typeName(q.type) }}</el-tag>
                <span class="q-count">{{ answerSummary(q) }}</span>
              </div>
            </div>
          </div>

          <!-- 矩阵题：按行展示列分布 -->
          <div v-if="isMatrixQ(q)" class="matrix-stats">
            <template v-if="q.chartData && q.chartData.length > 0">
              <div class="matrix-table-wrap">
                <table class="stat-matrix">
                  <thead>
                    <tr>
                      <th></th>
                      <th v-for="(col, ci) in matrixCols(q)" :key="'sc' + ci">{{ col }}</th>
                      <th class="stat-matrix-total">行合计</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, ri) in matrixRows(q)" :key="'sr' + ri">
                      <th>{{ row }}</th>
                      <td v-for="(col, ci) in matrixCols(q)" :key="'sd' + ci">
                        <span class="matrix-count">{{ matrixCell(q, row, col) }}</span>
                        <span class="matrix-pct">{{ matrixPct(q, row, col) }}%</span>
                      </td>
                      <td class="stat-matrix-total">{{ matrixRowTotal(q, row) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p class="stats-tip">百分比按每一行自己的回答数计算（每行互不影响）。</p>
            </template>
            <el-empty v-else description="暂无回答数据" :image-size="70" />
          </div>

          <!-- 排序题：平均名次越小越靠前 -->
          <div v-else-if="isRankingQ(q)" class="ranking-stats">
            <template v-if="q.chartData && q.chartData.length > 0">
              <div v-for="(item, idx) in sortedRanking(q)" :key="'rk' + idx" class="rank-row">
                <span class="rank-pos">{{ idx + 1 }}</span>
                <span class="rank-label">{{ item.content }}</span>
                <span class="rank-bar-wrap">
                  <span class="rank-bar" :style="{ width: rankingBarWidth(q, item) }"></span>
                </span>
                <span class="rank-avg">平均 {{ rankingAvg(item) }} 名</span>
              </div>
              <p class="stats-tip">平均名次 = 该选项在全部答卷里的名次平均值（1 = 最靠前）。</p>
            </template>
            <el-empty v-else description="暂无回答数据" :image-size="70" />
          </div>

          <!-- NPS：总分 + 0~10 分布 -->
          <div v-else-if="isNpsQ(q)" class="nps-stats">
            <template v-if="q.chartData && q.chartData.length > 0">
              <div class="nps-hero">
                <div class="nps-score" :class="npsTone(npsOf(q).score)">{{ npsOf(q).score == null ? '—' : npsOf(q).score }}</div>
                <div class="nps-hero-text">
                  <div class="nps-hero-title">NPS 净推荐值</div>
                  <div class="nps-hero-sub">
                    推荐者 {{ npsOf(q).promoters }} · 被动者 {{ npsOf(q).passives }} · 贬损者 {{ npsOf(q).detractors }}
                  </div>
                </div>
              </div>
              <div class="nps-dist">
                <div v-for="score in npsScores" :key="'nps' + score" class="nps-dist-item">
                  <span class="nps-dist-score">{{ score }}</span>
                  <span class="nps-dist-bar-wrap">
                    <span class="nps-dist-bar" :class="npsBucketClass(score)"
                          :style="{ height: npsBarHeight(q, score) }"></span>
                  </span>
                  <span class="nps-dist-count">{{ npsCount(q, score) }}</span>
                </div>
              </div>
              <p class="stats-tip">NPS =（推荐者 9~10 − 贬损者 0~6）÷ 总回答 × 100。</p>
            </template>
            <el-empty v-else description="暂无回答数据" :image-size="70" />
          </div>

          <!-- 普通图表题（单选 / 多选 / 下拉 / 评分） -->
          <div v-else-if="isEchart(q)">
            <div v-if="q.chartData && q.chartData.length > 0" :ref="el => setChartRef(el, q.id)" class="q-chart"></div>
            <el-empty v-else description="暂无回答数据" :image-size="70" />
          </div>

          <!-- 填空题 -->
          <div v-else class="text-answers">
            <template v-if="q.textAnswers && q.textAnswers.length > 0">
              <div v-for="(ans, aIndex) in visibleTexts(q)" :key="aIndex" class="text-answer">
                <span class="text-answer-index">{{ aIndex + 1 }}</span>
                <span class="text-answer-body">{{ ans }}</span>
              </div>
              <el-button
                v-if="q.textAnswers.length > TEXT_PREVIEW"
                link
                type="primary"
                class="text-more"
                @click="toggleTexts(q.id)"
              >
                {{ textExpanded[q.id] ? '收起' : `展开全部 ${q.textAnswers.length} 条` }}
              </el-button>
            </template>
            <el-empty v-else description="暂无回答数据" :image-size="70" />
          </div>
        </el-card>
      </div>
    </template>

    <!-- ==================== 答卷列表对话框 ==================== -->
    <el-dialog v-model="listVisible" width="760px" class="answer-dialog">
      <template #header>
        <div class="dialog-head">
          <span class="dialog-title">全部答卷</span>
          <el-tag size="small" effect="light" round>共 {{ answerPage.total }} 份</el-tag>
        </div>
      </template>

      <el-table v-loading="answerLoading" class="w-full" :data="answers" max-height="420">
        <el-table-column type="index" label="序号" width="64" align="center" />
        <el-table-column prop="id" label="答卷 ID" width="96" align="center" />
        <el-table-column prop="submitTime" label="提交时间" width="190" />
        <el-table-column label="填写者" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.userId" type="success" effect="light" round size="small">用户 #{{ row.userId }}</el-tag>
            <el-tag v-else type="info" effect="light" round size="small">匿名</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="checkinEnabled" label="签到" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.checkedIn === 1" type="success" size="small">已签到</el-tag>
            <span v-else class="text-gray">未签到</span>
          </template>
        </el-table-column>
        <el-table-column v-if="checkinEnabled" label="核销" width="100" align="center">
          <template #default="{ row }">
            <el-button
              size="small"
              :type="row.checkedIn === 1 ? 'info' : 'primary'"
              plain
              @click="toggleCheckin(row)"
            >{{ row.checkedIn === 1 ? '取消' : '签到' }}</el-button>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="viewAnswerDetail(row)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- ★ 分页器：没有它时后端一次返回整份问卷的全部答卷（两万份 = 两万个对象） -->
      <el-pagination
        v-if="answerPage.total > 0"
        class="answer-pager"
        background
        layout="total, prev, pager, next"
        :current-page="answerPage.pageNum"
        :page-size="answerPage.pageSize"
        :total="answerPage.total"
        @current-change="loadAnswerPage"
      />
      <el-empty v-if="answers.length === 0" description="暂无答卷" :image-size="70" />
    </el-dialog>

    <!-- ==================== 答卷详情对话框 ==================== -->
    <el-dialog v-model="detailVisible" :title="`答卷 #${currentAnswerId} 详情`" width="720px">
      <div v-for="(item, idx) in answerDetails" :key="item.id" class="detail-row">
        <div class="detail-index">{{ idx + 1 }}</div>
        <div class="detail-body">
          <div class="detail-title">
            {{ getQuestionTitle(item.questionId) }}
            <!-- ★ 判分题的逐题对错（t_answer_detail.is_correct）：多选一选项一行，
                 只在该题的第一行显示一次，避免同一道题重复挂三个"答对"。 -->
            <template v-if="item.isCorrect != null && idx === firstDetailIndex(item.questionId)">
              <el-tag v-if="item.isCorrect === 1" type="success" size="small" style="margin-left:6px;">答对</el-tag>
              <el-tag v-else type="danger" size="small" style="margin-left:6px;">答错</el-tag>
            </template>
          </div>
          <div class="detail-content">{{ formatDetail(item) }}</div>
        </div>
      </div>
      <el-empty v-if="answerDetails.length === 0" description="暂无回答内容" :image-size="70" />
    </el-dialog>

    <!-- ==================== 分享报告（生成图片） ==================== -->
    <el-dialog v-model="shareVisible" title="分享报告" width="560px" top="4vh">
      <div class="share-preview">
        <img v-if="shareImage" :src="shareImage" alt="答卷统计分享图" />
        <div v-else class="sk-block" style="height: 420px;"></div>
      </div>
      <p class="share-hint">
        图片可直接保存或转发到群里；文字摘要适合贴到聊天/答辩 PPT 里。
      </p>
      <template #footer>
        <el-button @click="shareVisible = false">关闭</el-button>
        <el-button @click="copyShareText">复制文字摘要</el-button>
        <el-button type="primary" :disabled="!shareImage" @click="downloadShareImage">保存图片</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
// ★ 按需引入 ECharts（不要全量 import * as echarts，见 utils/echarts.js）
import echarts from '../utils/echarts'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Memo, Warning, CircleCheck, Share, TrendCharts } from '@element-plus/icons-vue'
// ★ 统一接口层：本页请求一律走 src/api/（改法与 AdminLayout.vue 一致）。
//   http.js 统一带 Authorization、统一按 HTTP 状态码判成败、统一弹后端 message。
//   路径说明：本文件在 src/views/ 下，api 在 src/api/，所以是 '../api'。
import api from '../api'
import {
  chartText, chartStrong, chartSplit, chartTooltip, THEME_EVENT,
  chartPalette, chartColor, chartSurface, chartEmptyGraphic, chartNum, chartPct, isDarkTheme
} from '../utils/chartTheme'
// 分享图：纯 canvas 手绘（不引截图库），见 utils/shareCard.js
import { renderStatsCard } from '../utils/shareCard'
// 题型元数据与判定：与后端 common/QuestionType 同口径
import {
  NPS_SCORES, typeLabel, typeMeta, hasChart, isRating, ratingAverage,
  isMatrix, isRanking, isNps, parseMatrixAxis,
  npsSummary as calcNps, npsBucket
} from '../utils/questionTypes'

/** 填空题默认先展示多少条（其余折叠，避免 2000 条一次性铺满页面） */
const TEXT_PREVIEW = 20

export default {
  // Share 在模板里以标签形式用（<Share />），这里显式注册
  components: { Share },
  data() {
    return {
      surveyId: null,
      surveyTitle: '',
      responseCount: 0,
      /** 判分摘要（考试问卷才有；普通问卷为 null，顶部不渲染平均分/及格率） */
      scoreSummary: null,
      /** 问卷是否启用签到核销（决定答卷列表显不显示签到列） */
      checkinEnabled: false,
      questions: [],
      chartInstances: {},
      /** 首屏 / 切换口径时的骨架屏 */
      loading: true,
      // 答卷列表
      listVisible: false,
      answerLoading: false,
      answers: [],
      /**
       * 答卷列表分页状态（与后端 PageResult 字段同名，便于直接回填）。
       * ★ total 由后端给（直查 count，不走 60 秒缓存），否则"共 N 份"会和最后一页对不上。
       */
      answerPage: { pageNum: 1, pageSize: 10, total: 0 },
      // 答卷详情
      detailVisible: false,
      currentAnswerId: null,
      answerDetails: [],
      // ===== 质控（答卷无效样本质控）=====
      /** 质控摘要：{ total, suspicious, suspiciousRate, reasons:[{code,label,count}] } */
      quality: null,
      /** 是否剔除可疑样本（true = 图表只用有效答卷） */
      excludeInvalid: false,
      // ===== 分享报告（图片） =====
      shareVisible: false,
      /** 生成好的 PNG dataURL；空串 = 还在生成 */
      shareImage: '',
      // ===== 填空题折叠 =====
      TEXT_PREVIEW,
      textExpanded: {},
      /** NPS 的 0~10 分（分布条按这个顺序画） */
      npsScores: NPS_SCORES
    }
  },
  computed: {
    /** 顶部概览卡（普通问卷 4 张；考试问卷再加「平均分 / 及格率」两张） */
    tiles() {
      const suspicious = this.quality ? this.quality.suspicious : 0
      const counted = this.excludeInvalid ? Math.max(this.responseCount - suspicious, 0) : this.responseCount
      const tiles = [
        { key: 'responses', label: '回收答卷', value: this.responseCount, icon: ChatDotRound, color: '#2f80ed', bg: 'linear-gradient(135deg,#e8f3ff,#d7e9ff)' },
        { key: 'questions', label: '题目数量', value: this.questions.length, icon: Memo, color: '#7c3aed', bg: 'linear-gradient(135deg,#f3ecff,#e6dbff)' },
        { key: 'suspicious', label: '可疑样本', value: suspicious, icon: Warning, color: '#d97706', bg: 'linear-gradient(135deg,#fff5e6,#ffe9c7)' },
        { key: 'counted', label: '计入统计', value: counted, icon: CircleCheck, color: '#16a34a', bg: 'linear-gradient(135deg,#e9f9ef,#d6f3e3)' }
      ]
      // ★ 判分摘要只在考试问卷出现（后端 gradedCount=0 时给 null，见 SurveyStatisticsDTO.ScoreSummary）：
      //   「—」比 0 分更诚实 —— 没有已判分答卷时显示"平均分 0"会让人以为大家都考了 0 分。
      if (this.scoreSummary) {
        tiles.push({
          key: 'avgScore', label: '平均分',
          value: this.scoreSummary.avgScore == null ? '—' : this.scoreSummary.avgScore,
          icon: TrendCharts, color: '#0284c7', bg: 'linear-gradient(135deg,#e0f2fe,#bae6fd)'
        })
        tiles.push({
          key: 'passRate', label: '及格率',
          value: this.scoreSummary.passRate == null ? '—' : this.scoreSummary.passRate + '%',
          icon: CircleCheck, color: '#0891b2', bg: 'linear-gradient(135deg,#ecfeff,#cffafe)'
        })
      }
      return tiles
    },
    /** 可疑原因的中文汇总（鼠标悬停在徽标上能看到原因分布） */
    qualityHint() {
      if (!this.quality || !this.quality.reasons || !this.quality.reasons.length) return ''
      return this.quality.reasons.map(r => `${r.label}：${r.count} 份`).join('；')
    },
    /**
     * 分享图里的环形数据：始终与"当前口径"一致 ——
     * 关掉剔除时全部计入；打开剔除时把可疑样本单列出来。
     * 两份加起来恒等于回收答卷数，不会出现"百分比加起来不是 100%"。
     */
    shareSegments() {
      const suspicious = this.quality ? this.quality.suspicious : 0
      const counted = this.excludeInvalid
        ? Math.max(this.responseCount - suspicious, 0)
        : this.responseCount
      const excluded = this.excludeInvalid ? suspicious : 0
      return [
        { name: '计入统计', value: counted, color: '#22c55e' },
        { name: '剔除可疑', value: excluded, color: '#f59e0b' }
      ].filter(s => s.value > 0)
    }
  },
  mounted() {
    this.surveyId = this.$route.params.id
    this.loadAll()
    window.addEventListener(THEME_EVENT, this.handleThemeChange)
  },
  beforeUnmount() {
    window.removeEventListener(THEME_EVENT, this.handleThemeChange)
    // ★ resize 监听必须用具名函数，否则无法 removeEventListener：
    //   匿名写法 `window.addEventListener('resize', () => chart.resize())` 会让每进一次统计页
    //   就永久多一个监听，而且它还会对已 dispose 的实例调 resize。
    //   具名 handler 存在 chartInstances 上，这里一并移除再 dispose。
    //   （对照写法：SystemStatistics.vue 的 handleResize + removeEventListener。）
    Object.values(this.chartInstances).forEach(item => {
      if (item.resizeHandler) window.removeEventListener('resize', item.resizeHandler)
      if (item.chart) item.chart.dispose()
    })
  },
  methods: {
    /** 主题切换：销毁旧图表并用新配色重画（不重新请求数据，也不清空 el 引用） */
    handleThemeChange() {
      Object.values(this.chartInstances).forEach(item => {
        if (item.resizeHandler) window.removeEventListener('resize', item.resizeHandler)
        if (item.chart) item.chart.dispose()
        item.chart = null
        item.resizeHandler = null
      })
      this.questions.forEach(q => {
        if (this.isEchart(q) && q.chartData && q.chartData.length > 0) this.initChart(q)
      })
    },

    async loadAll() {
      // ★ 切换"剔除可疑样本"会重跑本方法：先把旧图表 dispose 掉 ——
      //   否则 v-for 重建 DOM 后，chart 还挂在已脱离文档的旧元素上，图表会变空白。
      this.disposeCharts()
      this.loading = true
      try {
        // ★ 一次请求取回全部：问卷标题 + 答卷数 + 题目列表 + 每题的统计/文本。
        //   原实现是 `1 + 1 + 1 + N` 次请求，且最后一步是 for + await **串行** ——
        //   N 道题就要等 N 个来回，延迟直接叠加（10 题约等于 10 倍往返）。
        //   现在只发这 1 次（后端见 AnswerController.statisticsBySurvey）。
        // ★ excludeInvalid：剔除可疑样本时后端走"只统计有效答卷"的口径（另一套缓存 key）。
        const data = await api.answer.surveyStatistics(this.surveyId, this.excludeInvalid)
        this.surveyTitle = data.title
        this.responseCount = data.answerCount
        // ★ 判分摘要：普通问卷后端给 null（见 SurveyStatisticsDTO），这里不做兜底数字
        this.scoreSummary = data.scoreSummary || null
        this.checkinEnabled = data.checkinEnabled === true

        // 后端每题返回 { question, chartData, textAnswers }；
        // 摊平成模板一直用的结构（题目字段直接铺开 + 两个统计字段）。
        this.questions = (data.questions || []).map(item => ({
          ...item.question,
          chartData: item.chartData,
          textAnswers: item.textAnswers
        }))

        // 质控摘要（可疑份数 / 原因分布）：独立请求，失败不影响主流程
        this.loadQuality()
      } catch {
        // 失败提示由接口层统一弹出，这里不再重复弹，避免出现两条红字。
      } finally {
        this.loading = false
      }

      // ★ 骨架屏与内容是两个分支：必须等 loading=false 的那一帧挂完 DOM 再画，
      //   否则图表容器不存在 → init 出来是 0 宽画布（表现为"卡片空白且不报错"）。
      await this.$nextTick()
      this.questions.forEach(q => {
        if (this.isEchart(q) && q.chartData && q.chartData.length > 0) {
          this.initChart(q)
        }
      })
    },

    /** 质控摘要（失败就静默：它只是提示，不该让统计页整体报错） */
    async loadQuality() {
      try {
        this.quality = await api.answer.quality(this.surveyId)
      } catch {
        this.quality = null
      }
    },

    /** "剔除可疑样本"开关切换：重新拉一遍（图和文本都可能是另一个口径） */
    reloadAll() {
      this.loadAll()
    },

    /** 释放所有图表实例并移除 resize 监听（切换口径 / 重新加载前必须调用，否则图表会空白） */
    disposeCharts() {
      Object.values(this.chartInstances).forEach(item => {
        if (item.resizeHandler) window.removeEventListener('resize', item.resizeHandler)
        if (item.chart) item.chart.dispose()
      })
      this.chartInstances = {}
    },

    setChartRef(el, questionId) {
      if (!el) return
      if (!this.chartInstances[questionId]) {
        // resizeHandler 与 chart 一起存，卸载时才能移除监听（见 beforeUnmount）
        this.chartInstances[questionId] = { el: null, chart: null, resizeHandler: null }
      }
      this.chartInstances[questionId].el = el
    },

    initChart(question) {
      const ref = this.chartInstances[question.id]
      if (!ref || !ref.el) return
      // 容器宽度为 0（首帧布局未完成）时画出来的是 0 宽画布
      if (!ref.el.clientWidth) return
      if (ref.resizeHandler) {
        window.removeEventListener('resize', ref.resizeHandler)
        ref.resizeHandler = null
      }
      if (ref.chart) ref.chart.dispose()

      const chart = echarts.init(ref.el)
      ref.chart = chart

      // 评分题的 content 是 "1"~"5"，图表标签补上"分"，避免 x 轴只有裸数字
      const isRatingQuestion = isRating(question.type)
      const chartData = question.chartData.map(item => ({
        name: isRatingQuestion ? `${item.content} 分` : item.content,
        value: item.count
      }))
      const total = chartData.reduce((sum, d) => sum + Number(d.value || 0), 0)
      // 全部选项都是 0 票：不画一个空坐标系，直接在图表中央给一句文案
      const isEmpty = total === 0

      // 单选 / 下拉 = 圆环（中心总数）；多选 / 评分 = 横向条形（分布更好读）
      const pieLike = question.type === 1 || question.type === 5
      const option = pieLike
        ? {
            color: chartPalette(),
            tooltip: {
              trigger: 'item',
              // 单位统一为「人 / %」，百分比四舍五入到整数
              formatter: p => `${p.name}：${chartNum(p.value)} 人（${chartPct(p.percent)}%）`,
              ...chartTooltip()
            },
            title: {
              text: String(total),
              subtext: '回答数',
              left: '34%',
              top: '40%',
              textAlign: 'center',
              textStyle: { fontSize: 24, fontWeight: 700, color: chartStrong() },
              subtextStyle: { fontSize: 12, color: chartText() }
            },
            legend: {
              orient: 'vertical',
              right: '4%',
              top: 'middle',
              icon: 'circle',
              itemWidth: 8,
              itemHeight: 8,
              textStyle: { color: chartText() }
            },
            series: [
              {
                type: 'pie',
                radius: ['54%', '76%'],
                center: ['34%', '50%'],
                itemStyle: { borderColor: chartSurface(), borderWidth: 3, borderRadius: 6 },
                label: { formatter: '{b}\n{c} 人', color: chartText(), fontSize: 12 },
                labelLine: { length: 10, length2: 8 },
                data: chartData
              }
            ]
          }
        : {
            color: chartPalette(),
            tooltip: {
              trigger: 'axis',
              axisPointer: { type: 'shadow' },
              formatter: params => {
                const p = Array.isArray(params) ? params[0] : params
                return `${p.name}：${chartNum(p.value)} 人`
              },
              ...chartTooltip()
            },
            grid: { left: 8, right: 36, top: 8, bottom: 8, containLabel: true },
            xAxis: {
              type: 'value',
              minInterval: 1,
              axisLabel: { color: chartText() },
              splitLine: { lineStyle: { color: chartSplit() } }
            },
            yAxis: {
              type: 'category',
              inverse: true,
              data: chartData.map(d => d.name),
              axisTick: { show: false },
              axisLine: { show: false },
              axisLabel: { color: chartText(), width: 180, overflow: 'truncate' }
            },
            series: [
              {
                type: 'bar',
                data: chartData.map(d => d.value),
                barWidth: 14,
                showBackground: true,
                backgroundStyle: {
                  color: isDarkTheme() ? '#16263f' : '#f1f6fd',
                  borderRadius: 7
                },
                itemStyle: {
                  borderRadius: [0, 7, 7, 0],
                  color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
                    { offset: 0, color: chartColor(4) },
                    { offset: 1, color: chartColor(0) }
                  ])
                },
                label: { show: true, position: 'right', color: chartText(), fontSize: 12 }
              }
            ]
          }

      if (isEmpty) {
        // 空数据态：去掉标题/图例/系列，只留居中文案（统一用 chartTheme 的 graphic）
        option.title = undefined
        option.legend = undefined
        option.series = []
        option.graphic = chartEmptyGraphic('暂无作答数据')
      }

      chart.setOption(option)
      // 首帧宽度可能还没算完：下一帧补一次 resize，避免画到 0 宽画布
      requestAnimationFrame(() => chart.resize())
      // ★ 具名 handler，卸载时才能移除监听（见 beforeUnmount 的说明）
      ref.resizeHandler = () => chart.resize()
      window.addEventListener('resize', ref.resizeHandler)
    },

    /**
     * 普通图表题：单选 / 多选 / 下拉 / 评分。
     * ★ 矩阵 / 排序 / NPS 也属于 hasChart（后端走 chartData），但它们的图形结构完全不同，
     *   必须各自定制渲染；混进 ECharts 分支会得到一张没有 ref 的空卡片。
     */
    isEchart(q) {
      return hasChart(q.type) && !isMatrix(q.type) && !isRanking(q.type) && !isNps(q.type)
    },

    isMatrixQ(q) {
      return isMatrix(q.type)
    },

    isRankingQ(q) {
      return isRanking(q.type)
    },

    isNpsQ(q) {
      return isNps(q.type)
    },

    // ===== 矩阵题 =====
    matrixRows(q) {
      return parseMatrixAxis(q.matrixRows)
    },

    matrixCols(q) {
      return parseMatrixAxis(q.matrixCols)
    },

    matrixCell(q, row, col) {
      const item = (q.chartData || []).find(d => d.row === row && d.content === col)
      return item ? Number(item.count || 0) : 0
    },

    matrixRowTotal(q, row) {
      return (q.chartData || [])
        .filter(d => d.row === row)
        .reduce((sum, d) => sum + Number(d.count || 0), 0)
    },

    matrixPct(q, row, col) {
      const total = this.matrixRowTotal(q, row)
      return total > 0 ? Math.round(this.matrixCell(q, row, col) * 100 / total) : 0
    },

    // ===== 排序题 =====
    sortedRanking(q) {
      return (q.chartData || []).slice().sort((a, b) => Number(a.avgRank) - Number(b.avgRank))
    },

    rankingAvg(item) {
      const avg = Number(item.avgRank)
      return Number.isFinite(avg) ? avg.toFixed(2) : '—'
    },

    /** 条形长度：第 1 名最长、最后一名最短（按"名次越好条越长"的直觉画） */
    rankingBarWidth(q, item) {
      const total = (q.chartData || []).length
      const avg = Number(item.avgRank)
      if (!total || !Number.isFinite(avg)) return '0%'
      const ratio = 1 - (avg - 1) / total
      return `${Math.max(8, Math.round(ratio * 100))}%`
    },

    // ===== NPS =====
    npsOf(q) {
      return calcNps(q.chartData)
    },

    npsTone(score) {
      if (score == null) return 'nps-none'
      if (score >= 50) return 'nps-excellent'
      if (score >= 0) return 'nps-good'
      return 'nps-poor'
    },

    npsCount(q, score) {
      const item = (q.chartData || []).find(d => String(d.content) === String(score))
      return item ? Number(item.count || 0) : 0
    },

    /** 分布条高度：按该分值回答数 / 最大回答数 缩放（最低 4% 保证 0 也看得见底） */
    npsBarHeight(q, score) {
      const max = (q.chartData || []).reduce((m, d) => Math.max(m, Number(d.count || 0)), 0)
      if (!max) return '4%'
      return `${Math.max(4, Math.round(this.npsCount(q, score) * 100 / max))}%`
    },

    npsBucketClass(score) {
      return `nps-b-${npsBucket(score)}`
    },

    typeName(type) {
      return typeLabel(type)
    },

    typeTag(type) {
      return typeMeta(type).tag
    },

    /** 每题"回答数"：选择题/评分题按计数求和，文本类按条数；评分题附带平均分 */
    answerSummary(q) {
      if (this.isMatrixQ(q)) {
        const cells = (q.chartData || []).reduce((sum, d) => sum + Number(d.count || 0), 0)
        return `${cells} 格回答`
      }
      if (this.isRankingQ(q)) {
        const votes = (q.chartData || []).reduce((sum, d) => sum + Number(d.count || 0), 0)
        return `${votes} 票`
      }
      if (this.isNpsQ(q)) {
        const n = (q.chartData || []).reduce((sum, d) => sum + Number(d.count || 0), 0)
        return `${n} 份回答`
      }
      if (this.isEchart(q)) {
        const n = (q.chartData || []).reduce((sum, d) => sum + Number(d.count || 0), 0)
        if (isRating(q.type)) {
          const avg = ratingAverage(q.chartData)
          return `${n} 份回答${avg == null ? '' : ` · 平均 ${avg.toFixed(1)} 分`}`
        }
        return `${n} 份回答`
      }
      return `${(q.textAnswers || []).length} 条回答`
    },

    visibleTexts(q) {
      const list = q.textAnswers || []
      return this.textExpanded[q.id] ? list : list.slice(0, TEXT_PREVIEW)
    },

    toggleTexts(questionId) {
      this.textExpanded = { ...this.textExpanded, [questionId]: !this.textExpanded[questionId] }
    },

    // 打开答卷列表：从第 1 页开始，成功了才开弹窗（失败提示由接口层弹）
    async openAnswerList() {
      if (await this.loadAnswerPage(1)) {
        this.listVisible = true
      }
    },

    /**
     * 拉取一页答卷（分页器翻页也走这里）。
     *
     * ★ 后端返回 `{ total, list, pageNum, pageSize }`（PageResult）：
     *   `answers` 只放**当前页**，`total` 交给分页器 —— 不要再把整个数组塞进内存。
     * ★ 返回 true/false 供 openAnswerList 判断"要不要开弹窗"。
     */
    async loadAnswerPage(pageNum) {
      this.answerLoading = true
      try {
        const res = await api.answer.list(this.surveyId, pageNum, this.answerPage.pageSize)
        this.answers = res.list
        this.answerPage.total = res.total
        this.answerPage.pageNum = res.pageNum
        return true
      } catch {
        // 提示由接口层统一弹出
        return false
      } finally {
        this.answerLoading = false
      }
    },

    // 查看某份答卷详情
    async viewAnswerDetail(row) {
      this.currentAnswerId = row.id
      try {
        //   api.answer.detail(row.id)（这里传的是答卷 id，不是问卷 id）
        this.answerDetails = await api.answer.detail(row.id)
        this.detailVisible = true
      } catch {
        // 提示由接口层统一弹出
      }
    },

    // 根据题目ID查题目标题
    getQuestionTitle(questionId) {
      const q = this.questions.find(item => item.id === questionId)
      return q ? q.title : `题目 #${questionId}`
    },

    /** 签到 / 取消签到（只改当前行状态，不重拉整个列表） */
    async toggleCheckin(row) {
      const next = row.checkedIn !== 1
      try {
        await api.answer.checkin(row.id, next)
        row.checkedIn = next ? 1 : 0
        ElMessage.success(next ? '已签到' : '已取消签到')
      } catch {
        // 失败提示由 http.js 统一弹出（如"该问卷未启用签到核销"）
      }
    },

    /** 某题在答卷详情里的第一行下标（逐题对错标签只在第一行显示一次） */
    firstDetailIndex(questionId) {
      return this.answerDetails.findIndex(x => x.questionId === questionId)
    },

    /**
     * 单份答卷详情里，一行明细怎么显示。
     * ★ 矩阵一行一个矩阵行、排序一名一条 —— 只显示 content 会丢掉"是哪一行/第几名"，
     *   用户看到的就会是一串互不相干的列标题/选项。
     */
    formatDetail(item) {
      const q = this.questions.find(x => x.id === item.questionId)
      if (!q) return item.content || '（未作答）'
      if (isMatrix(q.type)) {
        return `${item.rowKey || '（行）'}：${item.content || '（未作答）'}`
      }
      if (isRanking(q.type)) {
        return `第 ${item.rankNo == null ? '?' : item.rankNo} 名 · ${item.content || ''}`
      }
      if (isNps(q.type)) {
        return `${item.content == null ? '（未作答）' : item.content + ' 分'}`
      }
      return item.content || '（未作答）'
    },

    /**
     * 导出 Excel。
     *
     * ★ api.answer.exportExcel() 内部走 http.download()，它解析响应头 Content-Disposition
     *   里的文件名（后端用 RFC 5987 的 `filename*=UTF-8''` 传中文名，并给一个 ASCII 的
     *   `filename` 兜底）并**直接触发浏览器下载** —— 所以页面只剩一行，
     *   而且文件名终于是后端给的那个，不再是写死的兜底名。
     */
    async exportExcel() {
      try {
        await api.answer.exportExcel(this.surveyId)
        ElMessage.success('导出成功')
      } catch {
        // 失败提示由接口层统一弹出
      }
    },

    // ==================== 分享报告 ====================

    /** 打开分享弹窗并生成图片（同步生成，1080×1320 一张 PNG，几十毫秒） */
    openShare() {
      this.shareVisible = true
      this.shareImage = ''
      try {
        this.shareImage = renderStatsCard({
          title: this.surveyTitle,
          tiles: this.tiles.map(t => ({ label: t.label, value: t.value, color: t.color })),
          segments: this.shareSegments,
          generatedAt: this.nowText()
        })
      } catch {
        // canvas 不可用（极旧内核 / 被浏览器禁用）时不要留个空白弹窗
        this.shareVisible = false
        ElMessage.error('生成分享图失败，请换个浏览器再试')
      }
    },

    /** 文字摘要：直接贴到聊天或 PPT 里 */
    copyShareText() {
      const counted = this.tiles.find(t => t.key === 'counted')
      const text = [
        `【${this.surveyTitle || '问卷'}】答卷统计`,
        `回收答卷：${this.responseCount} 份`,
        `题目数量：${this.questions.length} 道`,
        `可疑样本：${this.quality ? this.quality.suspicious : 0} 份`,
        `计入统计：${counted ? counted.value : this.responseCount} 份`
      ].join('\n')
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text)
          .then(() => ElMessage.success('摘要已复制'))
          .catch(() => ElMessage.warning('复制失败，请手动选择复制'))
      } else {
        ElMessage.warning('复制失败，请手动选择复制')
      }
    },

    downloadShareImage() {
      if (!this.shareImage) return
      // 文件名里不能出现 \ / : * ? " < > |，问卷标题是用户输入，必须先清洗
      const safeTitle = String(this.surveyTitle || '问卷').replace(/[\\/:*?"<>|]/g, '_')
      const a = document.createElement('a')
      a.href = this.shareImage
      a.download = `${safeTitle}-答卷统计.png`
      a.click()
    },

    nowText() {
      return new Date().toLocaleString('zh-CN', { hour12: false })
    }
  }
}

</script>

<style scoped>
.stats-page {
  max-width: 1280px;
  margin: 0 auto;
}
.page-subtitle {
  margin: 6px 0 0 15px;
  font-size: 13px;
  color: var(--ink-3);
}
.header-actions {
  display: flex;
  gap: 10px;
}

/* ==================== 概览卡 ==================== */
.stat-tile {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border-radius: var(--r-lg);
  background: #fff;
  border: 1px solid var(--stroke);
  box-shadow: var(--shadow-sm);
  transition:
    transform 0.22s var(--ease),
    box-shadow 0.22s var(--ease);
}
.stat-tile:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-md);
}
.stat-ico {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-label {
  font-size: 12px;
  color: var(--ink-3);
}
.stat-num {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
  margin-top: 2px;
}

/* ==================== 质控条 ==================== */
.quality-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
  margin-bottom: 20px;
  border-radius: var(--r-lg);
  border: 1px solid #ffe3b8;
  background: linear-gradient(120deg, #fffaf0 0%, #fff4e0 100%);
}
.quality-main {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.quality-icon {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #ffe9c7;
  color: #d97706;
  flex-shrink: 0;
}
.quality-title {
  font-weight: 600;
  color: #92400e;
}
.quality-rate {
  font-weight: 400;
  color: #b45309;
  font-size: 12px;
}
.quality-reasons {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.reason-tag {
  background: #fff;
  border-color: #ffe3b8;
  color: #b45309;
}
.quality-switch {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.quality-switch-label {
  font-size: 13px;
  color: #92400e;
}

/* ==================== 题目卡 ==================== */
.question-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.q-card {
  border-radius: var(--r-lg);
}
.q-head {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin-bottom: 12px;
}
.q-index {
  width: 34px;
  height: 34px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  color: #fff;
  background: var(--brand-gradient);
  box-shadow: 0 6px 14px rgba(47, 128, 237, 0.28);
  flex-shrink: 0;
}
.q-title-wrap {
  flex: 1;
  min-width: 0;
}
.q-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1.5;
}
.q-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}
.q-count {
  font-size: 12px;
  color: var(--ink-3);
}
.q-chart {
  width: 100%;
  height: 300px;
}

/* ==================== 矩阵题统计 ==================== */
.matrix-table-wrap {
  overflow-x: auto;
}
.stat-matrix {
  width: 100%;
  border-collapse: collapse;
}
.stat-matrix th,
.stat-matrix td {
  padding: 9px 12px;
  border: 1px solid var(--stroke);
  font-size: 13px;
  text-align: center;
}
.stat-matrix thead th {
  background: #f5f9ff;
  font-weight: 600;
  color: var(--ink-2);
}
.stat-matrix tbody th {
  text-align: left;
  font-weight: 500;
  white-space: nowrap;
  color: var(--ink-2);
}
.stat-matrix-total {
  font-weight: 600;
  color: var(--ink-2);
  background: #fafcff;
}
.matrix-count {
  margin-right: 4px;
  font-weight: 600;
  color: var(--ink);
}
.matrix-pct {
  font-size: 11px;
  color: var(--ink-3);
}
.stats-tip {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-3);
}

/* ==================== 排序题统计 ==================== */
.ranking-stats {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.rank-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.rank-pos {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--sky-50);
  color: var(--sky-600);
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}
.rank-label {
  width: 170px;
  font-size: 13px;
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rank-bar-wrap {
  flex: 1;
  height: 14px;
  border-radius: 999px;
  background: #eef4fd;
  overflow: hidden;
}
.rank-bar {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #7db7ff, #3b82f6);
}
.rank-avg {
  width: 110px;
  text-align: right;
  font-size: 12px;
  color: var(--ink-3);
  flex-shrink: 0;
}

/* ==================== NPS 统计 ==================== */
.nps-hero {
  display: flex;
  align-items: center;
  gap: 20px;
}
.nps-score {
  font-size: 44px;
  font-weight: 800;
  line-height: 1;
}
.nps-excellent {
  color: #16a34a;
}
.nps-good {
  color: #2563eb;
}
.nps-poor {
  color: #dc2626;
}
.nps-none {
  color: var(--ink-3);
}
.nps-hero-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-2);
}
.nps-hero-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
}
.nps-dist {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 150px;
  margin-top: 18px;
}
.nps-dist-item {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  height: 100%;
}
.nps-dist-score {
  font-size: 11px;
  color: var(--ink-3);
}
.nps-dist-bar-wrap {
  display: flex;
  align-items: flex-end;
  width: 100%;
  flex: 1;
}
.nps-dist-bar {
  display: block;
  width: 100%;
  border-radius: 6px 6px 0 0;
}
.nps-b-promoter {
  background: #34d399;
}
.nps-b-passive {
  background: #fbbf24;
}
.nps-b-detractor {
  background: #f87171;
}
.nps-dist-count {
  font-size: 11px;
  color: var(--ink-2);
}
html[data-theme="dark"] .stat-matrix thead th,
html[data-theme="dark"] .stat-matrix-total {
  background: #16263f;
}
html[data-theme="dark"] .rank-bar-wrap {
  background: #16263f;
}

/* ==================== 填空题回答 ==================== */
.text-answers {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.text-answer {
  display: flex;
  gap: 10px;
  padding: 11px 14px;
  border-radius: 12px;
  background: #f8fbff;
  border: 1px solid #eef4fd;
  transition: background 0.18s var(--ease);
}
.text-answer:hover {
  background: #f2f8ff;
}
.text-answer-index {
  width: 22px;
  height: 22px;
  border-radius: 7px;
  background: var(--sky-100);
  color: var(--sky-700);
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 1px;
}
.text-answer-body {
  color: var(--ink-2);
  line-height: 1.65;
  word-break: break-word;
}
.text-more {
  align-self: flex-start;
}

/* ==================== 弹窗 ==================== */
.dialog-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dialog-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.answer-pager {
  margin-top: 14px;
  justify-content: flex-end;
}
.detail-row {
  display: flex;
  gap: 12px;
  padding: 12px 14px;
  margin-bottom: 10px;
  border-radius: 12px;
  background: #f8fbff;
  border: 1px solid #eef4fd;
}
.detail-index {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  background: var(--sky-100);
  color: var(--sky-700);
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.detail-body {
  flex: 1;
  min-width: 0;
}
.detail-title {
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 4px;
}
.detail-content {
  color: var(--ink-2);
  line-height: 1.65;
  word-break: break-word;
}

/* ==================== 分享报告 ==================== */
.share-preview {
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid var(--stroke);
  background: var(--surface-2);
}
.share-preview img {
  display: block;
  width: 100%;
  height: auto;
}
.share-hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.6;
}
</style>
