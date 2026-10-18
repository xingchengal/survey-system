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

    <!-- ★ 不可填写（收满 / 已截止 / 已结束）：**进页面就告知**。
         以前要等填完、点提交才在服务端吃 400，白填一遍。 -->
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

    <div v-else-if="submitted" style="max-width: 600px; margin: 80px auto;">
      <el-result icon="success" title="提交成功！" :sub-title="thankText">
        <template #extra>
          <div class="submit-actions">
            <el-button v-if="survey.publicResult === 1" type="primary" @click="goPublicResult">
              查看统计结果
            </el-button>
            <el-button v-if="redirectUrl" type="success" @click="goRedirect">立即前往</el-button>
            <!-- ★ 已收满时不再给"再填一份"：点进去也只能白填一遍（提交必被拒） -->
            <el-button v-if="survey.responsesFull !== true" @click="resetForm">再填一份</el-button>
          </div>
          <p v-if="redirectUrl" class="redirect-hint">{{ redirectSeconds }} 秒后自动前往…</p>
        </template>
      </el-result>
    </div>

    <div v-else style="max-width: 700px; margin: 0 auto;">
      <!-- 问卷头部 -->
      <el-card class="mb-20">
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

        <!-- ★ 公开结果入口**填写前也可见**：以前只有"提交成功页"才有这个按钮，
             填完关掉页面就再也找不到路（再打开链接还会被"已收满"挡住）。 -->
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

      <!-- 答题密码门：密码正确前不加载题目（题目由后端按请求头校验，前端只是体验层） -->
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

        <!-- ★ 选项题但没有任何"有内容"的选项（历史脏数据 / 作者没填选项就发布了）：
             绝不能把这题渲染成"看不见的选项" —— 多个空 value 会让点一个就整组选中，
             下拉则是空面板，必填时访客会被永远卡在这一题。这里给出明确提示，并允许跳过。 -->
        <div
          v-if="optionsBroken"
          class="option-broken"
        >
          ⚠️ 该题的选项未配置完整（内容为空），暂时无法作答。请联系问卷发布者补充选项后再填。
        </div>

        <!-- 单选题 -->
        <el-radio-group
          v-if="currentQuestion.type === 1 && usableOptions.length"
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
          v-else-if="currentQuestion.type === 2 && usableOptions.length"
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
          v-else-if="currentQuestion.type === 5 && usableOptions.length"
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
        <!-- ★ maxlength=1000 必须与后端 TextLimits.ANSWER_CONTENT(1000) / 列宽 varchar(1000) 对齐，
             否则访客写 1001 字会在提交时吃 400「单题回答不能超过 1000 个字符」。 -->
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
import { CircleCheckFilled } from '@element-plus/icons-vue'
// ★ 统一接口层：本页是**匿名答题页**，所有请求走 src/api/。
//   ⚠️ silent 说明（本页最关键的一点）：本页任何接口失败都**不能触发"登录过期跳转"**，
//     否则匿名答题流程会被打断。实际情况：
//       · api.survey.view()（记访问量）在接口层内置 { silent: true } —— 失败不打扰访客；
//       · api.survey.byShareCode() 由本页传 { silent: true } —— 链接失效时页面本身
//         会显示"问卷不存在或已下线"的空态，不需要再弹红字；
//       · api.question.list() 与 api.answer.submitByShareCode() **不传 silent** ——
//         它们必须让访客看到失败原因（题目加载失败 / 问卷已截止 / 提交失败）。
//     这些路径全部在 JwtAuthFilter 白名单里（/api/survey/share/、/api/survey/view/、
//     /api/question/list/、/api/answer/submit-share/），根本不会返回 401，
//     所以去掉 silent 也不会触发跳转。
import api from '../api'
import { RATING_LABELS, hasOptions as hasOptionType } from '../utils/questionTypes'

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
      /**
       * 为什么现在不能填：'' = 可以填；'full' 已收满 / 'expired' 已截止 / 'ended' 已结束。
       * ★ 三者都要在**进页面时**就判出来 —— 以前只有"提交"那一刻才知道，填写人会白填一遍。
       */
      closedReason: '',
      allDone: false,
      countdown: '',
      countdownTimer: null,
      // ===== 答题密码 =====
      /** 已验证通过的密码（提交时放请求头） */
      password: '',
      /** 密码输入框 */
      passwordInput: '',
      passwordVerified: false,
      verifying: false,
      passwordError: '',
      // ===== 提交后跳转 =====
      redirectSeconds: 3,
      redirectTimer: null,
      /** 评分题的 5 档文案（与后端 1~5 分对应） */
      ratingLabels: RATING_LABELS,
      /**
       * 打开答题页的时间戳（毫秒）。
       * ★ 唯一用途：提交时上报"答题耗时"，给后端的"答题过快"质控判定。
       *   它是**客户端值、可伪造** —— 后端只当提示，不当安全边界。
       */
      startedAt: 0
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
    /** 当前题是不是"有选项"的题型（单选 / 多选 / 下拉） */
    currentHasOptions() {
      return hasOptionType(this.currentQuestion && this.currentQuestion.type)
    },
    /**
     * 当前题**可用**的选项：过滤掉内容为空的脏选项。
     * ★ 空内容必须滤掉而不是原样渲染 —— 空字符串当 value 时整组选项的值都相同。
     */
    usableOptions() {
      const opts = (this.currentQuestion && this.currentQuestion.options) || []
      return opts.filter(o => typeof o.content === 'string' && o.content.trim() !== '')
    },
    /** 选项题却一个可用选项都没有：页面提示"配置不完整"，且不拦"下一题"（否则必填会卡死） */
    optionsBroken() {
      return this.currentHasOptions && this.usableOptions.length === 0
    },
    /**
     * 进度分子 = "当前答题路径上已经走过的题数"：
     *   · 已经推进到后面的题 → 前面那些题（含被跳过的非必填题）都算完成；
     *   · 当前题已作答 → 算完成；
     *   · 全部答完 → 等于 totalSteps（100%）。
     * ★ 原来用的是"已答题目数 / 总步数"：只要用户跳过一道非必填题，进度就再也不动
     *   （报障"进度条一直没变"）；空选项的脏数据更是让任何答案都算不进去。
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
    }
  },
  mounted() {
    this.shareCode = this.$route.params.shareCode
    // ★ 从"打开页面"开始计时（而不是等题目加载完）：用户的阅读时间也该算进答题耗时
    this.startedAt = Date.now()
    this.loadSurvey()
  },
  beforeUnmount() {
    if (this.countdownTimer) clearInterval(this.countdownTimer)
    if (this.redirectTimer) clearInterval(this.redirectTimer)
  },
  methods: {
    async loadSurvey() {
      try {
        // ★ api.survey.byShareCode()（路径只维护在 src/api/survey.js 一处）；
        //   传 { silent: true }：这是匿名答题页，未登录是常态 ——
        //   万一真返回 401，也不该触发"登录已过期"跳转、把答题流程打断。
        const data = await api.survey.byShareCode(this.shareCode, { silent: true })
        if (!data || !data.id) {
          this.survey = null
          this.loading = false
          return
        }
        this.survey = data
        // ★ 记一次访问量：api.survey.view() 在 api 层已内置 { silent: true }
        //   （失败不弹提示、不跳登录）；
        //   ★ 绝不【加 await】：访问量统计不该拖慢问卷首屏渲染。
        api.survey.view(this.shareCode).catch(() => {})
        // ★ 草稿 / 回收站等（既不是已发布也不是已结束）→ 对外一律当作"不存在或已下线"
        if (data.status !== 1 && data.status !== 2) {
          this.survey = null
          this.loading = false
          return
        }
        // ★★ 三种"现在填不了"统一处理（都在**进页面**时判，不让访客白填一遍）：
        //   ① 已结束（定时任务把 status 置 2，或作者手动结束）
        //   ② 已收满（后端 /share 算好的 responsesFull，口径与提交守卫一致）
        //   ③ 已过截止时间（status 可能还没来得及翻，但提交已经会被拒）
        //   ★ 放在密码门之前：都已经不能交了，没必要先让人输密码。
        if (data.status === 2) {
          this.closedReason = 'ended'
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
        // ★ 有答题密码时先不加载题目：后端会按请求头校验，前端只是把密码门提前。
        if (data.hasPassword) {
          this.loading = false
          return
        }
        await this.loadQuestions()
      } catch {
        // ★ 加载失败（问卷不存在/已下线/网络异常）→ 走"问卷不存在"空态
        //   （this.survey = null）；错误提示由 src/api/http.js 统一负责，本页不再自己弹提示。
        this.survey = null
      } finally {
        this.loading = false
      }
    },

    /**
     * 加载题目。
     * ★ 有密码时带 X-Survey-Password 请求头（密码只放请求头，不进 query/URL）。
     */
    async loadQuestions() {
      const list = await api.question.list(this.survey.id, this.requestOpts())
      this.questions = list.map((q, idx) => ({ ...q, _order: idx + 1 }))
      this.startCountdown()
    },

    /** 需要密码时返回请求选项，否则 undefined（不影响原有调用） */
    requestOpts() {
      return this.password
        ? { headers: { 'X-Survey-Password': this.password } }
        : undefined
    },

    /**
     * 校验答题密码：直接用"带密码请求题目"来验证 —— 密码对了后端才会返回题目，
     * 不需要额外开一个只判密码的端点（少一个端点就少一处要维护的口径）。
     * ★ 这里传 silent：密码错误要在密码框下面内联提示，再弹一条红字会重复。
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
        this.startCountdown()
      } catch (e) {
        this.passwordError = (e && e.message) || '密码不正确，请重试'
      } finally {
        this.verifying = false
      }
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

    /**
     * 根据当前所有答案，模拟走一遍完整路径，返回需要答多少道题
     */
    computeTotalSteps() {
      if (this.questions.length === 0) return 0

      // ★ 复用 previewPath：这里、answerPathQuestionIds()、pruneStaleAnswers() 必须共用
      //   同一套跳转语义，否则"几步 / 哪些题 / 清哪些题"三处会各改各的、悄悄走偏。
      return this.previewPath(1, this.answers[this.questions[0] && this.questions[0].id]).path.length
    },

    /**
     * 计算某题答完后应该跳到**哪一道题**。
     *
     * ★★ 返回值必须是 **question.id**，不能用"1 基题目序号"：序号是"按当前数组位置"算出来的，
     *   而问卷可以被编辑（删题、调序）——否则编辑过之后，历史跳转规则里存的序号就会指向
     *   错的题（静默错位，不报错只跳错）。
     *
     * @returns {number} 目标题的 **id**；-1 = 结束问卷；正数但没有对应题（脏数据）时也按"结束"处理
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
     * 预算"在 order 这道题选了 answer 之后"的答题路径，返回两部分：
     *   path —— 完整路径（**仍用 1 基序号**表示，因为进度条、history、清理逻辑都按它工作）；
     *   next —— order 之后要去的题号；-1 表示到终点；> questions.length 表示结束。
     *
     * ★ 存在的意义：让"结算后续题"不再各写一套循环。下面的 pruneStaleAnswers() 与
     *   computeTotalSteps() / answerPathQuestionIds() 都基于它，**四处走法永远一致**。
     * ★ 防环：while 里用 visited 去重（历史脏数据里跳转目标可能指回自己）。
     * ★★ 跳转规则存的是**题 id**，而本方法内部用**序号**推进（为了与 history / 进度条 /
     *   清理逻辑共用一套表示）。所以这里做一次"id → 序号"的换算，
     *   并把"目标必须大于当前题"这层防护放在**序号域**上 —— 它同时挡住了
     *   "脏数据指回自己/往前跳"造成的死循环。
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
     * 用户**回退改选**之后，把"沿着新选择不再会走到"的那些题的答案就地清掉。
     *
     * 为什么需要它（与 submitAnswer 的路径过滤互补，不重复）：
     *   · submitAnswer 只提交路径上的题 —— 那是"最后一道防线"，保证脏答案不落库；
     *   · 但**页面状态**里旧答案还在：用户再往前点一次、或删掉某个选项时，
     *     会看到"被跳过的那道题竟然还留着之前的答案"，甚至误以为它仍然生效。
     *   两者一起，才让"答题路径"这个概念在**界面状态**与**提交数据**上都成立。
     *
     * 清理规则（保守，只清"确实越界的"）：
     *   · 只清 order 之后的题（回退改选不会影响之前的答案，用户往回看仍然看得到）；
     *   · 跳过"当前这一题"与整条新路径上的题；
     *   · 选择类题目（单选/多选，type 1/2）**保留** —— 用户回头再选时答案还在，不算越界；
     *   · 只清 **填空（type 3）** 的陈旧答案 —— 它没有选项，界面上看不出"这题已作废"，
     *     是最容易误提交、也最容易让用户困惑的一类。
     *
     * ★ 有意**不弹任何提示**：答题人只需要知道"能不能提交成功"，
     *   "你的路径变了、我顺手清了 N 道题"属于实现细节，弹出来只会让人紧张。
     *   被清掉的题本来就在新路径之外、不会再被问到，用户不会感到少了什么。
     *
     * @returns {number} 实际清掉的题目数（仅用于测试/排查，不面向用户展示）
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
     * 沿"从第 1 题开始 + 每题按已选项跳转"走一遍，返回**本次答题路径**上的题目 id 集合。
     * ★ 走法与 computeTotalSteps() 一致（同一套 currentOrder / calcNextQuestionId 语义），
     *   所以"进度条算了几步"与"提交几题"永远一致。
     * ★ 用"当前答案"计算：用户回退改选之后，路径会随之变化，被跳过的题自然被排除。
     */
    answerPathQuestionIds() {
      // ★ 复用 previewPath（与进度条、清理逻辑共用同一套走法）
      const { path } = this.previewPath(1, this.answers[this.questions[0] && this.questions[0].id])
      const byOrder = new Map(this.questions.map(item => [item._order, item.id]))
      return new Set(path.map(order => byOrder.get(order)).filter(id => id !== undefined))
    },

    handleNext() {
      const q = this.currentQuestion
      if (!q) return

      const ans = this.answers[q.id]
      // ★ 选项题但一个可用选项都没有（历史脏数据）→ 这道题物理上没法答，
      //   再做必填拦截只会把访客永久停在这一页（正是"下拉无选项、必填卡死"的报障）。
      //   这种题按"跳过"处理并给出提示，让访客能继续往下填。
      if (this.optionsBroken) {
        ElMessage.warning('该题选项未配置，已跳过这道题')
      } else if (q.required === 1) {
        if (ans === undefined || ans === '' || (Array.isArray(ans) && ans.length === 0)) {
          ElMessage.warning('此题必填')
          return
        }
      }

      // ★★ 跳转目标是**题 id**，而页面推进用**序号**（currentOrder / history），
      //   所以这里做一次"id → 该题序号"的换算：
      //     · 目标 id 找不到对应题（历史脏数据 / 目标题被删）→ 与"结束"同义，不让用户卡在空页；
      //     · 目标在当前题之前 → 按结束处理（与 previewPath 的防环口径一致，避免死循环）。
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

      // ★ 改选会让后续路径变化，先把"不会再走到"的填空答案清掉（选择类保留）
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

      // ★ 只提交**本次答题路径上**的答案，按"从第 1 题沿跳转走一遍"得到的路径集合过滤
      //   （与页面上显示的题序一致）—— 否则会出两种错：
      //     ① 条件跳转把某题跳过（例如选了"不吸烟"），但那题在**回退改选之前**曾被答过，
      //        它的答案会**一起被提交**，污染统计（后端按 content 分组计数，直接多出票数）；
      //     ② 回退到前面改选、走了另一条分支时，旧分支的答案同样被带上。
      const onPath = this.answerPathQuestionIds()
      const details = []
      for (const q of this.questions) {
        if (!onPath.has(q.id)) continue
        const ans = this.answers[q.id]
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
        // ★ api.answer.submitByShareCode()，**不传 silent**：
        //     访客点"提交问卷"失败时（问卷已截止 / 已下线 / 网络异常）必须看到原因，
        //     否则点了没反应等于功能坏了。该接口在白名单里，不会返回 401，
        //     所以不传 silent 也不会触发"登录过期跳转"。
        // ★ 后端契约：成功 200、失败 400 + message（由 http.js 弹红字）。
        // ★ 上报答题耗时（毫秒）：后端的"答题过快"质控规则靠它。
        //   没打开计时（startedAt=0）就不传 —— 后端会当作"未上报"、不判这条规则。
        const durationMs = this.startedAt ? Math.max(0, Date.now() - this.startedAt) : null
        await api.answer.submitByShareCode(this.shareCode, details, durationMs, this.requestOpts())
        this.submitted = true
        this.startRedirectTimer()
        // ★ 提交成功后静默刷新一次分享信息：限量问卷往往"你交完就满了"，
        //   刷新后"再填一份"会自动消失，而不是点进去再吃一次 400。
        this.refreshShareInfo()
      } catch {
        // 失败红字已由 http.js 弹出；这里只做流程控制：
        // 保持当前页面，访客可以改完再点一次"提交问卷"。
      }
    },

    /**
     * 静默刷新问卷的公开信息（只为拿到最新的 responsesFull）。
     * ★ 失败不影响任何流程：这是"锦上添花"的一次刷新，错了也不该打扰访客。
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
      this.answers = {}
      this.currentOrder = 1
      this.history = []
      this.allDone = false
    }
  }
}
</script>

<style scoped>
/* ==================== 答题密码门 ==================== */
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

/* ==================== 评分题 ==================== */
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

/* ==================== 提交成功页 ==================== */
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

/* ==================== 选项组 ==================== */
/* ★ el-radio-group / el-checkbox-group 默认是 inline-flex，宽度会收缩到内容宽度；
   而每个选项写的是 width:100%（相对父级），于是选项框只有"文字那么宽"。
   这里把组撑成块级满宽，选项框才是一条整行。 */
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
</style>