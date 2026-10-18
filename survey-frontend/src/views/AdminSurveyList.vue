<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="page-title">全部问卷</h2>
        <p class="page-subtitle">共 {{ total }} 份 · 可多选批量移入回收站、按创建时间筛选、导出 Excel</p>
      </div>
    </div>

    <div class="toolbar-card">
      <!-- "显示模板"开关：打开后走 api.survey.templates()，能看到全部模板并取消它们。
           ★ 少了这个开关，管理员把问卷设为模板后它会从本页消失、再也无法在本页取消：
           /api/survey/page 的 SQL 排除 is_template=1，而 /api/survey/templates 只提供读取。 -->
      <el-switch
        v-model="showTemplates"
        active-text="显示模板"
        inline-prompt
        @change="handleTemplateViewChange"
      />
      <el-select v-model="statusFilter" placeholder="全部状态" clearable :disabled="showTemplates" @change="handleSearch">
        <el-option label="全部状态" value="" />
        <el-option label="草稿" :value="0" />
        <el-option label="已发布" :value="1" />
        <el-option label="已结束" :value="2" />
      </el-select>
      <!-- 创建时间范围（G3）：闭区间，只到"天"。
           ★ /survey/templates 不支持时间筛选，所以模板视图下禁用，避免"选了日期但没生效"的错觉。 -->
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="创建起"
        end-placeholder="创建止"
        :disabled="showTemplates"
        class="date-range"
        @change="handleSearch"
      />
      <el-input v-model="keyword" placeholder="搜索标题或描述..." clearable :prefix-icon="Search" @keyup.enter="handleSearch" @clear="handleSearch" />
      <el-button type="primary" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>
      <el-button :icon="Refresh" @click="loadSurveys">刷新</el-button>
      <!-- 导出（G3）：导的是**当前筛选结果**（不是当前页），后端上限 10000 份。
           模板视图没有对应口径（导出 SQL 与 /page 一致、不含模板），所以禁用。 -->
      <el-button type="success" plain :icon="Download" :loading="exporting" :disabled="showTemplates" @click="handleExport">
        导出 Excel
      </el-button>
      <!-- 列设置（G3）：纯前端偏好，存 localStorage。至少保留一列，否则表格会空成一片。 -->
      <el-popover placement="bottom-end" :width="180" trigger="click">
        <template #reference>
          <el-button :icon="Setting">列设置</el-button>
        </template>
        <div class="col-setting">
          <div class="col-setting-title">显示哪些列</div>
          <el-checkbox-group v-model="visibleColumns">
            <el-checkbox v-for="col in columnOptions" :key="col.key" :value="col.key" :disabled="visibleColumns.length === 1 && visibleColumns.includes(col.key)">
              {{ col.label }}
            </el-checkbox>
          </el-checkbox-group>
        </div>
      </el-popover>
      <TableDensity />
    </div>

    <!-- 批量操作条：选中 ≥1 项才出现。
         ★ 所有批量动作都是**逐条调单条端点**（Promise.allSettled），后端没有批量端点 ——
           这样能精确报告"哪几份成功、哪几份为什么失败"，而不是整批回滚。 -->
    <div v-if="selectedRows.length > 0" class="batch-bar">
      <span class="batch-count">已选 <strong>{{ selectedRows.length }}</strong> 项</span>
      <el-button type="danger" size="small" :icon="Delete" :loading="batchWorking" @click="handleBatchRecycle">
        批量移入回收站
      </el-button>
      <el-button size="small" link @click="clearSelection">取消选择</el-button>
      <span class="batch-hint">移入后可在「回收站」恢复</span>
    </div>

    <div class="table-card">
    <PageState
      :loading="loading"
      :error="error"
      :empty="surveys.length === 0"
      empty-text="暂无问卷"
      @retry="loadSurveys"
    >
    <el-table
      ref="tableRef"
      class="w-full"
      :data="surveys"
      stripe
      border
      max-height="560"
      row-key="id"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="46" align="center" />
      <el-table-column v-if="isColumnVisible('id')" prop="id" label="ID" width="70" align="center" />
      <el-table-column v-if="isColumnVisible('title')" prop="title" label="问卷标题" min-width="180" show-overflow-tooltip />
      <el-table-column v-if="isColumnVisible('description')" prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column v-if="showTemplates && isColumnVisible('templateCategory')" label="模板分类" width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small" type="success">{{ row.templateCategory || '通用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="isColumnVisible('status')" prop="status" label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.status === 1" type="success" size="small">已发布</el-tag>
          <el-tag v-else-if="row.status === 2" type="danger" size="small">已结束</el-tag>
          <el-tag v-else type="info" size="small">草稿</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="isColumnVisible('createdTime')" prop="createdTime" label="创建时间" width="170" />
      <el-table-column label="操作" :width="showTemplates ? 250 : 220" align="center" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="handleView(row)">查看</el-button>
          <el-button type="warning" link size="small" @click="handleTemplate(row)">
            {{ row.isTemplate === 1 ? '取消模板' : '设为模板' }}
          </el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager-row">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :page-sizes="[5, 10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>
    </PageState>
    </div>

    <el-dialog v-model="dialogVisible" :title="currentSurvey.title" width="600px">
      <p class="text-secondary">{{ currentSurvey.description }}</p>
      <el-divider />
      <div class="mb-16" v-for="(q, idx) in currentQuestions" :key="q.id">
        <div class="text-bold">
          {{ idx + 1 }}. {{ q.title }}
          <el-tag class="ml-8" size="small">
            {{ q.type === 1 ? '单选' : q.type === 2 ? '多选' : '填空' }}
          </el-tag>
        </div>
        <div v-if="q.options && q.options.length" style="padding-left: 20px; color: #666; margin-top: 6px;">
          <div v-for="opt in q.options" :key="opt.id">· {{ opt.content }}</div>
        </div>
      </div>
      <el-empty v-if="currentQuestions.length === 0" description="该问卷暂无题目" />
    </el-dialog>

    <!-- 设为模板 / 取消模板（管理员）。
         ★ 为什么要弹窗而不是直接调：模板的"分类"是必填信息（模板广场按它聚合），
         否则直接调会把分类写成 NULL，模板广场里就会显示成"通用/其他"。 -->
    <el-dialog v-model="templateDialogVisible" :title="templateForm.isTemplate === 1 ? '取消模板' : '设为模板'" width="480px">
      <p class="text-secondary" v-if="templateForm.isTemplate === 1">
        确定取消「{{ templateForm.title }}」的模板身份吗？取消后它会从模板广场消失，回到「全部问卷」列表。
      </p>
      <template v-else>
        <p class="text-secondary">
          「{{ templateForm.title }}」将出现在<strong>所有登录用户</strong>都能看到的模板广场，并可被任何人「从模板创建」。
        </p>
        <p class="text-secondary" style="margin-top: 6px;">
          设为模板后可到「模板管理」里改分类、编辑内容或取消模板。
        </p>
        <el-form label-width="90px" class="mt-12">
          <el-form-item label="模板分类">
            <el-select v-model="templateForm.category" placeholder="请选择或输入分类" allow-create filterable class="w-full">
              <el-option v-for="cat in templateCategoryOptions" :key="cat" :label="cat" :value="cat" />
            </el-select>
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="templateDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="templateSubmitting" @click="submitTemplate">
          {{ templateForm.isTemplate === 1 ? '确定取消' : '确定设为模板' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// 统一接口层：本页的请求全部走 src/api/ ——
//   分页列表 / 模板列表（"显示模板"开关用）/ 题目列表 / 设为模板 / 移入回收站 / 导出。
//   页面不再自己取 token、拼 query、判断 res.ok、parse JSON。
import api from '../api'
// Refresh / Search / Download / Setting 都用于「属性变量」形式（:icon="Download"），
// 这种写法需要真实的变量，所以必须 import；
// 若某图标只以模板标签形式出现（<Bell />），则由 main.js 全局注册即可，无需在这里 import。
import { Refresh, Search, Download, Setting, Delete } from '@element-plus/icons-vue'
// 三态容器：骨架 / 空 / 错误（带重试）
import PageState from '../components/PageState.vue'
// 行密度切换（默认 / 紧凑）
import TableDensity from '../components/TableDensity.vue'
// 页码记忆（sessionStorage）
import { readPageNum, savePageNum } from '../utils/pageMemory'

/** 本页在 sessionStorage 里的页码键 */
const PAGE_KEY = 'AdminSurveyList'

/** 列设置（G3）的 localStorage 键：记住"哪几列可见"，刷新页面后不丢 */
const COLUMN_KEY = 'AdminSurveyList:columns'

/** 可开关的列（ID / 标题 / 描述 / 状态 / 创建时间）。模板分类列只在模板视图出现，一并纳入开关。 */
const COLUMN_OPTIONS = [
  { key: 'id', label: 'ID' },
  { key: 'title', label: '问卷标题' },
  { key: 'description', label: '描述' },
  { key: 'templateCategory', label: '模板分类（模板视图）' },
  { key: 'status', label: '状态' },
  { key: 'createdTime', label: '创建时间' }
]

const ALL_COLUMNS = COLUMN_OPTIONS.map((c) => c.key)

/** 读回上次的列设置；数据坏了/没存过就用全量（绝不让表格因为一条脏数据变成空白） */
function readVisibleColumns() {
  try {
    const raw = localStorage.getItem(COLUMN_KEY)
    if (!raw) return [...ALL_COLUMNS]
    const saved = JSON.parse(raw)
    if (!Array.isArray(saved)) return [...ALL_COLUMNS]
    const valid = saved.filter((k) => ALL_COLUMNS.includes(k))
    return valid.length > 0 ? valid : [...ALL_COLUMNS]
  } catch {
    return [...ALL_COLUMNS]
  }
}

/** 把一次批量操作的结果汇总成"成功数 + 失败明细" */
function summarizeBatch(rows, results) {
  const failures = []
  let ok = 0
  results.forEach((r, i) => {
    if (r.status === 'fulfilled') {
      ok += 1
    } else {
      failures.push({ title: rows[i].title, message: (r.reason && r.reason.message) || '未知原因' })
    }
  })
  return { ok, failures }
}

// 图标已在 main.js 里全局注册，这里不再重复注册。
// 说明：模板标签形式（<Bell />）和属性变量形式（:icon="Refresh"）都不受影响 ——
//       前者走全局注册，后者走本文件顶部的 import。
export default {
  components: { PageState, TableDensity },
  data() {
    return {
      surveys: [],
      loading: false,
      // 接口失败标记：由 PageState 渲染「加载失败 + 重试」
      error: false,
      keyword: '',
      statusFilter: '',
      // 创建时间范围（G3）：['2026-10-01', '2026-10-02'] 或 null。
      // ★ value-format 决定它永远是 'YYYY-MM-DD' 字符串，直接进 query 不用再格式化。
      dateRange: null,
      // false = 走 api.survey.page（排除模板，默认视图）
      // true  = 走 api.survey.templates（含模板，用来管理模板身份）
      showTemplates: false,
      pageNum: 1,
      pageSize: 10,
      total: 0,
      dialogVisible: false,
      currentSurvey: {},
      currentQuestions: [],
      // ===== G3：多选 / 批量 / 导出 / 列设置 =====
      // el-table 的选中行（selection-change 事件回填）
      selectedRows: [],
      batchWorking: false,
      exporting: false,
      visibleColumns: readVisibleColumns(),
      columnOptions: COLUMN_OPTIONS,
      // ===== 设为/取消模板 =====
      templateDialogVisible: false,
      templateSubmitting: false,
      // title 只用于弹窗文案；isTemplate 决定是"设为"还是"取消"
      templateForm: { id: null, title: '', category: '', isTemplate: 0 },
      // 与 TemplateSeederService 的 7 大分类一致（那 31 个预置模板就是这么分的）；
      // 用 allow-create 允许管理员新建分类，前端不做白名单校验（后端也没有分类白名单）。
      templateCategoryOptions: ['满意度', '报名', '反馈', '调研', '教育', '健康', '其他'],
      Search: markRaw(Search),
      Refresh: markRaw(Refresh),
      Download: markRaw(Download),
      Setting: markRaw(Setting),
      Delete: markRaw(Delete)
    }
  },
  watch: {
    // 列设置改动立即落 localStorage（不需要"保存"按钮，与 TableDensity 的习惯一致）
    visibleColumns: {
      deep: true,
      handler(value) {
        try {
          localStorage.setItem(COLUMN_KEY, JSON.stringify(value))
        } catch {
          // 隐私模式/配额满时写不进去：列设置只是偏好，失败不影响使用，静默即可
        }
      }
    }
  },
  mounted() {
    // 恢复上次停留的页码
    this.pageNum = readPageNum(PAGE_KEY)
    this.loadSurveys()
  },
  methods: {
    // ===== 分页加载全部问卷（管理员）=====
    // 关联：surveys 喂给表格；total 喂给 el-pagination；statusFilter / keyword / dateRange 来自顶部筛选栏。
    // ★ showTemplates 为真时必须走 api.survey.templates()（模板列表）：
    //   api.survey.page 的 SQL 排除 is_template = 1，不切数据源就看不到模板、也就无法取消它。
    async loadSurveys() {
      this.loading = true
      this.error = false
      try {
        if (this.showTemplates) {
          // 模板列表接口**不分页**（模板广场一次全给），所以这里本地分页，语义与 page 视图保持一致
          const all = await api.survey.templates({ keyword: this.keyword.trim() })
          const list = Array.isArray(all) ? all : []
          this.total = list.length
          const start = (this.pageNum - 1) * this.pageSize
          this.surveys = list.slice(start, start + this.pageSize)
          return
        }
        // 参数对象交给 api.survey.page()，由 http.js 的 toQuery 统一拼串并做 URL 编码。
        // ★ 注意 status 的 0（草稿）不会被误当成空值丢掉 —— toQuery 只过滤 '' / null / undefined，
        //   所以这里不必自己判空。
        // ★ dateRange 为 null 时下面两个字段都是 undefined，toQuery 会自动跳过（= 不限制时间）。
        const data = await api.survey.page({
          pageNum: this.pageNum,
          pageSize: this.pageSize,
          keyword: this.keyword.trim(),
          status: this.statusFilter,
          startDate: this.dateRange && this.dateRange[0],
          endDate: this.dateRange && this.dateRange[1]
        })
        this.surveys = data.list || []
        this.total = data.total || 0
      } catch {
        // 错误提示已由 src/api/http.js 统一弹出，这里只置 error 让 PageState 显示重试入口
        this.error = true
      } finally {
        this.loading = false
      }
    },

    handleSearch() {
      this.pageNum = 1
      savePageNum(PAGE_KEY, 1)
      this.loadSurveys()
    },

    /** 重置全部筛选条件（G3）：关键词 / 状态 / 时间范围一起清，页码回 1 */
    handleReset() {
      this.keyword = ''
      this.statusFilter = ''
      this.dateRange = null
      this.handleSearch()
    },

    // ★ 模板列表接口不分页（一次给全部），前端本地分页；
    //   切换视图时把页码复位，否则在"全部问卷"翻到第 3 页再切"显示模板"会落到空页。
    handleTemplateViewChange() {
      this.pageNum = 1
      savePageNum(PAGE_KEY, 1)
      // 切视图时清空选中：两个视图的行集合不同，留着上一批选中会误操作
      this.clearSelection()
      this.loadSurveys()
    },

    handleSizeChange(size) {
      this.pageSize = size
      this.pageNum = 1
      savePageNum(PAGE_KEY, 1)
      this.loadSurveys()
    },

    handlePageChange(page) {
      this.pageNum = page
      savePageNum(PAGE_KEY, page)
      this.loadSurveys()
    },

    // ===== G3：多选与批量操作 =====
    handleSelectionChange(rows) {
      this.selectedRows = rows || []
    },

    clearSelection() {
      this.selectedRows = []
      // 同步清掉表格内部的勾选状态（否则换页/刷新后表头复选框还是"半选"）
      if (this.$refs.tableRef) {
        this.$refs.tableRef.clearSelection()
      }
    },

    /**
     * 批量移入回收站。
     * ★ 逐条调用 `PUT /api/survey/recycle/{id}`（管理员对该端点有权限，见审计表「需登录+归属」档）。
     *   传 `{ silent: true }` 是为了**不让 http.js 对每条失败各弹一次红字** ——
     *   批量 10 条里有 3 条失败时，弹 3 次比一次汇总更难读。
     * ★ 用 allSettled 而不是 all：一条失败不该让已成功的回滚（后端本来就是各自独立的事务）。
     */
    handleBatchRecycle() {
      const rows = [...this.selectedRows]
      if (rows.length === 0) return
      ElMessageBox.confirm(
        `确定把选中的 ${rows.length} 份问卷移入回收站吗？移入后可在「回收站」恢复。`,
        '批量移入回收站',
        { confirmButtonText: '确定移入', cancelButtonText: '取消', type: 'warning' }
      ).then(async () => {
        this.batchWorking = true
        try {
          const results = await Promise.allSettled(rows.map((r) => api.survey.recycle(r.id, { silent: true })))
          const { ok, failures } = summarizeBatch(rows, results)
          if (failures.length === 0) {
            ElMessage.success(`已将 ${ok} 份问卷移入回收站`)
          } else {
            const detail = failures.slice(0, 3).map((f) => `「${f.title}」${f.message}`).join('；')
            ElMessage.warning(`${ok} 份已移入回收站，${failures.length} 份失败：${detail}${failures.length > 3 ? ' 等' : ''}`)
          }
          this.clearSelection()
          this.loadSurveys()
        } finally {
          this.batchWorking = false
        }
      }).catch(() => {})
    },

    // ===== G3：导出（当前筛选结果，不是当前页）=====
    async handleExport() {
      this.exporting = true
      try {
        // ★ 走 http.download()：解析后端 Content-Disposition 里的文件名并触发浏览器下载。
        //   失败（如超过 10000 份）由 http.js 统一弹红字并 throw，这里不用再提示。
        await api.survey.exportExcel({
          keyword: this.keyword.trim(),
          status: this.statusFilter,
          startDate: this.dateRange && this.dateRange[0],
          endDate: this.dateRange && this.dateRange[1]
        })
        ElMessage.success('导出已开始，请查看浏览器下载')
      } catch {
        // 失败提示已由 http.js 统一弹出
      } finally {
        this.exporting = false
      }
    },

    // ===== G3：列设置 =====
    isColumnVisible(key) {
      return this.visibleColumns.includes(key)
    },

    // ===== 查看问卷详情（弹窗里列出题目）=====
    // 关联：currentSurvey / currentQuestions 分别喂给 el-dialog 的标题和题目列表。
    async handleView(row) {
      this.currentSurvey = row
      this.dialogVisible = true
      try {
        // ★ 带 X-Survey-Editor：这是「管理/编辑视角」的读取，不是填写人视角。
        //   不带的话，管理员查看一份**设了答题密码**的问卷会被 400 拦住（后端只对
        //   编辑器/管理视角豁免口令，填写页必须输对密码）。
        this.currentQuestions = await api.question.list(row.id, {
          headers: { 'X-Survey-Editor': '1' }
        })
      } catch {
        // 提示已在 http.js 弹过；这里做流程控制：拿不到题目就显示空状态，不阻断弹窗
        this.currentQuestions = []
      }
    },

    // ===== 设为模板 / 取消模板（管理员）=====
    // 关联：PUT /api/survey/set-template/{id}?isTemplate=0|1&category=xxx
    //   · 后端已把 isTemplate 收成只允许 0/1；category 只在"设为模板"时有意义
    //   · 后端还会挡"回收站里的问卷"（400），失败提示由 http.js 统一弹出
    handleTemplate(row) {
      const isTemplate = row.isTemplate === 1 ? 0 : 1
      this.templateForm = {
        id: row.id,
        title: row.title,
        // ★ 取消模板时分类传空串（后端 SurveyService.setTemplate 会把空白归一成 NULL）；
        //   否则库里会留下 template_category='其他'，与既有的 NULL 混成两种"没有分类" ——
        //   前端展示恰好都显示成"通用/其他"，表面看不出问题，但按分类统计会分裂成两组。
        // 设为模板时预填原分类或"其他"，便于快速确认。
        category: isTemplate === 1 ? (row.templateCategory || '其他') : '',
        isTemplate
      }
      this.templateDialogVisible = true
    },

    async submitTemplate() {
      const { id, isTemplate, category } = this.templateForm
      // 设为模板时分类必填：留空会让模板广场把它显示成"通用/其他"（后端不做非空校验）
      if (isTemplate === 1 && !String(category || '').trim()) {
        ElMessage.warning('请选择或输入模板分类')
        return
      }
      this.templateSubmitting = true
      try {
        await api.survey.setTemplate(id, isTemplate, category)
        ElMessage.success(isTemplate === 1 ? '已设为模板' : '已取消模板')
        this.templateDialogVisible = false
        this.loadSurveys()
      } catch {
        // 失败提示已由 http.js 统一弹出（如"问卷已在回收站中，请先恢复再设为模板"）
      } finally {
        this.templateSubmitting = false
      }
    },

    // ===== 把问卷移入回收站 =====
    // 关联：成功后要重新拉列表（recycle 不改本地数组，必须重新 loadSurveys）。
    handleDelete(row) {
      ElMessageBox.confirm(`确定将问卷「${row.title}」移入回收站吗？`, '提示', {
        confirmButtonText: '移入回收站',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          // ★ 失败由 http.js 按状态码弹错并 throw，所以页面按"能 await 成功就是成功"写即可。
          await api.survey.recycle(row.id)
          ElMessage.success('已移入回收站')
          this.loadSurveys()
        } catch {
          // HTTP 层失败（4xx/5xx、401、网络异常）的提示已由 http.js 统一弹出，这里不再重复弹
        }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
/* 时间范围选择器：固定宽度，避免把工具栏挤成两行 */
.date-range {
  width: 250px;
  flex: 0 0 auto;
}

/* 批量操作条（G3）：选中才出现，粘在工具栏与表格之间 */
.batch-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 16px;
  border-radius: var(--r-md, 10px);
  border: 1px solid var(--sky-200, #bae0ff);
  background: var(--sky-50, #f0f9ff);
  animation: batch-bar-in 0.18s ease-out;
}

.batch-count {
  font-size: 14px;
  color: var(--text-regular, #606266);
}

.batch-count strong {
  color: var(--sky-600, #1677ff);
  font-size: 15px;
}

.batch-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-secondary, #909399);
}

@keyframes batch-bar-in {
  from { opacity: 0; transform: translateY(-4px); }
  to   { opacity: 1; transform: translateY(0); }
}

/* 列设置弹层 */
.col-setting-title {
  font-size: 12px;
  color: var(--text-secondary, #909399);
  margin-bottom: 8px;
}

.col-setting :deep(.el-checkbox) {
  display: flex;
  height: 28px;
}
</style>
