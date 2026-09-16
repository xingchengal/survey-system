<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h2 style="margin: 0;">{{ isAdmin ? '🚫 违规问卷管理' : '🗑️ 回收站' }}</h2>
      <div style="display: flex; gap: 10px;">
        <el-input v-model="keyword" placeholder="搜索问卷标题..." clearable :prefix-icon="Search" style="width: 260px;" @keyup.enter="handleSearch" @clear="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="loadRecycle">刷新</el-button>
      </div>
    </div>

    <el-alert
      :title="isAdmin ? '这里是所有被下架的问卷，可恢复或彻底删除' : '回收站里的问卷不会出现在正常列表，可随时恢复或彻底删除'"
      type="info"
      :closable="false"
      style="margin-bottom: 16px;"
    />

    <el-table :data="surveys" stripe border v-loading="loading" style="width: 100%;">
      <el-table-column prop="id" label="ID" width="70" align="center" />
      <el-table-column prop="title" label="问卷标题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
      <el-table-column prop="status" label="原状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.status === 1" type="success" size="small">已发布</el-tag>
          <el-tag v-else-if="row.status === 2" type="danger" size="small">已结束</el-tag>
          <el-tag v-else type="info" size="small">草稿</el-tag>
        </template>
      </el-table-column>

      <el-table-column v-if="isAdmin" label="删除人" width="130" align="center">
        <template #default="{ row }">
          <el-tag type="warning" size="small">{{ row.deletedByName || '未知' }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column prop="createdTime" label="创建时间" width="170" />
      <el-table-column label="操作" width="200" align="center" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="handleRestore(row)">恢复</el-button>
          <el-button type="danger" link size="small" @click="handlePermanentDelete(row)">彻底删除</el-button>
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

    <el-empty v-if="surveys.length === 0 && !loading" :description="isAdmin ? '暂无违规问卷' : '回收站是空的'" />
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
      pageNum: 1,
      pageSize: 10,
      total: 0,
      currentUser: JSON.parse(localStorage.getItem('user') || '{}'),
      Search: markRaw(Search),
      Refresh: markRaw(Refresh)
    }
  },
  computed: {
    isAdmin() {
      return this.currentUser.role === 'admin'
    }
  },
  mounted() {
    this.loadRecycle()
  },
  methods: {
    async loadRecycle() {
      this.loading = true
      try {
        const params = new URLSearchParams({
          pageNum: this.pageNum,
          pageSize: this.pageSize
        })
        if (this.keyword.trim()) params.append('keyword', this.keyword.trim())

        const res = await fetch(`/api/survey/recycle/page?${params.toString()}`)
        const data = await res.json()
        this.surveys = data.list || []
        this.total = data.total || 0
      } catch {
        ElMessage.error('加载失败')
      } finally {
        this.loading = false
      }
    },

    handleSearch() {
      this.pageNum = 1
      this.loadRecycle()
    },

    handleSizeChange(size) {
      this.pageSize = size
      this.pageNum = 1
      this.loadRecycle()
    },

    handlePageChange(page) {
      this.pageNum = page
      this.loadRecycle()
    },

    handleRestore(row) {
      ElMessageBox.confirm(`确定恢复「${row.title}」吗？`, '提示', {
        confirmButtonText: '确定恢复',
        cancelButtonText: '取消',
        type: 'info'
      }).then(async () => {
        try {
          const res = await fetch(`/api/survey/restore/${row.id}`, { method: 'PUT' })
          const msg = await res.text()
          if (msg === '已恢复') {
            ElMessage.success('恢复成功')
            this.loadRecycle()
          } else {
            ElMessage.error(msg)
          }
        } catch {
          ElMessage.error('恢复失败')
        }
      }).catch(() => {})
    },

    handlePermanentDelete(row) {
      const msg = this.isAdmin
        ? `⚠️ 确定彻底删除违规问卷「${row.title}」吗？所有相关答卷也会一并删除！`
        : `确定彻底删除「${row.title}」吗？此操作不可恢复！`
      ElMessageBox.confirm(msg, '警告', {
        confirmButtonText: '确定彻底删除',
        cancelButtonText: '取消',
        type: 'error'
      }).then(async () => {
        try {
          const res = await fetch(`/api/survey/permanent/${row.id}`, { method: 'DELETE' })
          const text = await res.text()
          if (text === '已彻底删除') {
            ElMessage.success('已彻底删除')
            this.loadRecycle()
          } else {
            ElMessage.error(text)
          }
        } catch {
          ElMessage.error('删除失败')
        }
      }).catch(() => {})
    }
  }
}
</script>