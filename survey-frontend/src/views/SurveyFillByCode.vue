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

    <div v-else-if="submitted" style="max-width: 600px; margin: 80px auto;">
      <el-result icon="success" title="提交成功！" sub-title="感谢您的参与">
        <template #extra>
          <el-button type="primary" @click="resetForm">再填一份</el-button>
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
      <el-card class="mb-16" v-if="!allDone && currentQuestion">
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
      countdownTimer: null,
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
    // ★ 从"打开页面"开始计时（而不是等题目加载完）：用户的阅读时间也该算进答题耗时
    this.startedAt = Date.now()
    this.loadSurvey()
  },
  beforeUnmount() {
    if (this.countdownTimer) clearInterval(this.countdownTimer)
  },
  methods: {
    async loadSurvey() {
      try {
        // ★ api.survey.byShareCode()（路径只维护在 src/api/survey.js 一处）；
        //   传 { silent: true }：这是匿名答题页，未登录是常态 ——
        //   万一真返回 401，也不该触发"登录已过期"跳转、把答题流程打断。
        const data = await api.survey.byShareCode(this.shareCode, { silent: true })
        if (!data || !data.id || data.status !== 1) {
          this.survey = null
          this.loading = false
          return
        }
        this.survey = data
        // ★ 记一次访问量：api.survey.view() 在 api 层已内置 { silent: true }
        //   （失败不弹提示、不跳登录）；
        //   ★ 绝不【加 await】：访问量统计不该拖慢问卷首屏渲染。
        api.survey.view(this.shareCode).catch(() => {})
        // ★ 加载题目用 api.question.list()（路径统一在 src/api/question.js）。
        //   不登录也能读已发布问卷的题目 —— 后端 JwtAuthFilter 白名单已放行该路径，不会 401。
        const list = await api.question.list(data.id)
        this.questions = list.map((q, idx) => ({ ...q, _order: idx + 1 }))
        this.startCountdown()
      } catch {
        // ★ 加载失败（问卷不存在/已下线/网络异常）→ 走"问卷不存在"空态
        //   （this.survey = null）；错误提示由 src/api/http.js 统一负责，本页不再自己弹提示。
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
      // 单选题
      if (q.type === 1 && ans) {
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
        if (item.type !== 3) continue                    // 选择类保留（可回头再选）
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
      if (q.required === 1) {
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
        await api.answer.submitByShareCode(this.shareCode, details, durationMs)
        this.submitted = true
      } catch {
        // 失败红字已由 http.js 弹出；这里只做流程控制：
        // 保持当前页面，访客可以改完再点一次"提交问卷"。
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