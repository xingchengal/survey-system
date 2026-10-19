<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="page-title">全部问卷</h2>
        <p class="page-subtitle">共 {{ total }} 份 · 可多选批量移入回收站、按创建时间筛选、导出 Excel</p>
      </div>
    </div>

    <div class="toolbar-card">
      <!-- ★ 模板视图必须走 /survey/templates：/survey/page 的 SQL 排除 is_template=1，
           不切数据源就看不到模板、也无法在本页取消；开关不配 inline-prompt（文字会被吞掉） -->
      <el-switch
        v-model="showTemplates"
        active-text="显示模板"
        @change="handleTemplateViewChange"
      />
      <el-select v-model="statusFilter" placeholder="全部状态" clearable :disabled="showTemplates" @change="handleSearch">
        <el-option label="全部状态" value="" />
        <el-option label="草稿" :value="0" />
        <el-option label="已发布" :value="1" />
        <el-option label="已结束" :value="2" />
      </el-select>
      <!-- 创建时间范围（闭区间，只到天）；/survey/templates 不支持时间筛选，模板视图下禁用 -->
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
      <!-- 无筛选条件时「重置」置灰：点了界面毫无变化，会被当成按钮坏了 -->
      <el-button :disabled="!hasFilter" @click="handleReset">重置</el-button>
      <!-- 刷新：带 loading 转圈。本地请求往往 <100ms，不加这个反馈就完全看不出点过 -->
      <el-button :icon="Refresh" :loading="loading" @click="loadSurveys">刷新</el-button>
      <!-- 导出的是**当前筛选结果**（不是当前页），后端上限 10000 份；模板视图无对应口径，禁用 -->
      <el-button type="success" plain :icon="Download" :loading="exporting" :disabled="showTemplates" @click="handleExport">
        导出 Excel
      </el-button>
      <!-- 列设置：纯前端偏好，存 localStorage。至少保留一列，否则表格会空成一片。 -->
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

    <!-- 批量操作条：选中 ≥1 项才出现。全部逐条调单条端点（Promise.allSettled），
         后端没有批量端点，这样才能精确报告哪几份成功、哪几份为什么失败 -->
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
          <el-button
            v-if="canManageTemplates"
            type="warning"
            link
            size="small"
            @click="handleTemplate(row)"
          >
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
        <div v-if="q.options && q.options.length" style="padding-left: 20px; color: var(--ink-2); margin-top: 6px;">
          <div v-for="opt in q.options" :key="opt.id">· {{ opt.content }}</div>
        </div>
      </div>
      <el-empty v-if="currentQuestions.length === 0" description="该问卷暂无题目" />
    </el-dialog>

    <!-- 设为/取消模板：分类是必填（模板广场按它聚合），所以用弹窗收集而不是直接调；
         文案方向跟 targetIsTemplate 走（1=接下来设为，0=取消），不要当成当前状态 -->
    <el-dialog
      v-model="templateDialogVisible"
      :title="templateForm.targetIsTemplate === 1 ? '设为模板' : '取消模板'"
      width="480px"
    >
      <template v-if="templateForm.targetIsTemplate === 1">
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
            <div class="text-secondary" style="font-size: 12px; margin-top: 4px;">
              分类决定模板广场里的筛选分组；可直接输入新分类，保存后会自动进入分类清单。
            </div>
          </el-form-item>
        </el-form>
      </template>
      <p v-else class="text-secondary">
        确定取消「{{ templateForm.title }}」的模板身份吗？取消后它会从模板广场消失，回到「全部问卷」列表。
      </p>
      <template #footer>
        <el-button @click="templateDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="templateSubmitting" @click="submitTemplate">
          {{ templateForm.targetIsTemplate === 1 ? '确定设为模板' : '确定取消' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// 本页请求统一走 src/api/，页面不自己取 token、拼 query、判断 res.ok、parse JSON
import api from '../api'
import { hasPermission } from '../utils/auth'
// :icon="Download" 这类属性变量需要真实变量，必须 import；模板标签形式由 main.js 全局注册
import { Refresh, Search, Download, Setting, Delete } from '@element-plus/icons-vue'
import PageState from '../components/PageState.vue'
import TableDensity from '../components/TableDensity.vue'
import { readPageNum, savePageNum } from '../utils/pageMemory'

const PAGE_KEY = 'AdminSurveyList'

/** 列设置的 localStorage 键 */
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
      // 创建时间范围：['2026-10-01', '2026-10-02'] 或 null；value-format 保证是 'YYYY-MM-DD'，可直接进 query
      dateRange: null,
      // false = /survey/page（排除模板）；true = /survey/templates（含模板，用来管理模板身份）
      showTemplates: false,
      pageNum: 1,
      pageSize: 10,
      total: 0,
      dialogVisible: false,
      currentSurvey: {},
      currentQuestions: [],
      selectedRows: [],
      batchWorking: false,
      exporting: false,
      visibleColumns: readVisibleColumns(),
      columnOptions: COLUMN_OPTIONS,
      // 并发保护：每次请求自增 seq，响应回来时若已不是最新序号就丢弃。
      //   「重置」会连发两次请求（日期 @change + handleReset），返回顺序不保证，
      //   慢的那个后到会把新结果覆盖成旧数据。
      loadSeq: 0,
      templateDialogVisible: false,
      templateSubmitting: false,
      // targetIsTemplate 是**目标状态**：1 = 这次要设为模板（显示分类选择），0 = 这次要取消模板
      templateForm: { id: null, title: '', category: '', targetIsTemplate: 0 },
      // 兜底 7 类，mounted 时用后端 /survey/template-categories 覆盖；allow-create 仍可现场输入新分类
      templateCategoryOptions: ['满意度', '报名', '反馈', '调研', '教育', '健康', '其他'],
      Search: markRaw(Search),
      Refresh: markRaw(Refresh),
      Download: markRaw(Download),
      Setting: markRaw(Setting),
      Delete: markRaw(Delete)
    }
  },
  computed: {
    /** 「设为/取消模板」需要 template:manage；只有 survey:manage 的次级管理员不显示该按钮 */
    canManageTemplates() {
      return hasPermission('template:manage')
    },
    /**
     * statusFilter 的 0（草稿）是有效值，不能用真值判断；
     * 没有任何筛选时「重置」置灰，否则点了界面毫无变化，用户会当成按钮坏了。
     */
    hasFilter() {
      return Boolean(
        this.keyword.trim() || this.statusFilter !== '' || this.dateRange
      )
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
    this.loadTemplateCategories()
  },
  methods: {
    // showTemplates 为真时必须走 api.survey.templates()：/survey/page 的 SQL 排除模板，
    //   不切数据源就看不到模板、也无法取消它。
    async loadSurveys() {
      const seq = ++this.loadSeq
      this.loading = true
      this.error = false
      try {
        if (this.showTemplates) {
          // 模板列表接口**不分页**（模板广场一次全给），所以这里本地分页，语义与 page 视图保持一致
          const all = await api.survey.templates({ keyword: this.keyword.trim() })
          if (seq !== this.loadSeq) return       // 已被更新的请求取代，丢弃
          const list = Array.isArray(all) ? all : []
          this.total = list.length
          const start = (this.pageNum - 1) * this.pageSize
          this.surveys = list.slice(start, start + this.pageSize)
          return
        }
        // status 的 0（草稿）不会被 toQuery 丢掉（只过滤 '' / null / undefined）；
        //   dateRange 为 null 时两个字段是 undefined，自动跳过 = 不限制时间。
        const data = await api.survey.page({
          pageNum: this.pageNum,
          pageSize: this.pageSize,
          keyword: this.keyword.trim(),
          status: this.statusFilter,
          startDate: this.dateRange && this.dateRange[0],
          endDate: this.dateRange && this.dateRange[1]
        })
        if (seq !== this.loadSeq) return       // 同上：慢请求不许覆盖新结果
        this.surveys = data.list || []
        this.total = data.total || 0
      } catch {
        // 错误提示已由 src/api/http.js 统一弹出，这里只置 error 让 PageState 显示重试入口
        // 同样只在"这次仍是最新请求"时才置错，否则一次早已被取代的失败会把界面打成错误态
        if (seq === this.loadSeq) this.error = true
      } finally {
        if (seq === this.loadSeq) this.loading = false
      }
    },

    handleSearch() {
      this.pageNum = 1
      savePageNum(PAGE_KEY, 1)
      this.loadSurveys()
    },

    /** 重置全部筛选条件：关键词 / 状态 / 时间范围一起清，页码回 1 */
    handleReset() {
      this.keyword = ''
      this.statusFilter = ''
      this.dateRange = null
      this.handleSearch()
    },

    // 切视图页码复位，否则翻到第 3 页再切会落到空页
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
     * ★ 逐条调 PUT /api/survey/recycle/{id}，用 allSettled 而非 all：一条失败不该回滚已成功的。
     * 每条传 { silent: true }，否则 10 条里 3 条失败会弹 3 次红字，比一次汇总更难读。
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

    async handleExport() {
      this.exporting = true
      try {
        // 走 http.download()：解析 Content-Disposition 文件名并触发下载；失败（如超过 10000 份）由 http.js 统一弹错
        await api.survey.exportExcel({
          keyword: this.keyword.trim(),
          status: this.statusFilter,
          startDate: this.dateRange && this.dateRange[0],
          endDate: this.dateRange && this.dateRange[1]
        })
        ElMessage.success('导出已开始，请查看浏览器下载')
      } catch {
        // 失败提示已由 http.js 统一弹出（如超过 10000 份）
      } finally {
        this.exporting = false
      }
    },

    isColumnVisible(key) {
      return this.visibleColumns.includes(key)
    },

    async handleView(row) {
      this.currentSurvey = row
      this.dialogVisible = true
      try {
        // 带 X-Survey-Editor：管理/编辑视角读取；否则查看设了答题密码的问卷会被 400 拦住
        this.currentQuestions = await api.question.list(row.id, {
          headers: { 'X-Survey-Editor': '1' }
        })
      } catch {
        // 提示已在 http.js 弹过；这里做流程控制：拿不到题目就显示空状态，不阻断弹窗
        this.currentQuestions = []
      }
    },

    // 设为/取消模板：PUT /survey/set-template/{id}?isTemplate=0|1&category=xxx
    // · 后端只允许 0/1；category 只在设为模板时有意义，回收站里的问卷会被 400 拦住
    // · 分类清单与「模板管理」共用 /survey/template-categories，后端会自动登记新分类
    async loadTemplateCategories() {
      try {
        const list = await api.survey.templateCategories({ silent: true })
        if (Array.isArray(list) && list.length) this.templateCategoryOptions = list
      } catch {
        // 分类清单拉不到时保留内置 7 类，不影响「设为模板」主流程
      }
    },

    handleTemplate(row) {
      const targetIsTemplate = row.isTemplate === 1 ? 0 : 1
      this.templateForm = {
        id: row.id,
        title: row.title,
        // 取消模板时分类传空串（后端归一成 NULL）：否则库里留下 '其他'，与既有的 NULL
        //   混成两种"没有分类"，按分类统计会分裂成两组。
        category: targetIsTemplate === 1 ? (row.templateCategory || '其他') : '',
        targetIsTemplate
      }
      this.templateDialogVisible = true
    },

    async submitTemplate() {
      const { id, targetIsTemplate, category } = this.templateForm
      // 设为模板时分类必填：留空会让模板广场把它显示成"通用/其他"
      if (targetIsTemplate === 1 && !String(category || '').trim()) {
        ElMessage.warning('请选择或输入模板分类')
        return
      }
      this.templateSubmitting = true
      try {
        await api.survey.setTemplate(id, targetIsTemplate, category)
        ElMessage.success(targetIsTemplate === 1 ? '已设为模板' : '已取消模板')
        this.templateDialogVisible = false
        this.loadSurveys()
        // 新输入的分类会被后端自动登记，刷新一次让下次弹窗直接可选
        this.loadTemplateCategories()
      } catch {
        // 失败提示已由 http.js 统一弹出（如"问卷已在回收站中，请先恢复再设为模板"）
      } finally {
        this.templateSubmitting = false
      }
    },

    // 移入回收站：recycle 不改本地数组，成功后必须重新 loadSurveys
    handleDelete(row) {
      ElMessageBox.confirm(`确定将问卷「${row.title}」移入回收站吗？`, '提示', {
        confirmButtonText: '移入回收站',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          // 失败由 http.js 按状态码弹错并 throw，所以页面按"能 await 成功就是成功"写即可。
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

/* 批量操作条：选中才出现，粘在工具栏与表格之间 */
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
