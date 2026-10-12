<template>
  <div>
    <div class="page-header">
      <h2 class="m-0">{{ isAdmin ? '🚫 违规问卷管理' : '🗑️ 回收站' }}</h2>
      <div class="flex-gap-10">
        <el-input v-model="keyword" placeholder="搜索问卷标题..." clearable :prefix-icon="Search" style="width: 260px;" @keyup.enter="handleSearch" @clear="handleSearch" />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="loadRecycle">刷新</el-button>
      </div>
    </div>

    <el-alert class="mb-16" :title="isAdmin ? '这里是所有被下架的问卷，可恢复或彻底删除' : '回收站里的问卷不会出现在正常列表，可随时恢复或彻底删除'" type="info" :closable="false" />

    <el-table class="w-full" :data="surveys" stripe border v-loading="loading">
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

    <el-empty v-if="surveys.length === 0 && !loading" :description="isAdmin ? '暂无违规问卷' : '回收站是空的'" />
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getUser } from '../utils/auth'
// Refresh / Search 用于属性变量形式（:icon="Refresh"、:prefix-icon="Search"），必须 import
import { Refresh, Search } from '@element-plus/icons-vue'
// ★ 统一接口层（src/api）：分页 / 恢复 / 彻底删除都走它 ——
//   token、query 拼接、错误提示、401 跳登录都不用本页再管
import api from '../api'

// 图标已在 main.js 里全局注册，这里不再重复注册
// （模板标签走全局注册；:prefix-icon="Search" 这种属性变量走本文件顶部的 import，两者都不受影响）
export default {
  data() {
    return {
      surveys: [],
      loading: false,
      keyword: '',
      pageNum: 1,
      pageSize: 10,
      total: 0,
      // ★ 读本地用户走 utils/auth.getUser()（data() 里的裸 parse 是白屏隐患）
      currentUser: getUser() || {},
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
        // 参数对象直接给接口层（空关键字会被 toQuery 自动跳过）
        const data = await api.survey.recyclePage({
          pageNum: this.pageNum,
          pageSize: this.pageSize,
          keyword: this.keyword.trim()
        })
        this.surveys = data.list || []
        this.total = data.total || 0
      } catch {
        // ★ 接口层已统一提示，这里只负责流程控制，loading 交给 finally 复位
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
          // 恢复接口是 PUT，成功才 resolve
          await api.survey.restore(row.id)
          ElMessage.success('恢复成功')
          this.loadRecycle()
        } catch {
          // 失败提示由接口层统一弹出，这里不再重复提示
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
          // 彻底删除是 DELETE，非 2xx 抛错（后端要求问卷已在回收站，否则 400）
          await api.survey.permanent(row.id)
          ElMessage.success('已彻底删除')
          this.loadRecycle()
        } catch {
          // 失败提示由接口层统一弹出（含后端返回的 400 具体原因），这里只做流程控制
        }
      }).catch(() => {})
    }
  }
}
</script>