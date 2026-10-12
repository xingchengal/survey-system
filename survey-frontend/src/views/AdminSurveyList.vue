<template>
  <div>
    <div class="page-header">
      <h2 class="m-0">📋 全部问卷</h2>
      <div class="flex-gap-10">
        <!-- "显示模板"开关：打开后走 api.survey.templates()，能看到全部模板并取消它们。
             ★ 少了这个开关，管理员把问卷设为模板后它会从本页消失、再也无法在本页取消：
             /api/survey/page 的 SQL 排除 is_template=1，而 /api/survey/templates 只提供读取。 -->
        <el-switch
          v-model="showTemplates"
          active-text="显示模板"
          inline-prompt
          @change="handleTemplateViewChange"
        />
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px;" :disabled="showTemplates" @change="handleSearch">
          <el-option label="全部状态" value="" />
          <el-option label="草稿" :value="0" /> 
          <el-option label="已发布" :value="1" />
          <el-option label="已结束" :value="2" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索标题或描述..." clearable :prefix-icon="Search" style="width: 260px;" @keyup.enter="handleSearch" @clear="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="loadSurveys">刷新</el-button>
      </div>
    </div>

    <el-table class="w-full" :data="surveys" stripe border v-loading="loading">
      <el-table-column prop="id" label="ID" width="70" align="center" />
      <el-table-column prop="title" label="问卷标题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column v-if="showTemplates" label="模板分类" width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small" type="success">{{ row.templateCategory || '通用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.status === 1" type="success" size="small">已发布</el-tag>
          <el-tag v-else-if="row.status === 2" type="danger" size="small">已结束</el-tag>
          <el-tag v-else type="info" size="small">草稿</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdTime" label="创建时间" width="170" />
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

    <el-empty v-if="surveys.length === 0 && !loading" description="暂无问卷" />

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
// 统一接口层：本页 5 个请求全部走 src/api/ ——
//   分页列表 / 模板列表（"显示模板"开关用）/ 题目列表 / 设为模板 / 移入回收站。
//   页面不再自己取 token、拼 query、判断 res.ok、parse JSON。
import api from '../api'
// Refresh / Search 都用于「属性变量」形式（:icon="Refresh"、:prefix-icon="Search"），
// 这种写法需要真实的变量，所以必须 import；
// 若某图标只以模板标签形式出现（<Bell />），则由 main.js 全局注册即可，无需在这里 import。
import { Refresh, Search } from '@element-plus/icons-vue'

// 图标已在 main.js 里全局注册，这里不再重复注册。
// 说明：模板标签形式（<Bell />）和属性变量形式（:prefix-icon="Search"）都不受影响 ——
//       前者走全局注册，后者走本文件顶部的 import。
export default {
  data() {
    return {
      surveys: [],
      loading: false,
      keyword: '',
      statusFilter: '',
      // false = 走 api.survey.page（排除模板，默认视图）
      // true  = 走 api.survey.templates（含模板，用来管理模板身份）
      showTemplates: false,
      pageNum: 1,
      pageSize: 10,
      total: 0,
      dialogVisible: false,
      currentSurvey: {},
      currentQuestions: [],
      // ===== 设为/取消模板 =====
      templateDialogVisible: false,
      templateSubmitting: false,
      // title 只用于弹窗文案；isTemplate 决定是"设为"还是"取消"
      templateForm: { id: null, title: '', category: '', isTemplate: 0 },
      // 与 TemplateSeederService 的 7 大分类一致（那 31 个预置模板就是这么分的）；
      // 用 allow-create 允许管理员新建分类，前端不做白名单校验（后端也没有分类白名单）。
      templateCategoryOptions: ['满意度', '报名', '反馈', '调研', '教育', '健康', '其他'],
      Search: markRaw(Search),
      Refresh: markRaw(Refresh)
    }
  },
  mounted() {
    this.loadSurveys()
  },
  methods: {
    // ===== 分页加载全部问卷（管理员）=====
    // 关联：surveys 喂给表格；total 喂给 el-pagination；statusFilter / keyword 来自顶部筛选栏。
    // ★ showTemplates 为真时必须走 api.survey.templates()（模板列表）：
    //   api.survey.page 的 SQL 排除 is_template = 1，不切数据源就看不到模板、也就无法取消它。
    async loadSurveys() {
      this.loading = true
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
        const data = await api.survey.page({
          pageNum: this.pageNum,
          pageSize: this.pageSize,
          keyword: this.keyword.trim(),
          status: this.statusFilter
        })
        this.surveys = data.list || []
        this.total = data.total || 0
      } catch {
        // 错误提示已由 src/api/http.js 统一弹出，这里不再重复弹，只保留 loading 复位
      } finally {
        this.loading = false
      }
    },

    handleSearch() {
      this.pageNum = 1
      this.loadSurveys()
    },

    // ★ 模板列表接口不分页（一次给全部），前端本地分页；
    //   切换视图时把页码复位，否则在"全部问卷"翻到第 3 页再切"显示模板"会落到空页。
    handleTemplateViewChange() {
      this.pageNum = 1
      this.loadSurveys()
    },

    handleSizeChange(size) {
      this.pageSize = size
      this.pageNum = 1
      this.loadSurveys()
    },

    handlePageChange(page) {
      this.pageNum = page
      this.loadSurveys()
    },

    // ===== 查看问卷详情（弹窗里列出题目）=====
    // 关联：currentSurvey / currentQuestions 分别喂给 el-dialog 的标题和题目列表。
    async handleView(row) {
      this.currentSurvey = row
      this.dialogVisible = true
      try {
        this.currentQuestions = await api.question.list(row.id)
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

    // ===== 把违规问卷移入回收站 =====
    // 关联：成功后要重新拉列表（recycle 不改本地数组，必须重新 loadSurveys）。
    handleDelete(row) {
      ElMessageBox.confirm(`确定将问卷「${row.title}」移入违规问卷管理吗？`, '提示', {
        confirmButtonText: '移入违规管理',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          // ★ 失败由 http.js 按状态码弹错并 throw，所以页面按"能 await 成功就是成功"写即可。
          await api.survey.recycle(row.id)
          ElMessage.success('已移入违规问卷管理')
          this.loadSurveys()
        } catch {
          // HTTP 层失败（4xx/5xx、401、网络异常）的提示已由 http.js 统一弹出，这里不再重复弹
        }
      }).catch(() => {})
    }
  }
}
</script>