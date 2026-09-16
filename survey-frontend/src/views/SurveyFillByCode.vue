<template>
  <div style="min-height: 100vh; background: #f0f2f5; padding: 30px 20px;">
    <div v-if="loading" style="text-align: center; padding: 80px; color: #999;">
      正在加载问卷...
    </div>

    <div v-else-if="!survey" style="text-align: center; padding: 80px;">
      <el-result icon="warning" title="问卷不存在或已下线">
        <template #extra>
          <p style="color: #999;">请确认链接是否正确</p>
        </template>
      </el-result>
    </div>

    <div v-else-if="submitted" style="max-width: 600px; margin: 80px auto;">
      <el-result icon="success" title="提交成功！" sub-title="感谢您的参与">
        <template #extra>
          <el-button type="primary" @click="resetForm">再填一份</el-button>
        </template>
      </el-result>
    </div>

    <div v-else style="max-width: 700px; margin: 0 auto;">
      <!-- 问卷头部 -->
      <el-card style="margin-bottom: 20px;">
        <h2 style="margin: 0 0 10px 0;">{{ survey.title }}</h2>
        <p style="color: #666; margin: 0;">{{ survey.description }}</p>

        <div v-if="survey.endTime" style="margin-top: 12px;">
          <el-tag v-if="isExpired" type="danger" size="large">
            ⚠️ 该问卷已于 {{ survey.endTime }} 截止
          </el-tag>
          <el-tag v-else type="warning" size="large">
            ⏰ 距截止：{{ countdown }}
          </el-tag>
        </div>

        <!-- 进度条 -->
        <div v-if="!allDone" style="margin-top: 16px;">
          <div style="display: flex; justify-content: space-between; font-size: 12px; color: #909399; margin-bottom: 6px;">
            <span>填写进度</span>
            <span>{{ answeredCount }} / {{ totalSteps }}</span>
          </div>
          <el-progress :percentage="progressPercent" :stroke-width="6" />
        </div>
      </el-card>

      <!-- 单题显示区 -->
      <el-card v-if="!allDone && currentQuestion" style="margin-bottom: 16px;">
        <div style="font-weight: bold; margin-bottom: 16px; font-size: 16px;">
          <span style="color: #409eff;">第 {{ currentQuestion._order }} 题.</span> {{ currentQuestion.title }}
          <span v-if="currentQuestion.required === 1" style="color: red;">*</span>
        </div>

        <!-- 单选题 -->
        <el-radio-group
          v-if="currentQuestion.type === 1"
          v-model="answers[currentQuestion.id]"
        >
          <el-radio
            v-for="opt in currentQuestion.options"
            :key="opt.id"
            :value="opt.content"
            style="display: block; margin: 10px 0; padding: 10px 14px; border: 1px solid #ebeef5; border-radius: 8px; width: 100%; box-sizing: border-box;"
          >
            {{ opt.content }}
          </el-radio>
        </el-radio-group>

        <!-- 多选题 -->
        <el-checkbox-group
          v-else-if="currentQuestion.type === 2"
          v-model="answers[currentQuestion.id]"
        >
          <el-checkbox
            v-for="opt in currentQuestion.options"
            :key="opt.id"
            :value="opt.content"
            style="display: block; margin: 10px 0; padding: 10px 14px; border: 1px solid #ebeef5; border-radius: 8px; width: 100%; box-sizing: border-box;"
          >
            {{ opt.content }}
          </el-checkbox>
        </el-checkbox-group>

        <!-- 单行输入 -->
        <el-input
          v-else-if="currentQuestion.type === 3"
          v-model="answers[currentQuestion.id]"
          placeholder="请输入您的回答"
          size="large"
        />
      </el-card>

      <!-- 完成页 -->
      <el-card v-else-if="allDone" style="margin-bottom: 16px; text-align: center; padding: 20px;">
        <el-icon :size="48" color="#67c23a"><CircleCheckFilled /></el-icon>
        <h3 style="margin: 12px 0 6px 0;">所有题目已完成</h3>
        <p style="color: #909399; margin: 0;">请点击下方按钮提交问卷</p>
      </el-card>

      <!-- 操作按钮 -->
      <div style="display: flex; justify-content: space-between; margin-top: 20px; margin-bottom: 40px;">
        <el-button
          size="large"
          :disabled="history.length === 0"
          @click="handlePrev"
        >
          上一题
        </el-button>

        <div>
          <el-button
            v-if="!allDone"
            type="primary"
            size="large"
            @click="handleNext"
          >
            下一题
          </el-button>
          <el-button
            v-else
            type="success"
            size="large"
            :disabled="isExpired || survey.status === 2"
            @click="submitAnswer"
          >
            {{ isExpired || survey.status === 2 ? '问卷已截止' : '提交问卷' }}
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import { CircleCheckFilled } from '@element-plus/icons-vue'

