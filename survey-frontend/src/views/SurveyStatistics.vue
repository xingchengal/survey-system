<template>
  <div style="padding: 20px;">
    <!-- 顶部栏 -->
    <div class="page-header">
      <div>
        <h2 class="m-0">📈 {{ surveyTitle }} - 统计</h2>
        <div style="color: #666; margin-top: 6px;">
          回收数量：<strong style="color: #f56c6c; font-size: 20px;">{{ responseCount }}</strong> 份
          <!-- 质控：有可疑样本时才出现，避免"0 份可疑"也占一行 -->
          <template v-if="quality && quality.suspicious > 0">
            <el-tag type="warning" size="small" class="ml-8" :title="qualityHint">
              其中可疑 {{ quality.suspicious }} 份（{{ quality.suspiciousRate }}%）
            </el-tag>
            <el-switch v-model="excludeInvalid" class="ml-10" size="small"
                       active-text="剔除可疑样本" @change="reloadAll" />
          </template>
        </div>
      </div>
      <div>
        <el-button @click="$router.push('/my/surveys')">返回</el-button>
        <el-button type="primary" @click="openAnswerList" :disabled="responseCount === 0">
          <el-icon><View /></el-icon> 查看答卷
        </el-button>
        <el-button type="success" @click="exportExcel" :disabled="responseCount === 0">
          <el-icon><Download /></el-icon> 导出Excel
        </el-button>
      </div>
    </div>

    <el-empty v-if="questions.length === 0" description="该问卷还没有题目" />

    <div v-else>
      <el-card class="mb-20" v-for="(q, qIndex) in questions" :key="q.id">
        <div style="font-weight: bold; font-size: 16px; margin-bottom: 16px;">
          {{ qIndex + 1 }}. {{ q.title }}
          <el-tag class="ml-8" size="small">
            {{ q.type === 1 ? '单选题' : q.type === 2 ? '多选题' : '单行输入' }}
          </el-tag>
        </div>

        <!-- 选择题图表 -->
        <div v-if="q.type === 1 || q.type === 2">
          <div v-if="q.chartData && q.chartData.length > 0">
            <div :ref="el => setChartRef(el, q.id)" style="width: 100%; height: 320px;"></div>
          </div>
          <div v-else style="text-align: center; color: #999; padding: 20px;">暂无回答数据</div>
        </div>

        <!-- 填空题回答列表 -->
        <div v-else>
          <div v-if="q.textAnswers && q.textAnswers.length > 0">
            <div v-for="(ans, aIndex) in q.textAnswers" :key="aIndex"
                 style="padding: 10px 14px; margin-bottom: 8px; background: #f5f7fa; border-radius: 6px;">
              {{ ans }}
            </div>
          </div>
          <div v-else style="text-align: center; color: #999; padding: 20px;">暂无回答数据</div>
        </div>
      </el-card>
    </div>

    <!-- 答卷列表对话框 -->
    <el-dialog v-model="listVisible" title="所有答卷" width="700px">
      <el-table class="w-full" :data="answers" stripe border max-height="400">
        <el-table-column type="index" label="序号" width="70" align="center" />
        <el-table-column prop="id" label="答卷ID" width="90" align="center" />
        <el-table-column prop="submitTime" label="提交时间" width="200" />
        <el-table-column label="填写者" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.userId" type="success">用户 ID: {{ row.userId }}</el-tag>
            <el-tag v-else type="info">匿名</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="viewAnswerDetail(row)">查看详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <!-- ★ 分页器：没有它时后端一次返回整份问卷的全部答卷（两万份 = 两万个对象） -->
      <el-pagination
        v-if="answerPage.total > 0"
        class="mt-3"
        background
        layout="total, prev, pager, next"
        :current-page="answerPage.pageNum"
        :page-size="answerPage.pageSize"
        :total="answerPage.total"
        @current-change="loadAnswerPage"
      />
      <el-empty v-if="answers.length === 0" description="暂无答卷" />
    </el-dialog>

    <!-- 答卷详情对话框 -->
    <el-dialog v-model="detailVisible" :title="`答卷 #${currentAnswerId} 详情`" width="700px">
      <div v-for="(item, idx) in answerDetails" :key="item.id" style="margin-bottom: 16px; padding: 12px; background: #f5f7fa; border-radius: 6px;">
        <div style="font-weight: bold; margin-bottom: 6px;">
          {{ idx + 1 }}. {{ getQuestionTitle(item.questionId) }}
        </div>
        <div class="text-secondary">{{ item.content || '（未作答）' }}</div>
      </div>
      <el-empty v-if="answerDetails.length === 0" description="暂无回答内容" />
    </el-dialog>
  </div>
</template>

