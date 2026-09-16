<template>
  <div style="padding: 20px;">
    <!-- 顶部栏 -->
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <div>
        <h2 style="margin: 0;">📈 {{ surveyTitle }} - 统计</h2>
        <div style="color: #666; margin-top: 6px;">
          回收数量：<strong style="color: #f56c6c; font-size: 20px;">{{ responseCount }}</strong> 份
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
      <el-card v-for="(q, qIndex) in questions" :key="q.id" style="margin-bottom: 20px;">
        <div style="font-weight: bold; font-size: 16px; margin-bottom: 16px;">
          {{ qIndex + 1 }}. {{ q.title }}
          <el-tag size="small" style="margin-left: 8px;">
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
      <el-table :data="answers" stripe border style="width: 100%;" max-height="400">
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
      <el-empty v-if="answers.length === 0" description="暂无答卷" />
    </el-dialog>

    <!-- 答卷详情对话框 -->
    <el-dialog v-model="detailVisible" :title="`答卷 #${currentAnswerId} 详情`" width="700px">
      <div v-for="(item, idx) in answerDetails" :key="item.id" style="margin-bottom: 16px; padding: 12px; background: #f5f7fa; border-radius: 6px;">
        <div style="font-weight: bold; margin-bottom: 6px;">
          {{ idx + 1 }}. {{ getQuestionTitle(item.questionId) }}
        </div>
        <div style="color: #666;">{{ item.content || '（未作答）' }}</div>
      </div>
      <el-empty v-if="answerDetails.length === 0" description="暂无回答内容" />
    </el-dialog>
  </div>
</template>

<script>
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { Download, View } from '@element-plus/icons-vue'

export default {
  components: { Download, View },
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
      // 答卷详情
      detailVisible: false,
      currentAnswerId: null,
      answerDetails: []
    }
  },
  mounted() {
    this.surveyId = this.$route.params.id
    this.loadAll()
  },
  beforeUnmount() {
    Object.values(this.chartInstances).forEach(item => item.chart && item.chart.dispose())
  },
  methods: {
    async loadAll() {
      try {
        // 1. 问卷信息
        const surveyRes = await fetch(`/api/survey/${this.surveyId}`)
        const survey = await surveyRes.json()
        this.surveyTitle = survey.title

        // 2. 回收数量
        const countRes = await fetch(`/api/answer/count/${this.surveyId}`)
        this.responseCount = await countRes.json()

        // 3. 题目列表
        const qRes = await fetch(`/api/question/list/${this.surveyId}`)
        const questions = await qRes.json()

        // 4. 逐题加载统计
        for (const q of questions) {
          if (q.type === 1 || q.type === 2) {
            const statRes = await fetch(`/api/answer/statistics/${q.id}`)
            q.chartData = await statRes.json()
          } else {
            const textRes = await fetch(`/api/answer/texts/${q.id}`)
            q.textAnswers = await textRes.json()
          }
        }

        this.questions = questions

        this.$nextTick(() => {
          this.questions.forEach(q => {
            if ((q.type === 1 || q.type === 2) && q.chartData && q.chartData.length > 0) {
              this.initChart(q)
            }
          })
        })
      } catch {
        ElMessage.error('加载统计数据失败')
      }
    },

    setChartRef(el, questionId) {
      if (!el) return
      if (!this.chartInstances[questionId]) {
        this.chartInstances[questionId] = { el: null, chart: null }
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
      window.addEventListener('resize', () => chart.resize())
    },

    // 打开答卷列表
    async openAnswerList() {
      try {
        const res = await fetch(`/api/answer/list/${this.surveyId}`)
        this.answers = await res.json()
        this.listVisible = true
      } catch {
        ElMessage.error('加载答卷列表失败')
      }
    },

    // 查看某份答卷详情
    async viewAnswerDetail(row) {
      this.currentAnswerId = row.id
      try {
        const res = await fetch(`/api/answer/detail/${row.id}`)
        this.answerDetails = await res.json()
        this.detailVisible = true
      } catch {
        ElMessage.error('加载答卷详情失败')
      }
    },

    // 根据题目ID查题目标题
    getQuestionTitle(questionId) {
      const q = this.questions.find(item => item.id === questionId)
      return q ? q.title : `题目 #${questionId}`
    },

    // 导出 Excel
    exportExcel() {
      window.open(`http://localhost:8080/api/answer/export/${this.surveyId}`, '_blank')
    }
  }
}
</script>