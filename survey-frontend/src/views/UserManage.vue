<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h2 style="margin: 0;">👥 用户管理</h2>
      <div style="display: flex; gap: 10px;">
        <el-input
          v-model="keyword"
          placeholder="搜索用户名或昵称..."
          clearable
          :prefix-icon="Search"
          style="width: 260px;"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="loadUsers">刷新</el-button>
      </div>
    </div>

    <el-table :data="users" stripe border v-loading="loading" style="width: 100%;">
      <el-table-column prop="id" label="ID" width="80" align="center" />
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column prop="nickname" label="昵称" min-width="140" />
      <el-table-column prop="role" label="角色" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.role === 'admin' ? 'danger' : 'info'">
            {{ row.role === 'admin' ? '管理员' : '普通用户' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdTime" label="注册时间" width="180" />
      <el-table-column label="操作" width="120" align="center">
        <template #default="{ row }">
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

    <el-empty v-if="users.length === 0 && !loading" description="暂无用户" />
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
      users: [],
      loading: false,
      keyword: '',
      pageNum: 1,
      pageSize: 10,
      total: 0,
      Search: markRaw(Search),
      Refresh: markRaw(Refresh)
    }
  },
  mounted() {
    this.loadUsers()
  },
  methods: {
    async loadUsers() {
      this.loading = true
      try {
        const params = new URLSearchParams({
          pageNum: this.pageNum,
          pageSize: this.pageSize
        })
        if (this.keyword.trim()) params.append('keyword', this.keyword.trim())
        const res = await fetch(`/api/user/page?${params.toString()}`)
        const data = await res.json()
        this.users = data.list || []
        this.total = data.total || 0
      } catch {
        ElMessage.error('加载用户列表失败')
      } finally {
        this.loading = false
      }
    },

    handleSearch() {
      this.pageNum = 1
      this.loadUsers()
    },

    handleSizeChange(size) {
      this.pageSize = size
      this.pageNum = 1
      this.loadUsers()
    },

    handlePageChange(page) {
      this.pageNum = page
      this.loadUsers()
    },

    handleDelete(row) {
      ElMessageBox.confirm(`确定删除用户「${row.username}」吗？该操作不可恢复！`, '警告', {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          const res = await fetch(`/api/user/delete/${row.id}`, { method: 'DELETE' })
          const msg = await res.text()
          if (msg === '删除成功') {
            ElMessage.success('删除成功')
            this.loadUsers()
          } else {
            ElMessage.error(msg)
          }
        } catch {
          ElMessage.error('删除失败')
        }
      }).catch(() => {})
    }
  }
}
</script>