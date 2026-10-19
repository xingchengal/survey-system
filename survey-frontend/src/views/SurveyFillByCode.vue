<template>
  <div style="min-height: 100vh; background: #f0f2f5; padding: 30px 20px;">
    <div v-if="loading" style="text-align: center; padding: 80px; color: #999;">
      正在加载问卷...
    </div>

    <div v-else-if="!survey" style="text-align: center; padding: 80px;">
      <el-result icon="warning" title="问卷不存在或已下线">
        <template #extra>
          <p class="text-gray">请确认链接是否正确</p>
        </template>
      </el-result>
    </div>

    <!-- 不可填写（收满 / 已截止 / 已结束 / 下架）：进页面就告知，别让访客白填一遍 -->
    <div v-else-if="closedReason" style="max-width: 600px; margin: 60px auto;">
      <el-card>
        <h2 style="margin: 0 0 8px 0;">{{ survey.title }}</h2>
        <p v-if="survey.description" style="color: #666; margin: 0 0 16px;">{{ survey.description }}</p>
        <el-alert type="warning" show-icon :closable="false" :title="closedTitle">
          {{ closedHint }}
        </el-alert>
        <div style="margin-top: 16px;">
          <el-button v-if="survey.publicResult === 1" type="primary" @click="goPublicResult">
            查看统计结果
          </el-button>
          <p v-else style="margin: 8px 0 0; font-size: 12px; color: #909399;">
            作者未开启公开结果，暂时看不到统计。
          </p>
        </div>
      </el-card>
    </div>

    <!-- 仅限登录用户填写：未登录只给登录引导、不加载题目（后端也拒收匿名答卷） -->
    <div v-else-if="loginRequired && !loggedIn" style="max-width: 600px; margin: 60px auto;">
      <el-card>
        <h2 style="margin: 0 0 8px 0;">{{ survey.title }}</h2>
        <p v-if="survey.description" style="color: #666; margin: 0 0 16px;">{{ survey.description }}</p>
        <el-alert
          type="info"
          show-icon
          :closable="false"
          title="该问卷仅限登录用户填写"
          description="请先登录或注册新账号，登录后会自动回到这份问卷。"
        />
        <div class="login-gate-actions">
          <el-button type="primary" @click="goLogin">登录</el-button>
          <el-button @click="goRegister">注册新账号</el-button>
        </div>
      </el-card>
    </div>

    <div v-else-if="submitted" style="max-width: 600px; margin: 80px auto;">
      <!-- 考试判分：交卷后立刻出分（匿名答卷没有 user_id，之后再也查不到自己的成绩）；
           只显示分数与是否及格，作者没开「提交后显示答案」时不返回正确答案。 -->
      <el-card v-if="submitResult" class="score-card">
        <div class="score-head">本次得分</div>
        <div class="score-main">
          <span class="score-value">{{ submitResult.earnedScore }}</span>
          <span class="score-total">/ {{ submitResult.totalScore }} 分</span>
        </div>
        <div class="score-meta">
          <el-tag v-if="submitResult.passed === 1" type="success" size="large" round>及格</el-tag>
          <el-tag v-else-if="submitResult.passed === 0" type="danger" size="large" round>未及格</el-tag>
          <span v-if="submitResult.passScore != null" class="score-pass">
            及格线 {{ submitResult.passScore }} 分
          </span>
        </div>
        <!-- 逐题对错：只有作者开启「提交后显示答案」时后端才返回，未开启不渲染（不泄题） -->
        <div v-if="submitResult.questions && submitResult.questions.length" class="score-review">
          <div v-for="qr in submitResult.questions" :key="qr.questionId" class="score-review-row">
            <span class="score-review-title">{{ questionTitle(qr.questionId) }}</span>
            <el-tag :type="qr.correct ? 'success' : 'danger'" size="small">
              {{ qr.correct ? '答对' : '答错' }}
            </el-tag>
            <span class="score-review-answer">
              正确答案：{{ (qr.correctAnswers || []).join('、') || '—' }}
            </span>
          </div>
        </div>
      </el-card>
      <el-result :icon="submitResult ? 'info' : 'success'" :title="submitResult ? '已交卷' : '提交成功！'"
                 :sub-title="thankText">
        <template #extra>
          <div class="submit-actions">
            <el-button v-if="survey.publicResult === 1" type="primary" @click="goPublicResult">
              查看统计结果
            </el-button>
            <el-button v-if="redirectUrl" type="success" @click="goRedirect">立即前往</el-button>
            <!-- 已收满时不再给"再填一份"：点进去也只能白填一遍（提交必被拒） -->
            <el-button v-if="survey.responsesFull !== true" @click="resetForm">再填一份</el-button>
          </div>
          <p v-if="redirectUrl" class="redirect-hint">{{ redirectSeconds }} 秒后自动前往…</p>
        </template>
      </el-result>
    </div>

    <div v-else style="max-width: 700px; margin: 0 auto;">
      <!-- 举报入口悬浮右上角：不占文档流，不会把「填写进度」挤下去；仅登录后显示 -->
      <el-tooltip v-if="loggedIn" content="举报该问卷" placement="left">
        <button type="button" class="report-fab" @click="openReport">
          <el-icon><Warning /></el-icon>
          <span>举报</span>
        </button>
      </el-tooltip>
      <!-- 问卷头部 -->
      <el-card class="mb-20">
        <h2 style="margin: 0 0 10px 0;">{{ survey.title }}</h2>
        <p style="color: #666; margin: 0;">{{ survey.description }}</p>

        <!-- 给填写人一个体量预期（题目数 + 估算时长）；密码门未过/题目未加载时不显示 -->
        <div v-if="passwordVerified && questions.length" class="fill-meta">
          <span>共 {{ questions.length }} 题</span>
          <span class="fill-meta-dot">·</span>
          <span>预计 {{ estimatedMinutes }} 分钟</span>
          <!-- 限时答卷：倒计时（服务端提交时会按上报耗时校验，超时拒收） -->
          <template v-if="timeLeft != null">
            <span class="fill-meta-dot">·</span>
            <span :class="{ 'fill-time-urgent': timeLeftUrgent }">剩余 {{ timeLeftText }}</span>
          </template>
        </div>

        <div v-if="survey.endTime" style="margin-top: 12px;">
          <el-tag v-if="isExpired" type="danger" size="large">
            ⚠️ 该问卷已于 {{ survey.endTime }} 截止
          </el-tag>
          <el-tag v-else type="warning" size="large">
            ⏰ 距截止：{{ countdown }}
          </el-tag>
        </div>


        <!-- 公开结果入口填写前也可见；结果页只展示汇总图表，不含填空原文（脱敏） -->
        <div v-if="survey.publicResult === 1" style="margin-top: 10px;">
          <el-link type="primary" @click="goPublicResult">📊 查看统计结果（作者已公开）</el-link>
        </div>

        <!-- 进度条 -->
        <div v-if="passwordVerified" style="margin-top: 16px;">
          <div style="display: flex; justify-content: space-between; font-size: 12px; color: #909399; margin-bottom: 6px;">
            <span>填写进度</span>
            <span>{{ completedSteps }} / {{ totalSteps }}</span>
          </div>
          <el-progress :percentage="progressPercent" :stroke-width="6" />
        </div>
      </el-card>

      <!-- 接龙报名名单：只显示称呼与时间（后端只返回这两个字段），不暴露其它答案 -->
      <el-card v-if="participants.length" class="mb-20 participants-card">
        <div class="participants-head">
          📋 报名名单（{{ participants.length }} 位）
          <span class="participants-tip">按报名先后排列</span>
        </div>
        <div v-for="p in participants" :key="p.seq" class="participant-row">
          <span class="participant-seq">{{ p.seq }}</span>
          <span class="participant-name">{{ p.name }}</span>
          <span class="participant-time">{{ p.submitTime }}</span>
        </div>
      </el-card>

      <el-card v-if="survey.hasPassword && !passwordVerified" class="mb-20 password-card">
        <div class="password-title">🔒 该问卷需要答题密码</div>
        <p class="password-hint">请向问卷发布者索取密码后填写</p>
        <div class="password-row">
          <el-input
            v-model="passwordInput"
            type="password"
            show-password
            placeholder="请输入答题密码"
            maxlength="20"
            size="large"
            @keyup.enter="verifyPassword"
          />
          <el-button type="primary" size="large" :loading="verifying" @click="verifyPassword">
            进入问卷
          </el-button>
        </div>
        <p v-if="passwordError" class="password-error">{{ passwordError }}</p>
      </el-card>

      <template v-else>
      <!-- 单题显示区 -->
      <el-card class="mb-16" v-if="!allDone && currentQuestion">
        <div style="font-weight: bold; margin-bottom: 16px; font-size: 16px;">
          <span style="color: #409eff;">第 {{ currentQuestion._order }} 题.</span> {{ currentQuestion.title }}
          <span v-if="currentQuestion.required === 1" style="color: red;">*</span>
        </div>

        <!-- 选项题没有任何有内容的选项：不能渲染空 value 的控件（点一个会整组选中、下拉空面板），
             这里给出提示，并允许跳过（否则必填会把访客永久卡住）。 -->
        <div
          v-if="optionsBroken"
          class="option-broken"
        >
          ⚠️ 该题的选项未配置完整（内容为空），暂时无法作答。请联系问卷发布者补充选项后再填。
        </div>

        <!-- 矩阵题行列缺失：同样不能渲染成一张空表（必填时会永远卡住） -->
        <div v-if="matrixBroken" class="option-broken">
          ⚠️ 该矩阵题的行列未配置完整，暂时无法作答。请联系问卷发布者补充行列后再填。
        </div>

        <!-- 重复选项：同名 value 会"点一个选中两个"，这里不渲染选项、直接说明原因 -->
        <div v-if="optionsDuplicated" class="option-broken">
          ⚠️ 该题有重复选项（{{ duplicateOptions.join('、') }}），暂时无法作答。请联系问卷发布者把选项改成不同内容后再填。
        </div>

        <!-- 单选题 -->
        <el-radio-group
          v-if="currentQuestion.type === 1 && usableOptions.length && !optionsDuplicated"
          v-model="answers[currentQuestion.id]"
          class="fill-options"
        >
          <el-radio
            v-for="opt in usableOptions"
            :key="opt.id"
            :value="opt.content"
            style="display: block; margin: 10px 0; padding: 10px 14px; border: 1px solid #ebeef5; border-radius: 8px; width: 100%; box-sizing: border-box;"
          >
            {{ opt.content }}
          </el-radio>
        </el-radio-group>

        <!-- 多选题 -->
        <el-checkbox-group
          v-else-if="currentQuestion.type === 2 && usableOptions.length && !optionsDuplicated"
          v-model="answers[currentQuestion.id]"
          class="fill-options"
        >
          <el-checkbox
            v-for="opt in usableOptions"
            :key="opt.id"
            :value="opt.content"
            style="display: block; margin: 10px 0; padding: 10px 14px; border: 1px solid #ebeef5; border-radius: 8px; width: 100%; box-sizing: border-box;"
          >
            {{ opt.content }}
          </el-checkbox>
        </el-checkbox-group>

        <!-- 下拉选择 -->
        <el-select
          v-else-if="currentQuestion.type === 5 && usableOptions.length && !optionsDuplicated"
          v-model="answers[currentQuestion.id]"
          placeholder="请选择…"
          size="large"
          style="width: 100%;"
        >
          <el-option
            v-for="opt in usableOptions"
            :key="opt.id"
            :label="opt.content"
            :value="opt.content"
          />
        </el-select>

        <!-- 评分（1~5 星） -->
        <div v-else-if="currentQuestion.type === 6" class="fill-rating">
          <span
            v-for="score in 5"
            :key="score"
            class="fill-star"
            :class="{ on: Number(answers[currentQuestion.id]) >= score }"
            @click="answers[currentQuestion.id] = String(score)"
          >★</span>
          <span class="fill-rating-text">
            {{ answers[currentQuestion.id] ? `${answers[currentQuestion.id]} 分 · ${ratingLabels[Number(answers[currentQuestion.id]) - 1]}` : '点击星星打分' }}
          </span>
        </div>

        <!-- 日期 -->
        <el-date-picker
          v-else-if="currentQuestion.type === 7"
          v-model="answers[currentQuestion.id]"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          size="large"
          style="width: 220px;"
        />

        <!-- 矩阵题：行 × 列，每行选一个列 -->
        <div v-else-if="currentQuestion.type === 8 && !matrixBroken" class="fill-matrix-wrap">
          <table class="fill-matrix">
            <thead>
              <tr>
                <th></th>
                <th v-for="(col, ci) in matrixCols" :key="'mc' + ci">{{ col }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, ri) in matrixRows" :key="'mr' + ri">
                <th>{{ row }}</th>
                <td v-for="(col, ci) in matrixCols" :key="'md' + ci">
                  <input
                    type="radio"
                    :name="'fm-' + currentQuestion.id + '-' + ri"
                    :checked="matrixPick(row) === col"
                    @change="pickMatrix(row, col)"
                  />
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 排序题：数组顺序就是名次，上下移动调整 -->
        <div v-else-if="currentQuestion.type === 9 && usableOptions.length && !optionsDuplicated" class="fill-ranking">
          <div v-for="(item, ri) in rankingAnswers" :key="'rk' + ri" class="fill-rank-row">
            <span class="fill-rank-no">{{ ri + 1 }}</span>
            <span class="fill-rank-text">{{ item }}</span>
            <el-button size="small" :disabled="ri === 0" @click="moveRank(ri, -1)">上移</el-button>
            <el-button size="small" :disabled="ri === rankingAnswers.length - 1" @click="moveRank(ri, 1)">下移</el-button>
          </div>
          <p class="fill-rank-tip">第 1 名在最上面；全部选项都要排名后才能进入下一题。</p>
        </div>

        <!-- NPS：0~10 -->
        <div v-else-if="currentQuestion.type === 10" class="fill-nps">
          <button
            v-for="score in npsScores"
            :key="'nps' + score"
            type="button"
            class="fill-nps-btn"
            :class="{ on: answers[currentQuestion.id] === score }"
            @click="answers[currentQuestion.id] = score"
          >{{ score }}</button>
          <p class="fill-nps-tip">0 = 完全不会推荐，10 = 非常愿意推荐</p>
        </div>

        <!-- 多行文本 -->
        <el-input
          v-else-if="currentQuestion.type === 4"
          v-model="answers[currentQuestion.id]"
          type="textarea"
          :rows="4"
          placeholder="请输入您的回答"
          maxlength="1000"
          show-word-limit
        />

        <!-- 单行输入 -->
        <el-input
          v-else-if="currentQuestion.type === 3"
          v-model="answers[currentQuestion.id]"
          placeholder="请输入您的回答"
          maxlength="1000"
          show-word-limit
          size="large"
        />
      <!-- maxlength 必须与后端 TextLimits.ANSWER_CONTENT(1000) / varchar(1000) 对齐，否则提交吃 400 -->
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
      </template>
    </div>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import { CircleCheckFilled, Warning } from '@element-plus/icons-vue'