export default {
  components: { CircleCheckFilled },
  data() {
    return {
      shareCode: null,
      loading: true,
      survey: null,
      questions: [],
      currentOrder: 1,
      history: [],
      answers: {},
      submitted: false,
      allDone: false,
      countdown: '',
      countdownTimer: null
    }
  },
  computed: {
    isExpired() {
      if (!this.survey || !this.survey.endTime) return false
      const end = new Date(this.survey.endTime.replace(/-/g, '/')).getTime()
      return Date.now() >= end
    },
    currentQuestion() {
      return this.questions.find(q => q._order === this.currentOrder) || null
    },
    answeredCount() {
      return Object.keys(this.answers).filter(k => {
        const v = this.answers[k]
        return v !== undefined && v !== '' && (!Array.isArray(v) || v.length > 0)
      }).length
    },
    totalSteps() {
      return this.computeTotalSteps()
    },
    progressPercent() {
      if (this.totalSteps === 0) return 0
      const percent = Math.round(this.answeredCount / this.totalSteps * 100)
      return Math.min(100, percent)
    }
  },
  mounted() {
    this.shareCode = this.$route.params.shareCode
    this.loadSurvey()
  },
  beforeUnmount() {
    if (this.countdownTimer) clearInterval(this.countdownTimer)
  },
  methods: {
    async loadSurvey() {
      try {
        const res = await fetch(`/api/survey/share/${this.shareCode}`)
        const data = await res.json()
        if (!data || !data.id || data.status !== 1) {
          this.survey = null
          this.loading = false
          return
        }
        this.survey = data
        fetch(`/api/survey/view/${this.shareCode}`, { method: 'POST' }).catch(() => {})
        const qRes = await fetch(`/api/question/list/${data.id}`)
        const list = await qRes.json()
        this.questions = list.map((q, idx) => ({ ...q, _order: idx + 1 }))
        this.startCountdown()
      } catch {
        this.survey = null
      } finally {
        this.loading = false
      }
    },

    /**
     * 根据当前所有答案，模拟走一遍完整路径，返回需要答多少道题
     */
    computeTotalSteps() {
      if (this.questions.length === 0) return 0

      let count = 0
      let currentOrder = 1
      const visited = new Set()

      while (currentOrder <= this.questions.length) {
        if (visited.has(currentOrder)) break
        visited.add(currentOrder)
        count++

        const q = this.questions.find(item => item._order === currentOrder)
        if (!q) break

        const ans = this.answers[q.id]
        const result = this.calcNextOrder(q, ans)

        if (result === -1) break
        if (result <= currentOrder) break
        currentOrder = result
      }

      return count
    },

    /**
     * 计算某题答完后应该跳到哪一题
     * 返回 -1 表示结束问卷
     */
    calcNextOrder(q, ans) {
      let nextOrder = q._order + 1

      // 单选题
      if (q.type === 1 && ans) {
        const matchedOpt = q.options.find(o => o.content === ans)
        if (matchedOpt) {
          if (matchedOpt.nextQuestionId === -1) return -1
          if (matchedOpt.nextQuestionId > 0) nextOrder = matchedOpt.nextQuestionId
        }
      }
      // 多选题：按选项顺序遍历，取第一个勾选的选项
      else if (q.type === 2 && Array.isArray(ans) && ans.length > 0) {
        for (const opt of q.options) {
          if (ans.includes(opt.content)) {
            if (opt.nextQuestionId === -1) return -1
            if (opt.nextQuestionId > 0) nextOrder = opt.nextQuestionId
            break
          }
        }
      }

      return nextOrder
    },

    handleNext() {
      const q = this.currentQuestion
      if (!q) return

      const ans = this.answers[q.id]
      if (q.required === 1) {
        if (ans === undefined || ans === '' || (Array.isArray(ans) && ans.length === 0)) {
          ElMessage.warning('此题必填')
          return
        }
      }

      const nextOrder = this.calcNextOrder(q, ans)

      this.history.push(q._order)

      if (nextOrder === -1 || nextOrder > this.questions.length) {
        this.allDone = true
      } else {
        this.currentOrder = nextOrder
      }
    },

    handlePrev() {
      if (this.history.length === 0) return
      const prev = this.history.pop()
      this.currentOrder = prev
      this.allDone = false
    },

    startCountdown() {
      if (!this.survey.endTime) return
      this.updateCountdown()
      this.countdownTimer = setInterval(() => this.updateCountdown(), 1000)
    },

    updateCountdown() {
      const end = new Date(this.survey.endTime.replace(/-/g, '/')).getTime()
      const now = Date.now()
      const diff = end - now
      if (diff <= 0) {
        this.countdown = '已截止'
        clearInterval(this.countdownTimer)
        return
      }
      const days = Math.floor(diff / (1000 * 60 * 60 * 24))
      const hours = Math.floor((diff % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60))
      const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60))
      const seconds = Math.floor((diff % (1000 * 60)) / 1000)
      this.countdown = `${days}天 ${hours}时 ${minutes}分 ${seconds}秒`
    },

    async submitAnswer() {
      if (this.isExpired || this.survey.status === 2) {
        ElMessage.error('该问卷已截止，无法提交')
        return
      }

      const details = []
      for (const q of this.questions) {
        const ans = this.answers[q.id]
        if (ans === undefined || ans === '') continue
        if (Array.isArray(ans)) {
          for (const c of ans) {
            details.push({ questionId: q.id, content: c })
          }
        } else {
          details.push({ questionId: q.id, content: ans })
        }
      }

      if (details.length === 0) {
        ElMessage.warning('您还没有作答任何题目')
        return
      }

      try {
        const res = await fetch(`/api/answer/submit-share/${this.shareCode}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(details)
        })
        const msg = await res.text()
        if (msg === '提交成功') {
          this.submitted = true
        } else {
          ElMessage.error(msg)
        }
      } catch {
        ElMessage.error('提交失败')
      }
    },

    resetForm() {
      this.submitted = false
      this.answers = {}
      this.currentOrder = 1
      this.history = []
      this.allDone = false
    }
  }
}
</script>