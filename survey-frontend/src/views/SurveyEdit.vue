<template>
  <div style="padding: 20px; max-width: 900px; margin: 0 auto;">
    <!-- ★ 回收站里的问卷禁止保存/发布：点下去**必然 400**（后端 OwnershipGuard.requireNotInRecycleBin /
          SurveyController.update / QuestionController.save 三处都会拦），所以这里明确告知并给恢复入口。 -->
    <el-alert
      v-if="surveyInfo.deleted === 1"
      class="mb-16"
      type="warning"
      show-icon
      :closable="false"
      title="这份问卷已在回收站中"
    >
      <template #default>
        <div class="mt-8">
          回收站里的问卷<strong>不能</strong>保存或发布（后端会拒绝，返回 400）。请先到
          <el-link type="primary" @click="goRecycle">回收站</el-link>
          把它恢复，再回来编辑。
        </div>
      </template>
    </el-alert>

    <!-- 顶部操作栏 -->
    <div class="page-header">
      <h2 class="m-0">📝 问卷编辑器 (ID: {{ surveyId }})</h2>
      <div>
        <el-button @click="goBack">返回</el-button>
        <!-- ★ 回收站里禁止保存/发布（点了必 400），按钮直接禁用并把原因写在标题里。
             ★ `saving` 门控：保存首次是"预存 + 正式提交"两次往返，期间禁用以免连点触发第二次全量覆盖。 -->
        <el-button type="primary" :disabled="inRecycleBin || saving" :loading="saving" @click="saveQuestions">保存</el-button>
        <!-- ★ status 为 0 或 2 都要给发布按钮：后端支持"延长（或清空）结束时间后重新发布"。
              已结束但结束时间仍在过去时，按钮变成"重新发布"并禁用（否则用户点了只吃 400），
              并在下面给出"请先把结束时间改到未来"的提示。 -->
        <el-button
          v-if="surveyInfo.status === 0 || surveyInfo.status === 2"
          type="success"
          :disabled="inRecycleBin || saving || (surveyInfo.status === 2 && !endExtended)"
          @click="publishSurvey"
        >{{ surveyInfo.status === 2 ? '重新发布' : '立即发布' }}</el-button>
        <el-tag class="ml-10" v-else-if="surveyInfo.status === 1" type="success">已发布（可继续修改）</el-tag>
        <!-- 历史版本：回收站里禁用（回滚走的是"全量覆盖式保存"，在回收站问卷上执行会让历史答卷的 question_id 悬空） -->
        <el-button class="ml-10" :disabled="inRecycleBin" @click="openVersions">历史版本</el-button>
      </div>
    </div>

    <!-- ★ 已结束但结束时间还没改到未来：给出可执行的提示，而不是让用户点发布去撞 400 -->
    <el-alert
      v-if="surveyInfo.status === 2 && !endExtended"
      class="mb-16"
      type="info"
      show-icon
      :closable="false"
      title="该问卷已结束，要重新开放请先把「截止时间」改到未来（或清空）再点「重新发布」"
    />

    <!-- 问卷基础信息 -->
    <el-card class="mb-20">
      <el-form label-width="100px">
        <el-form-item label="问卷标题">
          <!-- ★ maxlength 必须与后端 TextLimits.SURVEY_TITLE(200) / 数据库 varchar(200) 对齐，
               否则用户写超长只有提交后吃 400。 -->
          <el-input v-model="surveyInfo.title" placeholder="请输入问卷标题" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="问卷描述">
          <el-input v-model="surveyInfo.description" type="textarea" :rows="2" placeholder="请输入问卷描述" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="定时发布">
          <el-date-picker class="w-full" v-model="surveyInfo.startTime" type="datetime" placeholder="不设置则手动发布" value-format="YYYY-MM-DD HH:mm:ss" />
        </el-form-item>
        <el-form-item label="截止时间">
          <el-date-picker class="w-full" v-model="surveyInfo.endTime" type="datetime" placeholder="不设置则永不过期" value-format="YYYY-MM-DD HH:mm:ss" />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 题目列表 -->
    <div v-if="questions.length === 0" style="text-align: center; color: #999; padding: 40px 0; background: #fff; border-radius: 8px;">
      还没有题目，点击下方按钮添加第一道题
    </div>

    <el-card class="mb-16" v-for="(q, qIndex) in questions" :key="qIndex">
      <div style="display: flex; justify-content: space-between; align-items: flex-start;">
        <div style="flex: 1;">
          <el-tag :type="q.type === 1 ? 'primary' : q.type === 2 ? 'success' : 'warning'" style="margin-bottom: 8px;">
            {{ q.type === 1 ? '单选题' : q.type === 2 ? '多选题' : '单行输入' }}
          </el-tag>
          <el-checkbox class="ml-10" v-model="q.required" :true-value="1" :false-value="0">必填</el-checkbox>

          <el-input v-model="q.title" placeholder="请输入题目内容" maxlength="500" show-word-limit style="margin-top: 10px;" />

          <!-- 选项区域 -->
          <div v-if="q.type === 1 || q.type === 2" style="margin-top: 12px; padding-left: 20px;">
            <div v-for="(opt, oIndex) in q.options" :key="oIndex" style="display: flex; align-items: center; margin-bottom: 8px;">
              <el-icon style="margin-right: 8px; color: #999;">
                <component :is="q.type === 1 ? 'CircleCheck' : 'Select'" />
              </el-icon>
              <el-input v-model="opt.content" placeholder="请输入选项内容" maxlength="500" style="width: 220px; margin-right: 8px;" />

              <!-- 跳转设置：单选和多选都支持 -->
              <!-- ★ `:value` 必须是 question.id：保存时后端会按数组重排 sort_order、并删掉本次没传的题，
                   若这里存"第几题"的序号，删题/调序之后已存的规则就会**静默指向错的题**。
                   ★ 新建的题在保存前没有数据库 id，所以由前端先发一个**负数的临时 id**（见 data.tempQuestionId），
                     保存成功后替换成真实 id —— 否则新建问卷的每一道题都会因为 `!target.id` 而被禁用，根本设不了跳转。
                   ★ 只允许往后跳（ti <= qIndex 的禁用）：反向跳会破坏答题页的"路径推进"模型
                     （那里靠"目标必须在当前题之后"来防环）。 -->
              <el-select
                v-model="opt.nextQuestionId"
                placeholder="下一题"
                clearable
                size="small"
                style="width: 130px; margin-right: 8px;"
              >
                <el-option
                  v-for="(target, ti) in questions"
                  :key="ti"
                  :label="`跳到第 ${ti + 1} 题`"
                  :value="target.id"
                  :disabled="ti <= qIndex"
                />
                <el-option label="结束问卷" :value="-1" />
              </el-select>

              <el-button type="danger" link size="small" @click="removeOption(qIndex, oIndex)">删除</el-button>
            </div>

            <!-- 多选跳转规则提示 -->
            <div v-if="q.type === 2" style="font-size: 12px; color: #909399; margin-top: 4px; padding-left: 24px;">
              💡 多选跳转规则：勾选的多个选项中，按选项顺序取第一个命中的跳转
            </div>

            <el-button type="primary" link size="small" @click="addOption(qIndex)">+ 添加选项</el-button>
          </div>
        </div>

        <el-button class="ml-10" type="danger" link @click="removeQuestion(qIndex)">删除题目</el-button>
      </div>
    </el-card>

    <!-- 底部添加题目 -->
    <div style="text-align: center; margin-top: 20px; padding: 20px; background: #fff; border-radius: 8px;">
      <span style="margin-right: 10px; color: #666;">添加题目：</span>
      <el-button type="primary" plain @click="addQuestion(1)">单选题</el-button>
      <el-button type="success" plain @click="addQuestion(2)">多选题</el-button>
      <el-button type="warning" plain @click="addQuestion(3)">单行输入</el-button>
    </div>
    <!-- ===== 历史版本对话框 ===== -->
    <el-dialog v-model="versionVisible" title="历史版本" width="820px">
      <div style="color:#666; font-size:13px; margin-bottom:12px;">
        每次「保存」只要题目结构有变化就会留一个版本（每份问卷最多保留 50 个）。
        ★ 回滚会生成一个新版本，历史不会丢 —— 回滚错了还能再回滚回去。
      </div>

      <el-table class="w-full" :data="versions" stripe border max-height="300"
                empty-text="还没有版本（保存一次题目后才会生成）">
        <el-table-column label="版本" width="80" align="center">
          <template #default="{ row }">v{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="createdTime" label="生成时间" width="170" />
        <el-table-column prop="questionCount" label="题数" width="70" align="center" />
        <el-table-column prop="changeSummary" label="变更" min-width="240" show-overflow-tooltip />
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small"
                       :loading="rollingBack === row.id"
                       @click="rollbackVersion(row)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 版本对比 -->
      <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin-top:16px;">
        <span style="color:#666;">版本对比：</span>
        <el-select v-model="diffFrom" placeholder="旧版本" size="small" style="width:120px;">
          <el-option v-for="v in versions" :key="v.id" :label="'v' + v.versionNo" :value="v.id" />
        </el-select>
        <span>→</span>
        <el-select v-model="diffTo" placeholder="新版本" size="small" style="width:120px;">
          <el-option v-for="v in versions" :key="v.id" :label="'v' + v.versionNo" :value="v.id" />
        </el-select>
        <el-button size="small" type="primary" plain
                   :disabled="!diffFrom || !diffTo || diffFrom === diffTo"
                   @click="doDiff">对比</el-button>
      </div>

      <div v-if="versionDiff" style="margin-top:12px;">
        <el-alert v-if="versionDiff.titleChanged" type="info" :closable="false" style="margin-bottom:8px;"
                  :title="`标题：${versionDiff.titleFrom || '（空）'} → ${versionDiff.titleTo || '（空）'}`" />
        <div v-if="!versionDiff.added.length && !versionDiff.removed.length && !versionDiff.modified.length"
             style="color:#67c23a;">两个版本的题目结构完全一致</div>
        <div v-else>
          <div v-if="versionDiff.added.length" style="margin-bottom:8px;">
            <el-tag type="success" size="small">新增 {{ versionDiff.added.length }}</el-tag>
            <span v-for="x in versionDiff.added" :key="'a' + x.questionId" style="margin-left:8px;">{{ x.title }}</span>
          </div>
          <div v-if="versionDiff.removed.length" style="margin-bottom:8px;">
            <el-tag type="danger" size="small">删除 {{ versionDiff.removed.length }}</el-tag>
            <span v-for="x in versionDiff.removed" :key="'r' + x.questionId" style="margin-left:8px;">{{ x.title }}</span>
          </div>
          <div v-if="versionDiff.modified.length">
            <el-tag type="warning" size="small">修改 {{ versionDiff.modified.length }}</el-tag>
            <div v-for="x in versionDiff.modified" :key="'m' + x.questionId"
                 style="margin-left:8px; margin-top:4px;">
              {{ x.title }}
              <span style="color:#999;">（{{ x.changes.join('；') }}）</span>
            </div>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