// 本页是匿名答题页：题目加载与提交**不传 silent**（必须让访客看到失败原因），
// 其余调用用 silent，避免匿名流程被"登录过期"跳转打断。
import api from '../api'
import { isLoggedIn } from '../utils/auth'
import {
  NPS_SCORES, RATING_LABELS, hasOptions as hasOptionType,
  parseMatrixAxis, isMatrix, isRanking, isNps
} from '../utils/questionTypes'

export default {
  components: { CircleCheckFilled, Warning },
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
      /** 考试判分的交卷结果（null = 普通问卷 / 未判分）；只在"提交成功"页用来渲染成绩卡 */
      submitResult: null,
      /** 是否已登录：举报需要登录身份，匿名访客不显示入口（点了也只会 401） */
      loggedIn: false,
      /**
       * 为什么现在不能填：'' = 可以填；'full' 收满 / 'expired' 截止 / 'ended' 结束 / 'suspended' 下架。
       */
      closedReason: '',
      allDone: false,
      countdown: '',
      countdownTimer: null,
      /** 已验证通过的密码（提交时放请求头） */
      password: '',
      /** 密码输入框 */
      passwordInput: '',
      passwordVerified: false,
      verifying: false,
      passwordError: '',
      redirectSeconds: 3,
      redirectTimer: null,
      /** 评分题的 5 档文案（与后端 1~5 分对应） */
      ratingLabels: RATING_LABELS,
      /** NPS 的 0~10 分 */
      npsScores: NPS_SCORES,
      /** 打开答题页的时间戳：提交时上报答题耗时（客户端值、可伪造，后端只作质控提示） */
      startedAt: 0,
      /** 限时答卷：剩余秒数（null = 不限时），由 timeLimitTimer 每秒递减 */
      timeLeft: null,
      /** 限时倒计时器（与 endTime 的 countdownTimer 是两回事） */
      timeLimitTimer: null,
      /** 是否已经提示过"时间到"（避免每秒弹一次） */
      timeUpNotified: false,
      /** 公开报名名单（作者开启「公开报名名单」时才有；只含称呼与时间） */
      participants: []
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
    totalSteps() {
      return this.computeTotalSteps()
    },
    /** 估算填写时长（每题 20 秒，向上取整，至少 1 分钟） */
    estimatedMinutes() {
      return Math.max(1, Math.ceil((this.questions.length * 20) / 60))
    },
    /** 当前题是不是"有选项"的题型（单选 / 多选 / 下拉） */
    currentHasOptions() {
      return hasOptionType(this.currentQuestion && this.currentQuestion.type)
    },
    /** 该问卷是否仅限登录用户填写（作者在设置里开的；默认 false = 匿名） */
    loginRequired() {
      return !!this.survey && this.survey.requireLogin === 1
    },
    /** 当前题可用选项（滤掉内容为空的脏选项：空 value 会让整组选项同值） */
    usableOptions() {
      const opts = (this.currentQuestion && this.currentQuestion.options) || []
      return opts.filter(o => typeof o.content === 'string' && o.content.trim() !== '')
    },
    /** 选项题却一个可用选项都没有：页面提示"配置不完整"，且不拦"下一题"（否则必填会卡死） */
    optionsBroken() {
      return this.currentHasOptions && this.usableOptions.length === 0
    },
    /**
     * 当前题的重复选项（去首尾空白后同名）：同名 value 会"点一个选中两个"，有重复时不渲染控件。
     */
    duplicateOptions() {
      const seen = new Set()
      const dups = []
      for (const o of this.usableOptions) {
        const key = o.content.trim()
        if (seen.has(key)) {
          if (!dups.includes(key)) dups.push(key)
        } else {
          seen.add(key)
        }
      }
      return dups
    },
    /** 有重复选项 → 不渲染选项控件（否则"点一送一"），改为提示联系作者 */
    optionsDuplicated() {
      return this.currentHasOptions && this.duplicateOptions.length > 0
    },
    /** 矩阵题的行/列标题（后端给 JSON 文本，这里解析成数组） */
    matrixRows() {
      return parseMatrixAxis(this.currentQuestion && this.currentQuestion.matrixRows)
    },
    matrixCols() {
      return parseMatrixAxis(this.currentQuestion && this.currentQuestion.matrixCols)
    },
    /** 矩阵题行列配置不完整：与"空选项"同理，不能让访客卡死在一道渲染不出来的题上 */
    matrixBroken() {
      const q = this.currentQuestion
      return !!q && isMatrix(q.type) && (this.matrixRows.length === 0 || this.matrixCols.length < 2)
    },
    /** 排序题当前顺序（数组顺序即名次）；未作答时以原始选项顺序为起点 */
    rankingAnswers() {
      const q = this.currentQuestion
      if (!q || !isRanking(q.type)) return []
      const ans = this.answers[q.id]
      if (Array.isArray(ans) && ans.length > 0) return ans
      return this.usableOptions.map(o => o.content)
    },
    /**
     * 进度分子 = 当前答题路径上已走过的题数（含被跳过的非必填题）；
     * 不能用"已答题目数 / 总步数"——跳过一题后进度就再也不动。
     */
    completedSteps() {
      if (this.totalSteps === 0) return 0
      if (this.allDone) return this.totalSteps
      const { path } = this.previewPath(1, this.answers[this.questions[0] && this.questions[0].id])
      let done = 0
      for (const order of path) {
        if (order < this.currentOrder) {
          done++
        } else if (order === this.currentOrder) {
          const q = this.questions.find(item => item._order === order)
          if (q && this.hasAnswer(q.id)) done++
        }
      }
      return done
    },
    progressPercent() {
      if (this.totalSteps === 0) return 0
      return Math.min(100, Math.round(this.completedSteps / this.totalSteps * 100))
    },
    /** 不可填写页的标题（三种原因各一句话） */
    closedTitle() {
      if (this.closedReason === 'full') {
        return `该问卷已收满 ${this.survey.maxResponses} 份答卷，无法继续填写`
      }
      if (this.closedReason === 'suspended') {
        return '该问卷已被下架，无法继续填写'
      }
      if (this.closedReason === 'expired') {
        return `该问卷已于 ${this.survey.endTime} 截止，无法继续填写`
      }
      return '该问卷已结束，无法继续填写'
    },
    /** 不可填写页的说明：讲清"为什么现在拦"，避免访客以为是页面坏了 */
    closedHint() {
      if (this.closedReason === 'full') {
        return '收集上限由作者在「设置」里设定；达到上限后提交一律会被拒收，所以这里直接拦住，不用白填一遍。'
      }
      if (this.closedReason === 'suspended') {
        return '该问卷因举报被管理员下架，暂时不接收答卷。'
      }
      if (this.closedReason === 'expired') {
        return '到了截止时间就不再收答卷（提交会被后端拒绝），所以不再让你从头填。'
      }
      return '作者已经把这份问卷结束了，不再接收答卷。'
    },
    /** 提交成功页的感谢语：作者自定义优先，否则用默认文案 */
    thankText() {
      const text = this.survey && this.survey.thankText
      return text && text.trim() ? text : '感谢您的参与'
    },
    /** 提交后跳转地址：后端已校验只允许 http/https，前端再挡一次（防脏数据/手动改库） */
    redirectUrl() {
      const url = this.survey && this.survey.redirectUrl
      return url && /^https?:\/\//i.test(url) ? url : ''
    },
    /** 限时答卷的剩余时间文案（mm:ss；不限时返回空串） */
    timeLeftText() {
      if (this.timeLeft == null) return ''
      const m = Math.floor(this.timeLeft / 60)
      const s = this.timeLeft % 60
      return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
    },
    /** 剩余不足 1 分钟时高亮提示 */
    timeLeftUrgent() {
      return this.timeLeft != null && this.timeLeft <= 60
    }
  },
  mounted() {
    this.shareCode = this.$route.params.shareCode
    this.loggedIn = isLoggedIn()
    // 从"打开页面"开始计时（而不是等题目加载完）：用户的阅读时间也该算进答题耗时
    this.startedAt = Date.now()
    this.loadSurvey()
  },
  beforeUnmount() {
    if (this.countdownTimer) clearInterval(this.countdownTimer)
    if (this.redirectTimer) clearInterval(this.redirectTimer)
    this.clearTimeLimit()
  },
  methods: {
    async loadSurvey() {
      try {
        // 匿名答题页：byShareCode 传 silent（链接失效由页面显示空态），失败不触发登录跳转。
        const data = await api.survey.byShareCode(this.shareCode, { silent: true })
        if (!data || !data.id) {
          this.survey = null
          this.loading = false
          return
        }
        this.survey = data
        // 记一次访问量：silent 且不 await（失败不打扰访客，也不拖慢首屏）
        api.survey.view(this.shareCode).catch(() => {})
        // 接龙报名名单：作者开启后异步拉取，失败静默（不影响答题）
        if (data.showParticipants === 1) {
          this.loadParticipants()
        }
        // 草稿 / 回收站等（既不是已发布也不是已结束）→ 对外一律当作"不存在或已下线"
        if (data.status !== 1 && data.status !== 2) {
          this.survey = null
          this.loading = false
          return
        }
        // 三种"现在填不了"统一在进页面时判定，不让访客白填一遍：
        // 已结束（status=2）/ 已收满（responsesFull）/ 已过截止时间；判定放在密码门之前。
        if (data.status === 2) {
          this.closedReason = 'ended'
          this.loading = false
          return
        }
        // 被举报下架：status 仍是 1，只能看 lifecycle
        if (data.lifecycle === 'SUSPENDED') {
          this.closedReason = 'suspended'
          this.loading = false
          return
        }
        if (data.responsesFull === true) {
          this.closedReason = 'full'
          this.loading = false
          return
        }
        if (this.isExpired) {
          this.closedReason = 'expired'
          this.loading = false
          return
        }
        // 仅限登录用户填写：未登录不加载题目（服务端同样拒收匿名答卷）
        if (data.requireLogin === 1 && !this.loggedIn) {
          this.loading = false
          return
        }
        // 有答题密码时先不加载题目：后端按请求头校验，前端把密码门提前
        if (data.hasPassword) {
          this.loading = false
          return
        }
        // 无密码问卷必须置为已验证：进度条与题目区都以 passwordVerified 为门控
        this.passwordVerified = true
        await this.loadQuestions()
      } catch {
        // 加载失败 → 走"问卷不存在"空态；错误提示由 src/api/http.js 统一负责。
        this.survey = null
      } finally {
        this.loading = false
      }
    },

    /**
     * ★ 加载题目：有密码时带 X-Survey-Password 请求头（密码只放请求头，不进 query/URL）。
     */
    async loadQuestions() {
      const list = await api.question.list(this.survey.id, this.requestOpts())
      this.questions = list.map((q, idx) => ({ ...q, _order: idx + 1 }))
      this.initAnswers()
      this.startCountdown()
      this.startTimeLimit()
    },

    /**
     * 按题型初始化答案容器：多选/矩阵/排序需要数组或对象，不提前建好时 v-model 会往 undefined 上写。
     * 排序题以原始选项顺序为起点。
     */
    initAnswers() {
      const next = { ...this.answers }
      this.questions.forEach(q => {
        if (isMatrix(q.type)) {
          next[q.id] = {}
        } else if (isRanking(q.type)) {
          next[q.id] = (q.options || [])
            .map(o => o.content)
            .filter(c => typeof c === 'string' && c.trim() !== '')
        } else if (q.type === 2) {
          next[q.id] = []
        }
      })
      this.answers = next
    },

    /** 有密码时带上 X-Survey-Password 请求头，否则不额外传参 */
    requestOpts() {
      return this.password
        ? { headers: { 'X-Survey-Password': this.password } }
        : undefined
    },

    /**
     * 校验答题密码：直接用"带密码请求题目"验证，不额外开只判密码的端点。
     * 传 silent：密码错误要在密码框下内联提示，不再弹红字。
     */
    async verifyPassword() {
      const pwd = this.passwordInput.trim()
      if (!pwd) {
        this.passwordError = '请输入答题密码'
        return
      }
      this.verifying = true
      this.passwordError = ''
      try {
        const list = await api.question.list(this.survey.id, {
          silent: true,
          headers: { 'X-Survey-Password': pwd }
        })
        this.password = pwd
        this.passwordVerified = true
        this.questions = list.map((q, idx) => ({ ...q, _order: idx + 1 }))
        this.initAnswers()
        this.startCountdown()
        this.startTimeLimit()
      } catch (e) {
        this.passwordError = (e && e.message) || '密码不正确，请重试'
      } finally {
        this.verifying = false
      }
    },

    /** 举报：弹框填理由（后端校验 1~500 字、不能举报自己、草稿不可举报） */
    async openReport() {
      let reason
      try {
        const { value } = await this.$prompt('请描述举报理由（例如：内容违规、诱导分享）', '举报该问卷', {
          inputType: 'textarea',
          confirmButtonText: '提交举报',
          cancelButtonText: '取消'
        })
        reason = (value || '').trim()
      } catch {
        return // 用户取消
      }
      await api.survey.report(this.survey.id, reason)
      ElMessage.success('举报已提交，管理员会尽快处理')
    },

    goPublicResult() {
      this.$router.push(`/r/${this.shareCode}`)
    },

    goRedirect() {
      if (this.redirectUrl) window.location.href = this.redirectUrl
    },

    /** 提交成功后：有跳转链接就倒计时 3 秒自动跳（也给"立即前往"按钮） */
    startRedirectTimer() {
      if (!this.redirectUrl) return
      this.redirectSeconds = 3
      if (this.redirectTimer) clearInterval(this.redirectTimer)
      this.redirectTimer = setInterval(() => {
        this.redirectSeconds -= 1
        if (this.redirectSeconds <= 0) {
          clearInterval(this.redirectTimer)
          this.redirectTimer = null
          this.goRedirect()
        }
      }, 1000)
    },

    /** 走一遍完整答题路径，返回本次需要答的题数 */
    computeTotalSteps() {
      if (this.questions.length === 0) return 0

      // 复用 previewPath：进度、路径过滤、清理三处必须共用同一套跳转语义。
      return this.previewPath(1, this.answers[this.questions[0] && this.questions[0].id]).path.length
    },

    /**
     * ★ 计算某题答完后跳到哪一道题：返回值必须是 question.id，不能用 1 基序号
     * （问卷可被编辑，序号会在删题/调序后静默指向错的题）。
     * @returns {number} 目标题 id；-1 = 结束问卷；目标不存在时也按"结束"处理
     */
    calcNextQuestionId(q, ans) {
      // 单选 / 下拉：都是"一个答案对应一个选项"
      if ((q.type === 1 || q.type === 5) && ans) {
        const matchedOpt = q.options.find(o => o.content === ans)
        if (matchedOpt && matchedOpt.nextQuestionId !== null && matchedOpt.nextQuestionId !== undefined) {
          return matchedOpt.nextQuestionId          // -1（结束）或目标题 id
        }
      }
      // 多选题：按选项顺序遍历，取第一个勾选的选项
      else if (q.type === 2 && Array.isArray(ans) && ans.length > 0) {
        for (const opt of q.options) {
          if (ans.includes(opt.content)) {
            if (opt.nextQuestionId !== null && opt.nextQuestionId !== undefined) {
              return opt.nextQuestionId
            }
            break
          }
        }
      }
      return null   // 没有跳转规则 → 由调用方按"顺序做下一题"处理
    },

    /**
     * 预算"在 order 这道题选了 answer 之后"的答题路径，返回完整路径（1 基序号）与下一站：
     * path 供进度条/history/清理共用；next = -1 到终点，> questions.length 表示结束。
     * 跳转规则存的是题 id，这里换算成序号推进，并在序号域上做防环（目标必须大于当前题）。
     */
    previewPath(order, answer) {
      const path = []
      const visited = new Set()
      let currentOrder = order
      while (currentOrder >= 1 && currentOrder <= this.questions.length) {
        if (visited.has(currentOrder)) break
        visited.add(currentOrder)
        path.push(currentOrder)
        const q = this.questions.find(item => item._order === currentOrder)
        if (!q) break
        // 只在起点用"还没提交的草稿答案"；后续题一律用已存答案
        const ans = currentOrder === order ? answer : this.answers[q.id]
        const targetId = this.calcNextQuestionId(q, ans)
        if (targetId === -1) return { path, next: -1 }
        if (targetId === null || targetId === undefined) {
          currentOrder = currentOrder + 1          // 没规则 → 顺序下一题
          continue
        }
        const target = this.questions.find(item => item.id === targetId)
        if (!target) {
          // 目标题不存在（历史脏数据 / 目标题已被删）→ 与"结束"同义，别静默继续往下问
          return { path, next: this.questions.length + 1 }
        }
        if (target._order <= currentOrder) {
          // 目标不在当前题之后（含"指回自己"）—— 不允许后退，按结束处理（防环）
          return { path, next: this.questions.length + 1 }
        }
        currentOrder = target._order
      }
      return { path, next: this.questions.length + 1 }
    },

    /**
     * 回退改选后，把"沿新路径不再会走到"的填空答案就地清掉（只看 order 之后、不在新路径上的题）。
     * 选择/评分/日期类保留，方便用户回头再选；不弹提示（属于实现细节）。
     *
     * @returns {number} 实际清掉的题目数（仅用于测试/排查）
     */
    pruneStaleAnswers(order) {
      const q = this.questions.find(item => item._order === order)
      if (!q) return 0
      const { path } = this.previewPath(order, this.answers[q.id])
      const onPath = new Set(path)
      let cleared = 0
      for (const item of this.questions) {
        if (item._order <= order) continue              // 只清"后面"的
        if (onPath.has(item._order)) continue            // 新路径仍然会走到
        if (item.type !== 3 && item.type !== 4) continue    // 选择类/评分/日期保留（可回头再选）
        if (this.answers[item.id] === undefined) continue
        delete this.answers[item.id]
        cleared++
      }
      return cleared
    },

    /**
     * 沿"第 1 题 + 每题按已选项跳转"走一遍，返回本次答题路径上的题目 id 集合；
     * 走法与 computeTotalSteps() 共用 previewPath，所以"进度算几步"与"提交几题"永远一致。
     */
    answerPathQuestionIds() {
      // 复用 previewPath（与进度条、清理逻辑共用同一套走法）
      const { path } = this.previewPath(1, this.answers[this.questions[0] && this.questions[0].id])
      const byOrder = new Map(this.questions.map(item => [item._order, item.id]))
      return new Set(path.map(order => byOrder.get(order)).filter(id => id !== undefined))
    },

    handleNext() {
      const q = this.currentQuestion
      if (!q) return

      const ans = this.answers[q.id]
      // 选项题没有可用选项 / 有重复选项：控件已被隐藏，拦必填会让访客永远过不去，按"跳过 + 说明"处理。
      if (this.optionsBroken || this.optionsDuplicated) {
        ElMessage.warning(this.optionsDuplicated
          ? '该题有重复选项，已跳过这道题'
          : '该题选项未配置，已跳过这道题')
      } else if (this.matrixBroken) {
        ElMessage.warning('该矩阵题行列未配置，已跳过这道题')
      } else if (q.required === 1) {
        if (!this.isAnswerComplete(q)) {
          // 矩阵/排序的"没答完"与"完全没答"要分开说：否则用户明明填了 2 行，
          //   却被一句"此题必填"顶回来，根本不知道还差哪一行。
          if (isMatrix(q.type) && this.hasAnswer(q.id)) {
            ElMessage.warning('这道矩阵题还有行没有选择，请把每一行都选上')
          } else if (isRanking(q.type) && Array.isArray(ans) && ans.length > 0 &&
            ans.filter(item => !this.isBlankAnswer(item)).length !== this.usableOptions.length) {
            ElMessage.warning('排序题需要给全部选项排名')
          } else {
            ElMessage.warning('此题必填')
          }
          return
        }
      }

      // 跳转目标存的是题 id，页面推进用序号：这里做一次 id → 序号换算；
      // 目标不存在或不在当前题之后（脏数据/已删题）→ 按结束处理，避免死循环。
      const targetId = this.calcNextQuestionId(q, ans)
      let nextOrder
      if (targetId === -1) {
        nextOrder = -1
      } else if (targetId === null || targetId === undefined) {
        nextOrder = q._order + 1                     // 没规则 → 顺序下一题
      } else {
        const target = this.questions.find(item => item.id === targetId)
        nextOrder = (!target || target._order <= q._order) ? this.questions.length + 1 : target._order
      }

      // 改选会让后续路径变化，先把"不会再走到"的填空答案清掉（选择类保留）
      this.pruneStaleAnswers(q._order)

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

    /** 空答案口径：undefined / null / 纯空白字符串都不算作答 */
    isBlankAnswer(v) {
      return v === undefined || v === null || (typeof v === 'string' && v.trim() === '')
    },

    /** 某题是否已作答（多选里只要有**一个非空**选项就算答过） */
    hasAnswer(questionId) {
      const v = this.answers[questionId]
      if (Array.isArray(v)) return v.some(item => !this.isBlankAnswer(item))
      return !this.isBlankAnswer(v)
    },

    /** 矩阵题某一行当前选了哪个列（没选返回空串） */
    matrixPick(row) {
      const q = this.currentQuestion
      const ans = q && this.answers[q.id]
      if (!ans || typeof ans !== 'object') return ''
      return ans[row] || ''
    },

    pickMatrix(row, col) {
      const q = this.currentQuestion
      if (!q) return
      if (!this.answers[q.id] || typeof this.answers[q.id] !== 'object') {
        this.answers[q.id] = {}
      }
      this.answers[q.id][row] = col
    },

    /** 排序题：上移/下移一位（数组顺序 = 名次） */
    moveRank(index, delta) {
      const q = this.currentQuestion
      if (!q || !isRanking(q.type)) return
      if (!Array.isArray(this.answers[q.id])) {
        this.answers[q.id] = this.usableOptions.map(o => o.content)
      }
      const list = this.answers[q.id]
      const target = index + delta
      if (target < 0 || target >= list.length) return
      const [item] = list.splice(index, 1)
      list.splice(target, 0, item)
    },

    /**
     * 必填题是否完整作答（比 hasAnswer 严格）：矩阵每一行都要选、排序必须给全部选项排名。
     */
    isAnswerComplete(q) {
      const ans = this.answers[q.id]
      if (isMatrix(q.type)) {
        const rows = parseMatrixAxis(q.matrixRows)
        if (!ans || typeof ans !== 'object' || rows.length === 0) return false
        return rows.every(row => !this.isBlankAnswer(ans[row]))
      }
      if (isRanking(q.type)) {
        const total = (q.options || [])
          .filter(o => typeof o.content === 'string' && o.content.trim() !== '').length
        return Array.isArray(ans) && total > 0 &&
          ans.filter(item => !this.isBlankAnswer(item)).length === total
      }
      if (Array.isArray(ans)) return ans.some(item => !this.isBlankAnswer(item))
      return !this.isBlankAnswer(ans)
    },

    startCountdown() {
      if (!this.survey.endTime) return
      this.updateCountdown()
      this.countdownTimer = setInterval(() => this.updateCountdown(), 1000)
    },

    /** 拉取公开报名名单（失败静默：它只是展示，不该影响答题） */
    async loadParticipants() {
      try {
        this.participants = (await api.answer.participants(this.shareCode)) || []
      } catch {
        this.participants = []
      }
    },

    /**
     * 限时答卷：剩余时间从打开页面（startedAt）算起，与提交上报的 durationMs 同一口径。
     * 到 0 自动交卷（服务端对超时是拒收的，不自动交答案就作废）。
     */
    startTimeLimit() {
      const limit = this.survey && this.survey.timeLimitSeconds
      if (!limit || limit <= 0 || this.timeLimitTimer) return
      const elapsed = this.startedAt ? Math.floor((Date.now() - this.startedAt) / 1000) : 0
      this.timeLeft = Math.max(0, limit - elapsed)
      if (this.timeLeft === 0) {
        this.onTimeUp()
        return
      }
      this.timeLimitTimer = setInterval(() => {
        if (this.timeLeft == null) return
        this.timeLeft = Math.max(0, this.timeLeft - 1)
        if (this.timeLeft === 0) this.onTimeUp()
      }, 1000)
    },

    clearTimeLimit() {
      if (this.timeLimitTimer) {
        clearInterval(this.timeLimitTimer)
        this.timeLimitTimer = null
      }
    },

    onTimeUp() {
      this.clearTimeLimit()
      if (this.timeUpNotified || this.submitted) return
      this.timeUpNotified = true
      ElMessage.warning('答题时间到，正在为你提交当前答案…')
      this.submitAnswer()
    },

    /** 成绩卡里按 id 找题干（题目已加载；找不到时给一个不误导的占位） */
    questionTitle(questionId) {
      const q = this.questions.find(x => x.id === questionId)
      return q ? q.title : `题目 #${questionId}`
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

      // 只提交本次答题路径上的答案：被条件跳转跳过、或回退改选后旧分支里的答案都不能带上，
      // 否则会污染统计（后端直接按 content 分组计数）。
      const onPath = this.answerPathQuestionIds()
      const details = []
      for (const q of this.questions) {
        if (!onPath.has(q.id)) continue
        const ans = this.answers[q.id]

        // 矩阵：一行一条明细，row_key 带行标题、content 带列标题（后端统计按行分组）
        if (isMatrix(q.type)) {
          if (!ans || typeof ans !== 'object') continue
          for (const row of parseMatrixAxis(q.matrixRows)) {
            const col = ans[row]
            if (this.isBlankAnswer(col)) continue
            details.push({ questionId: q.id, content: col, rowKey: row })
          }
          continue
        }

        // 排序：一名一条明细，rankNo 从 1 起（数组顺序即名次）
        if (isRanking(q.type)) {
          if (!Array.isArray(ans)) continue
          const ranked = ans.filter(item => !this.isBlankAnswer(item))
          if (ranked.length === 0) continue
          // 不能复用 usableOptions：那个 computed 只看"当前题"，
          //   提交时要按**这道题自己的**选项数校验。
          const optionCount = (q.options || []).filter(o => typeof o.content === 'string' && o.content.trim() !== '').length
          if (ranked.length !== optionCount) {
            ElMessage.warning('排序题需要给全部选项排名')
            return
          }
          ranked.forEach((content, idx) => {
            details.push({ questionId: q.id, content, rankNo: idx + 1 })
          })
          continue
        }

        // NPS：0~10 的单值
        if (isNps(q.type)) {
          if (this.isBlankAnswer(ans)) continue
          details.push({ questionId: q.id, content: String(ans) })
          continue
        }

        if (ans === undefined || ans === null || ans === '') continue
        if (Array.isArray(ans)) {
          if (ans.length === 0) continue
          for (const c of ans) {
            details.push({ questionId: q.id, content: c })
          }
        } else {
          // 纯空白的填空不算作答（后端与必答校验同口径）
          if (typeof ans === 'string' && ans.trim() === '') continue
          details.push({ questionId: q.id, content: ans })
        }
      }

      if (details.length === 0) {
        ElMessage.warning('您还没有作答任何题目')
        return
      }

      try {
        // 提交接口不传 silent：失败原因（已截止/已下线/网络异常）必须让访客看到；
        // 该接口在白名单内不会 401，所以不会触发登录跳转。
        // ★ durationMs 由前端上报，可伪造，后端只用于"答题过快"质控；未计时则传 null。
        const durationMs = this.startedAt ? Math.max(0, Date.now() - this.startedAt) : null
        // 返回值带判分结果（普通问卷 graded=false，不渲染成绩卡）。
        // 实名问卷必须走带 token 的 submit：submit-share 的 userId 恒为 null，后端会 400。
        const result = this.loginRequired
          ? await api.answer.submit(this.survey.id, details, durationMs)
          : await api.answer.submitByShareCode(this.shareCode, details, durationMs, this.requestOpts())
        this.submitResult = result && result.graded ? result : null
        this.submitted = true
        this.clearTimeLimit()
        this.startRedirectTimer()
        // 提交成功后静默刷新分享信息：限量问卷往往"你交完就满了"，刷新后"再填一份"会自动消失。
        this.refreshShareInfo()
      } catch (e) {
        // 实名问卷同一账号二次提交会被 uk_survey_user 挡成 400：把上次成绩取回来展示，
        // 比只弹红字有用（也避免用户以为是网络问题反复点）。
        if (this.loginRequired && e && e.status === 400
            && String(e.message || '').includes('已经提交过')) {
          await this.loadMyScore()
          return
        }
        // 其它失败红字已由 http.js 弹出；保持当前页，访客可以改完再提交。
      }
    },

    /**
     * 取回"我在本问卷上次的成绩"并直接展示（实名问卷重复提交时用）；只有登录用户能查。
     */
    async loadMyScore() {
      try {
        const score = await api.answer.score(this.survey.id)
        this.submitResult = { graded: true, questions: null, ...score }
        this.submitted = true
        this.clearTimeLimit()
      } catch {
        // 失败提示由 http.js 负责；页面保持原样即可
      }
    },

    /** 登录 / 注册：带上当前问卷地址，登录后自动回到这里（Login/Register 支持 redirect） */
    goLogin() {
      this.$router.push({ path: '/login', query: { redirect: this.$route.fullPath } })
    },
    goRegister() {
      this.$router.push({ path: '/register', query: { redirect: this.$route.fullPath } })
    },

    /**
     * 静默刷新问卷公开信息（只为拿到最新的 responsesFull）；失败不影响任何流程。
     */
    async refreshShareInfo() {
      try {
        const data = await api.survey.byShareCode(this.shareCode, { silent: true })
        if (data && data.id) this.survey = data
      } catch {
        // 忽略：拿不到就用旧数据，页面照常
      }
    },

    resetForm() {
      if (this.redirectTimer) {
        clearInterval(this.redirectTimer)
        this.redirectTimer = null
      }
      this.submitted = false
      // 清掉上次的成绩卡：否则"再填一份"交卷前会先闪一下上一次的分数
      this.submitResult = null
      this.answers = {}
      this.initAnswers()
      this.currentOrder = 1
      this.history = []
      this.allDone = false
      // 限时问卷"再填一份"要重新起算计时，而不是接着上一份的剩余时间
      this.clearTimeLimit()
      this.timeLeft = null
      this.timeUpNotified = false
      this.startedAt = Date.now()
      this.$nextTick(() => this.startTimeLimit())
    }
  }
}
</script>