<script>
// ★ 按需引入 ECharts（不要全量 import * as echarts，见 utils/echarts.js）
import echarts from '../utils/echarts'
import { ElMessage } from 'element-plus'
// ★ 统一接口层：本页请求一律走 src/api/（改法与 AdminLayout.vue 一致）。
//   http.js 统一带 Authorization、统一按 HTTP 状态码判成败、统一弹后端 message。
//   路径说明：本文件在 src/views/ 下，api 在 src/api/，所以是 '../api'。
import api from '../api'
// 本页图标均为模板标签形式，已由 main.js 全局注册，无需 import

// 图标已在 main.js 全局注册，不再重复注册（View 是 HTML 保留标签名）
export default {
  data() {
    return {
      surveyId: null,
      surveyTitle: '',
      responseCount: 0,
      questions: [],
      chartInstances: {},
      // 答卷列表
      listVisible: false,
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
      excludeInvalid: false
    }
  },
  computed: {
    /** 可疑原因的中文汇总（鼠标悬停在徽标上能看到原因分布） */
    qualityHint() {
      if (!this.quality || !this.quality.reasons || !this.quality.reasons.length) return ''
      return this.quality.reasons.map(r => `${r.label}：${r.count} 份`).join('；')
    }
  },
  mounted() {
    this.surveyId = this.$route.params.id
    this.loadAll()
  },
  beforeUnmount() {
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
    async loadAll() {
      try {
        // ★ 切换"剔除可疑样本"会重跑本方法：先把旧图表 dispose 掉 ——
        //   否则 v-for 重建 DOM 后，chart 还挂在已脱离文档的旧元素上，图表会变空白。
        this.disposeCharts()

        // ★ 一次请求取回全部：问卷标题 + 答卷数 + 题目列表 + 每题的统计/文本。
        //   原实现是 `1 + 1 + 1 + N` 次请求，且最后一步是 for + await **串行** ——
        //   N 道题就要等 N 个来回，延迟直接叠加（10 题约等于 10 倍往返）。
        //   现在只发这 1 次（后端见 AnswerController.statisticsBySurvey）。
        // ★ excludeInvalid：剔除可疑样本时后端走"只统计有效答卷"的口径（另一套缓存 key）。
        const data = await api.answer.surveyStatistics(this.surveyId, this.excludeInvalid)
        this.surveyTitle = data.title
        this.responseCount = data.answerCount

        // 后端每题返回 { question, chartData, textAnswers }；
        // 摊平成模板一直用的结构（题目字段直接铺开 + 两个统计字段）。
        this.questions = (data.questions || []).map(item => ({
          ...item.question,
          chartData: item.chartData,
          textAnswers: item.textAnswers
        }))

        // 质控摘要（可疑份数 / 原因分布）：独立请求，失败不影响主流程
        this.loadQuality()

        // ↓↓↓ 下面是纯前端图表渲染 ↓↓↓
        this.$nextTick(() => {
          this.questions.forEach(q => {
            if ((q.type === 1 || q.type === 2) && q.chartData && q.chartData.length > 0) {
              this.initChart(q)
            }
          })
        })
      } catch {
        // 失败提示由接口层统一弹出，这里不再重复弹，避免出现两条红字。
      }
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

    setChartRef(el, questionId) {      if (!el) return
      if (!this.chartInstances[questionId]) {
        // resizeHandler 与 chart 一起存，卸载时才能移除监听（见 beforeUnmount）
        this.chartInstances[questionId] = { el: null, chart: null, resizeHandler: null }
      }
      this.chartInstances[questionId].el = el
    },

    initChart(question) {
      const ref = this.chartInstances[question.id]
      if (!ref || !ref.el) return
      if (ref.chart) ref.chart.dispose()

      const chart = echarts.init(ref.el)
      ref.chart = chart

      const isSingle = question.type === 1
      const chartData = question.chartData.map(item => ({
        name: item.content,
        value: item.count
      }))

      const option = isSingle
        ? {
            tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
            legend: { orient: 'vertical', left: 'left' },
            series: [
              {
                type: 'pie',
                radius: ['40%', '70%'],
                label: { formatter: '{b}\n{c}人' },
                data: chartData
              }
            ]
          }
        : {
            tooltip: { trigger: 'axis' },
            grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
            xAxis: {
              type: 'category',
              data: chartData.map(d => d.name),
              axisLabel: { interval: 0, rotate: 20 }
            },
            yAxis: { type: 'value', minInterval: 1 },
            series: [
              {
                type: 'bar',
                data: chartData.map(d => d.value),
                itemStyle: { color: '#409eff' },
                label: { show: true, position: 'top' }
              }
            ]
          }

      chart.setOption(option)
      // ★ 具名 handler，卸载时才能移除监听（见 beforeUnmount 的说明）
      ref.resizeHandler = () => chart.resize()
      window.addEventListener('resize', ref.resizeHandler)
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
      try {
        const res = await api.answer.list(this.surveyId, pageNum, this.answerPage.pageSize)
        this.answers = res.list
        this.answerPage.total = res.total
        this.answerPage.pageNum = res.pageNum
        return true
      } catch {
        // 提示由接口层统一弹出
        return false
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
  }
}
</script>