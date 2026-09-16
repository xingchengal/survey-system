<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h2 style="margin: 0;">📋 全部问卷</h2>
      <div style="display: flex; gap: 10px;">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px;" @change="handleSearch">
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

    <el-table :data="surveys" stripe border v-loading="loading" style="width: 100%;">
      <el-table-column prop="id" label="ID" width="70" align="center" />
      <el-table-column prop="title" label="问卷标题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.status === 1" type="success" size="small">已发布</el-tag>
          <el-tag v-else-if="row.status === 2" type="danger" size="small">已结束</el-tag>
          <el-tag v-else type="info" size="small">草稿</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdTime" label="创建时间" width="170" />
      <el-table-column label="操作" width="160" align="center" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="handleView(row)">查看</el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="display: flex; justify-content: flex-end; margin-top: 16px;">
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
      <p style="color: #666;">{{ currentSurvey.description }}</p>
      <el-divider />
      <div v-for="(q, idx) in currentQuestions" :key="q.id" style="margin-bottom: 16px;">
        <div style="font-weight: bold;">
          {{ idx + 1 }}. {{ q.title }}
          <el-tag size="small" style="margin-left: 8px;">
            {{ q.type === 1 ? '单选' : q.type === 2 ? '多选' : '填空' }}
          </el-tag>
        </div>
        <div v-if="q.options && q.options.length" style="padding-left: 20px; color: #666; margin-top: 6px;">
          <div v-for="opt in q.options" :key="opt.id">· {{ opt.content }}</div>
        </div>
      </div>
      <el-empty v-if="currentQuestions.length === 0" description="该问卷暂无题目" />
    </el-dialog>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'

export default {
  components: { Refresh, Search },
  data() {
    return {
      surveys: [],
      loading: false,
      keyword: '',
      statusFilter: '',
      pageNum: 1,
      pageSize: 10,
      total: 0,
      dialogVisible: false,
      currentSurvey: {},
      currentQuestions: [],
      Search: markRaw(Search),
      Refresh: markRaw(Refresh)
    }
  },
  mounted() {
    this.loadSurveys()
  },
  methods: {
    async loadSurveys() {
      this.loading = true
      try {
        const params = new URLSearchParams({
          pageNum: this.pageNum,
          pageSize: this.pageSize
        })
        if (this.keyword.trim()) params.append('keyword', this.keyword.trim())
        if (this.statusFilter !== '' && this.statusFilter !== null) {
          params.append('status', this.statusFilter)
        }

        const res = await fetch(`/api/survey/page?${params.toString()}`)
        const data = await res.json()
        this.surveys = data.list || []
        this.total = data.total || 0
      } catch {
        ElMessage.error('加载问卷失败')
      } finally {
        this.loading = false
      }
    },

    handleSearch() {
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

    async handleView(row) {
      this.currentSurvey = row
      this.dialogVisible = true
      try {
        const res = await fetch(`/api/question/list/${row.id}`)
        this.currentQuestions = await res.json()
      } catch {
        this.currentQuestions = []
      }
    },

    handleDelete(row) {
      ElMessageBox.confirm(`确定将问卷「${row.title}」移入违规问卷管理吗？`, '提示', {
        confirmButtonText: '移入违规管理',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          const res = await fetch(`/api/survey/recycle/${row.id}`, { method: 'PUT' })
          const msg = await res.text()
          if (msg === '已移入回收站') {
            ElMessage.success('已移入违规问卷管理')
            this.loadSurveys()
          } else {
            ElMessage.error(msg)
          }
        } catch {
          ElMessage.error('操作失败')
        }
      }).catch(() => {})
    }
  }
}
</script>