// ★ 统一接口层：本页请求全部走 src/api/ ——
//   接口路径、Authorization、成败判断、错误提示都收敛在 src/api/http.js，
//   页面只关心"调哪个接口、拿到什么数据"。
import api from '../api'
// ★ 判断角色/读用户统一走 utils/auth（不要裸 JSON.parse localStorage）
import { getUser } from '../utils/auth'

export default {
  data() {
    return {
      surveyId: null,
      surveyInfo: {
        title: '',
        description: '',
        status: 0,
        shareCode: null,
        startTime: null,
        endTime: null,
        // ★ 后端 findById **不过滤 deleted**，所以详情里带得回来；
        //   用来判断"这份问卷是不是在回收站里"（回收站里禁止编辑/保存/发布）
        deleted: 0
      },
      questions: [],
      /**
       * 是否正在保存（门控）。
       * 保存首次是"预存 + 正式提交"两次往返，期间按钮若还可点，用户很容易连点出第二次保存 ——
       * 两次全量覆盖并发执行，轻则重复插入题目、重则把对方的更新覆盖掉。
       * 所以点击后立刻置 true，**直到两次请求全部结束**（含失败）才放开。
       */
      saving: false,
      /**
       * 草稿阶段的**临时题目 id**（从 -1000 递减）。
       * 新建的题在保存前没有数据库 id，而"跳转目标"这个下拉框必须有一个值可用，
       * 否则新建的问卷完全设不了跳转（AI 创建与模板创建之所以正常，是因为它们的题目自带宽 id）。
       * 前端自己发号，保存成功后会用真实 id 覆盖掉（见 saveQuestions）。
       *
       * ★★ **不能从 -1 开始**：`-1` 在跳转语义里是"结束问卷"这个保留值
       *   （见下拉框的"结束问卷"选项与 SurveyFillByCode 的 `targetId === -1`）。
       *   若某道新题拿到 -1，用户配的"结束问卷"就会在映射时被替换成那道题的 id ——
       *   一句话都不报，症状却是"选了结束却跳到某道题"。
       *   这里从 -1000 起留出安全间隔；真实 id 全是正数，负数区只属于前端。
       */
      tempQuestionId: -1000,

      // ===== 历史版本（编辑历史 / 对比 / 回滚）=====
      versionVisible: false,
      versions: [],
      /** 对比用的两个版本 id（旧 → 新） */
      diffFrom: null,
      diffTo: null,
      /** 对比结果（后端 SurveyVersionDiff） */
      versionDiff: null,
      /** 正在回滚的版本 id（用于按钮 loading，避免连点两次回滚） */
      rollingBack: null
    }
  },
  computed: {
    /** 当前登录用户是不是管理员（只用于决定"去回收站"跳哪个路由） */
    isAdmin() {
      const user = getUser()
      return Boolean(user && user.role === 'admin')
    },
    /** 是否在回收站里（后端口径：deleted != 0；NULL 视为未删除） */
    inRecycleBin() {
      return this.surveyInfo.deleted !== null && this.surveyInfo.deleted !== 0
    },
    /**
     * 「已结束」的问卷是否已经把截止时间改到了未来（或清空）。
     * ★ 与后端 SurveyService.publish 的判定保持一致：endTime 为 null 或晚于当前时间 → 允许重新发布。
     */
    endExtended() {
      if (!this.surveyInfo.endTime) return true
      return new Date(String(this.surveyInfo.endTime).replace(/-/g, '/')).getTime() > Date.now()
    }
  },
  mounted() {
    this.surveyId = this.$route.params.id
    if (this.surveyId) {
      this.loadSurveyInfo()
      this.loadQuestions()
    }
  },
  methods: {
    async loadSurveyInfo() {
      try {
        const data = await api.survey.detail(this.surveyId)
        this.surveyInfo = {
          title: data.title || '',
          description: data.description || '',
          status: data.status || 0,
          shareCode: data.shareCode,
          startTime: data.startTime,
          endTime: data.endTime,
          // ★ 带上 deleted —— 回收站里的问卷禁止保存/发布，页面要能判断并给出提示
          deleted: data.deleted == null ? 0 : data.deleted
        }
      } catch {
        // ★ 失败提示已由 src/api/http.js 统一弹出（ElMessage.error），
        //   catch 只保留流程控制的位置（本场景无需额外处理，保持页面不变）。
      }
    },

    async loadQuestions() {
      try {
        const data = await api.question.list(this.surveyId)
        this.questions = this.normalizeQuestions(data)
      } catch {
        // 失败提示由 src/api/http.js 统一弹出
      }
    },

    /** 把后端返回的题目数组归一化成编辑器内部结构（保存后重新同步也走它，保证两处口径一致）。 */
    normalizeQuestions(data) {
      return (data || []).map(q => ({
        ...q,
        required: q.required || 0,
        options: q.options || []
      }))
    },

    addQuestion(type) {
      // ★ 草稿阶段的题没有数据库 id，先发一个临时 id（负数），否则"跳转目标"下拉框无值可用。
      const newQ = { id: this.tempQuestionId--, type, title: '', required: 0, options: [] }
      if (type === 1 || type === 2) {
        newQ.options = [{ content: '' }, { content: '' }]
      }
      this.questions.push(newQ)
    },

    removeQuestion(index) {
      this.questions.splice(index, 1)
    },

    addOption(qIndex) {
      this.questions[qIndex].options.push({ content: '' })
    },

    removeOption(qIndex, oIndex) {
      this.questions[qIndex].options.splice(oIndex, 1)
    },

    /**
     * 保存问卷信息 + 题目。
     *
     * @returns {Promise<boolean>} true = 保存成功；false = 校验失败或后端拒绝（提示已弹出）
     * ★ 返回值是给 publishSurvey() 用的：必须先确认"真的保存成功"再去发布，
     *   否则会出现"发布成功但标题/题目改动没保存"（本方法校验失败时只 `return`、
     *   后端失败时 `catch` 吞掉，调用方无从得知）。
     */
    async saveQuestions() {
      if (!this.surveyInfo.title.trim()) {
        ElMessage.warning('请输入问卷标题')
        return false
      }
      for (let i = 0; i < this.questions.length; i++) {
        if (!this.questions[i].title.trim()) {
          ElMessage.warning(`第 ${i + 1} 题的题目内容不能为空`)
          return false
        }
      }
      if (this.saving) return false    // 门控：上一次保存还没走完

      this.saving = true
      try {
        // 0. ★ **保存前的跳转预检查**：任何选项的跳转目标都必须指向"编辑器里现在存在的某道题"。
        //    典型触发场景：用户删掉了被别的题跳转指向的那道题 ——
        //    这时规则已经失效，必须**拦住保存并说清是哪一题**，
        //    而不是让后端拒绝（提示不够具体）或静默把规则丢掉（用户以为还在）。
        if (!this.validateJumpTargets()) return false

        // 1. 更新问卷信息（含时间字段）
        // ★ 这里【不要】再传 shareCode：
        //   分享码由服务端独占管理（SurveyController.update() 会无条件沿用数据库里的旧值），
        //   传了也会被忽略，属于冗余参数，而且容易让人误以为前端能改分享码。
        // ★ 也【不要】再传 status：SurveyMapper.update 的 SET 列表里根本没有 status
        //   （改状态收口在 publish / updateStatusAndRecycle 等专用入口），
        //   传了不生效，只会让人误以为"保存时能顺手改状态"。
        await api.survey.update({
          id: this.surveyId,
          title: this.surveyInfo.title,
          description: this.surveyInfo.description,
          startTime: this.surveyInfo.startTime,
          endTime: this.surveyInfo.endTime
        })

        // 2. 保存题目
        //   api.question.save(surveyId, questions) 的第二个参数就是**本次完整题目数组**
        //   （后端是全量覆盖语义，见 src/api/question.js 的注释），
        //   返回值是**按下标一一对应**的题目 id 列表（后端保存后回显）。
        // ★ 失败时后端返回 400 + message，由 http.js 弹红字并 throw。
        //
        // ★★ 草稿阶段新加的题还没有数据库 id，而"跳转目标"必须存真 id，所以：
        //    ① 先**预存**一次（提交完整数组，去掉"指向临时题"的跳转）拿到真实 id；
        //    ② 用真实 id 替换掉题目 id 与所有跳转目标；
        //    ③ 再正式保存一次（此时跳转目标全是真 id）。
        //    首次保存因此是两次往返，之后题目都有真 id，永远只有一次。
        const pending = await this.ensureRealQuestionIds()
        if (!pending) return false
        // ★ 换完真 id 之后再查一次：预存可能被后端重建了题目（id 变了），
        //   这时"原来解析得通的目标"有可能解析不通了 —— 必须拦在正式提交之前。
        if (!this.validateJumpTargets()) return false
        // 第二次提交做**有限重发**：第一次已经成功、只是它的响应丢了（网络抖动）时，
        // 重发一次就能救回来 —— 否则用户以为没保存，再点一次反而要走完整流程。
        const idItems = await this.saveWithRetry()
        this.applyServerIdOrder(idItems)
        ElMessage.success('保存成功')
        return true
      } catch {
        // 失败提示由 src/api/http.js 统一弹出
        return false
      } finally {
        this.saving = false
      }
    },

    /**
     * 保存前的跳转预检查。
     *
     * 规则：每个选项的 `nextQuestionId` 只能是
     *   · `null`（按顺序）/ `undefined`（没设）/ `-1`（结束问卷）；或
     *   · 某道**当前编辑器里存在**的题的 id（含还没保存的新题的临时 id —— 它们随后会被换成真 id）。
     *
     * 只报告**第一条**问题并定位到题号，让用户能直接去改。
     *
     * @returns {boolean} true = 全部合法，可以保存；false = 已弹提示，必须中止保存
     */
    validateJumpTargets() {
      const existingIds = new Set(this.questions.map(q => q.id))
      for (let i = 0; i < this.questions.length; i++) {
        const q = this.questions[i]
        for (const opt of q.options || []) {
          const target = opt.nextQuestionId
          if (target === null || target === undefined || target === -1) continue
          if (!existingIds.has(target)) {
            ElMessage.error(
              `第 ${i + 1} 题的选项「${opt.content || '(未填内容)'}」要跳转的那道题已被删除，` +
              `请重新设置该选项的跳转，或清空这项跳转后再保存`
            )
            return false
          }
        }
      }
      return true
    },

    /**
     * 提交题目，失败时**有限重发**（最多 2 次，间隔 400ms）。
     *
     * ★ 只重发**可恢复的失败**：网络中断 / 超时 / 5xx。
     *   4xx（尤其是 400）是**业务拒绝**，重发不会变好，反而会重复打扰用户 —— 直接抛出。
     * ★ 为什么值得重发：走到这里说明"预存"已经成功、临时 id 也已经换成真 id，
     *   只差最后一次落库；网络抖一下就让用户重头再点一遍，代价远大于重发一次。
     */
    async saveWithRetry() {
      const MAX_ATTEMPTS = 2
      let lastError = null
      for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
          return await api.question.save(this.surveyId, this.questions)
        } catch (e) {
          lastError = e
          // 有状态码且小于 500 → 业务拒绝，不重发
          if (typeof e?.status === 'number' && e.status < 500) throw e
          if (attempt < MAX_ATTEMPTS) {
            await new Promise(r => setTimeout(r, 400))
          }
        }
      }
      throw lastError
    },

    /**

    /**
     * 让**所有题目**都拥有真实的数据库 id（草稿阶段只有前端发的临时 id，从 -1000 递减）。
     *
     * ★★ **预存必须提交"完整的题目数组"**：后端这个接口是**全量覆盖**语义
     *   （没传的题会被删掉），所以只提交新题会把问卷里**已有的题目全删了**。
     *   这里提交完整数组，只把**指向临时 id 的跳转目标去掉**
     *   —— 它们此刻还没有对应的真实题，留着只会被后端置空并把这几条规则悄悄丢掉。
     *
     * @returns {Promise<boolean>} true = 现在所有题都有真 id（可以正式保存）；
     *                             false = 预存失败（提示已弹出，调用方应中止本次保存）
     */
    async ensureRealQuestionIds() {
      if (!this.questions.some(q => !this.isRealId(q.id))) {
        return true   // 常见路径：题目都已在库里，一次提交即可
      }
      // ① 完整数组 + 去掉"指向临时题"的跳转（完整是硬要求，见方法注释）
      const payload = this.questions.map(q => ({
        id: this.isRealId(q.id) ? q.id : undefined,   // 已有题带上真 id，避免被当成新题重建
        type: q.type,
        title: q.title,
        required: q.required,
        options: (q.options || []).map(o => ({
          content: o.content,
          nextQuestionId: this.isRealId(o.nextQuestionId) || o.nextQuestionId === -1
            ? o.nextQuestionId
            : null
        }))
      }))
      const idItems = await api.question.save(this.surveyId, payload)
      if (!Array.isArray(idItems) || idItems.length !== payload.length) {
        ElMessage.error('保存题目失败（后端未返回题目 id，请重试）')
        return false
      }
      // ② 临时 id → 真实 id（题目自身的 id，以及跳转目标里指向临时 id 的）
      const idMap = new Map()
      this.questions.forEach((q, i) => {
        if (!this.isRealId(q.id)) idMap.set(q.id, idItems[i].id)
      })
      this.questions.forEach(q => {
        if (idMap.has(q.id)) q.id = idMap.get(q.id)
        ;(q.options || []).forEach(opt => {
          if (idMap.has(opt.nextQuestionId)) opt.nextQuestionId = idMap.get(opt.nextQuestionId)
        })
      })
      return true
    },

    /** 真实 id 是**正数**；前端临时 id 是负数（-1000 起），`-1` 是"结束问卷"这个保留值。 */
    isRealId(id) {
      return typeof id === 'number' && id > 0
    },

    /**
     * 保存成功后，把编辑态的题目顺序/真实 id 与后端回显对齐。
     *
     * 后端按数组顺序重排 sortOrder，所以回显的第 i 项就对应本次提交的第 i 项。
     * 这里只做**校验 + 兜底**：正常情况下编辑态已经是正确的（正式提交前就换好了真 id）。
     */
    applyServerIdOrder(idItems) {
      if (!Array.isArray(idItems) || idItems.length !== this.questions.length) return
      let mismatch = 0
      idItems.forEach((item, i) => {
        const q = this.questions[i]
        if (q && this.isRealId(item.id) && q.id !== item.id) {
          q.id = item.id      // 兜底：理论上不该发生，发生即说明上面的映射有漏
          mismatch++
        }
        if (q) q.sortOrder = item.sortOrder
      })
      if (mismatch > 0) {
        ElMessage.error(`有 ${mismatch} 道题的 id 与后端不一致，跳转规则可能不准确，请重新检查后再保存`)
      }
    },

    // ===== 历史版本 =====

    async openVersions() {
      this.versionVisible = true
      await this.loadVersions()
    },

    async loadVersions() {
      this.versions = await api.survey.versions(this.surveyId)
      // 默认预选"最新两版"做对比，省得用户每次手选
      if (this.versions.length >= 2 && !this.diffFrom && !this.diffTo) {
        this.diffTo = this.versions[0].id
        this.diffFrom = this.versions[1].id
      }
      this.versionDiff = null
    },

    async rollbackVersion(v) {
      try {
        await this.$confirm(
          `把题目恢复到 v${v.versionNo}？\n\n会生成一个新版本，历史不会丢（回滚错了还能再回滚回来）。`,
          '回滚版本',
          { type: 'warning', confirmButtonText: '确定回滚', cancelButtonText: '取消' }
        )
      } catch {
        return // 用户取消
      }
      this.rollingBack = v.id
      try {
        const created = await api.survey.rollbackVersion(v.id)
        ElMessage.success(`已回滚到 v${v.versionNo}，生成新版本 v${created.versionNo}`)
        await this.loadVersions()
        // ★ 必须重新加载题目：编辑器里还是回滚前的数组，此时直接点保存会把回滚结果覆盖掉
        await this.loadQuestions()
      } finally {
        this.rollingBack = null
      }
    },

    async doDiff() {
      this.versionDiff = await api.survey.versionDiff(this.diffFrom, this.diffTo)
    },

    async publishSurvey() {
      // ★★ **先确认保存成功，再发布**：否则会出现"发布成功但改动没保存"。
      const wasEnded = this.surveyInfo.status === 2
      const saved = await this.saveQuestions()
      if (!saved) {
        ElMessage.warning('问卷尚未保存成功，已取消发布；请先按上面的提示修正')
        return
      }
      try {
        //   返回后端响应体（后端会按需生成分享码 shareCode）。
        const data = await api.survey.publish(this.surveyId)
        if (data && data.shareCode) {
          this.surveyInfo.status = 1
          this.surveyInfo.shareCode = data.shareCode
          ElMessage.success((wasEnded ? '已重新发布' : '发布成功') + '！分享码：' + data.shareCode)
        } else {
          ElMessage.error('发布失败')
        }
      } catch {
        // 失败提示由 src/api/http.js 统一弹出
      }
    },

    goBack() {
      this.$router.push('/my/surveys')
    },

    /** ★ 回收站提示里的"去回收站"入口（管理员与普通用户看到的都是自己的回收站页） */
    goRecycle() {
      this.$router.push(this.isAdmin ? '/admin/recycle' : '/my/recycle')
    }
  }
}
</script>