<style scoped>
/* 接龙报名名单（作者开启「公开报名名单」才有） */
.participants-head {
  font-weight: 600;
  margin-bottom: 8px;
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.participants-tip {
  font-size: 12px;
  color: var(--ink-3, #94a3b8);
  font-weight: 400;
}
.participant-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 0;
  font-size: 13px;
  border-bottom: 1px dashed var(--line-1, #f1f5f9);
}
.participant-row:last-child {
  border-bottom: none;
}
.participant-seq {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--sky-50, #eff6ff);
  color: var(--sky-600, #0284c7);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  flex-shrink: 0;
}
.participant-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.participant-time {
  color: var(--ink-3, #94a3b8);
  font-size: 12px;
  flex-shrink: 0;
}
/* 仅限登录：登录/注册引导按钮 */
.login-gate-actions {
  margin-top: 18px;
  display: flex;
  gap: 12px;
}
/* 考试判分：交卷后的成绩卡（只展示分数与是否及格，不展示正确答案） */
.score-card {
  margin-bottom: 4px;
  text-align: center;
  border-radius: var(--r-lg, 16px);
}
.score-head {
  font-size: 13px;
  color: var(--ink-3, #64748b);
  letter-spacing: 1px;
}
.score-main {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 8px;
  margin: 6px 0 10px;
}
.score-value {
  font-size: 52px;
  font-weight: 700;
  line-height: 1;
  color: var(--sky-600, #0284c7);
}
.score-total {
  font-size: 16px;
  color: var(--ink-3, #64748b);
}
.score-meta {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}
.score-pass {
  font-size: 13px;
  color: var(--ink-3, #64748b);
}
/* 限时答卷：剩余不足 1 分钟时标红 */
.fill-time-urgent {
  color: #dc2626;
  font-weight: 600;
}
/* 成绩卡的逐题对错（作者开启"提交后显示答案"才有） */
.score-review {
  margin-top: 14px;
  border-top: 1px dashed var(--line-1, #e5e7eb);
  padding-top: 10px;
  text-align: left;
}
.score-review-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
  font-size: 13px;
}
.score-review-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-2, #475569);
}
.score-review-answer {
  color: var(--ink-3, #64748b);
  flex-shrink: 0;
}
.fill-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  font-size: 12px;
  color: var(--ink-3);
}
.fill-meta-dot {
  color: #c0c4cc;
}

.password-title {
  margin-bottom: 6px;
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
}
.password-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: #909399;
}
.password-row {
  display: flex;
  gap: 12px;
}
.password-row .el-input {
  flex: 1;
}
.password-error {
  margin: 10px 0 0;
  font-size: 13px;
  color: #f56c6c;
}

.fill-rating {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fill-star {
  font-size: 34px;
  line-height: 1;
  color: #d8e3f3;
  cursor: pointer;
  transition:
    color 0.16s var(--ease),
    transform 0.16s var(--ease);
}
.fill-star:hover {
  transform: scale(1.12);
}
.fill-star.on {
  color: #f59e0b;
}
.fill-rating-text {
  margin-left: 10px;
  font-size: 14px;
  color: #909399;
}

.submit-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12px;
}
.redirect-hint {
  margin: 14px 0 0;
  font-size: 12px;
  color: #909399;
}

/* el-radio/checkbox-group 默认 inline-flex 会收缩到文字宽；撑成块级满宽，选项框才占整行 */
.fill-options {
  display: block;
  width: 100%;
}
.fill-options :deep(.el-radio),
.fill-options :deep(.el-checkbox) {
  margin-right: 0;
}

/* 选项配置不完整时的提示条（历史脏数据兜底） */
.option-broken {
  margin: 4px 0 2px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.6;
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 8px;
}

.fill-matrix-wrap {
  overflow-x: auto;
}
.fill-matrix {
  min-width: 100%;
  border-collapse: collapse;
}
.fill-matrix th,
.fill-matrix td {
  padding: 10px 12px;
  border: 1px solid var(--stroke);
  font-size: 14px;
  text-align: center;
}
.fill-matrix thead th {
  background: #f5f9ff;
  font-weight: 600;
  color: var(--ink-2);
}
.fill-matrix tbody th {
  text-align: left;
  font-weight: 500;
  white-space: nowrap;
  color: var(--ink-2);
}
.fill-matrix input {
  width: 18px;
  height: 18px;
  accent-color: var(--sky-500);
  cursor: pointer;
}
.fill-ranking {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.fill-rank-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border: 1px solid var(--stroke);
  border-radius: 10px;
}
.fill-rank-no {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--sky-50);
  color: var(--sky-600);
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}
.fill-rank-text {
  flex: 1;
  font-size: 14px;
  color: var(--ink-2);
}
.fill-rank-tip,
.fill-nps-tip {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--ink-3);
}
.fill-nps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.fill-nps-btn {
  width: 44px;
  height: 44px;
  border: 1px solid var(--stroke);
  border-radius: 10px;
  background: #fff;
  color: var(--ink-2);
  font-size: 15px;
  cursor: pointer;
  transition: all 0.16s ease;
}
.fill-nps-btn:hover {
  border-color: var(--sky-400);
  color: var(--sky-600);
}
.fill-nps-btn.on {
  background: var(--sky-500);
  border-color: var(--sky-500);
  color: #fff;
  font-weight: 600;
}
html[data-theme="dark"] .fill-matrix thead th {
  background: #16263f;
}
html[data-theme="dark"] .fill-nps-btn {
  background: #0e1b2e;
  border-color: #1d2c44;
  color: #cbd5e1;
}

/* 固定右上角、不占文档流：不挡标题/描述，也不会把「填写进度」挤下去 */
.report-fab {
  position: fixed;
  top: 18px;
  right: 18px;
  z-index: 2000;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 14px;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  color: #6b7280;
  font-size: 13px;
  cursor: pointer;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(6px);
  transition: color 0.18s ease, background 0.18s ease, border-color 0.18s ease, transform 0.18s ease;
}
.report-fab:hover {
  color: #dc2626;
  background: #fef2f2;
  border-color: #fecaca;
  transform: translateY(-1px);
}
.report-fab .el-icon {
  font-size: 15px;
}
</style>
