<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="page-title">公告管理</h2>
        <p class="page-subtitle">发布、下架与删除站内公告</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" :icon="Plus" @click="openDialog">发布公告</el-button>
      </div>
    </div>

    <div class="table-card">
    <PageState
      :loading="loading"
      :error="error"
      :empty="list.length === 0"
      empty-text="暂无公告，点右上角「发布公告」创建一条"
      @retry="loadList"
    >
    <el-table class="w-full" :data="list" stripe border>
      <el-table-column prop="id" label="ID" width="70" align="center" />
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.status === 1" type="success" size="small">已发布</el-tag>
          <el-tag v-else type="info" size="small">已下架</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="expireTime" label="过期时间" width="170">
        <template #default="{ row }">
          {{ row.expireTime || '永不过期' }}
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="发布时间" width="170" />
      <el-table-column label="操作" width="180" align="center" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 1"
            type="warning"
            link
            size="small"
            @click="handleToggle(row, 0)"
          >下架</el-button>
          <el-button
            v-else
            type="success"
            link
            size="small"
            @click="handleToggle(row, 1)"
          >上架</el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    </PageState>
    </div>

    <!-- 发布对话框 -->
    <el-dialog v-model="dialogVisible" title="发布公告" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="请输入公告标题" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="6" placeholder="请输入公告内容" maxlength="16000" show-word-limit />
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker class="w-full" v-model="form.expireTime" type="datetime" placeholder="不设置则永不过期" value-format="YYYY-MM-DD HH:mm:ss" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// Plus 用于属性变量形式（:icon="Plus"），必须 import（模板标签形式才可由全局注册提供）
import { Plus } from '@element-plus/icons-vue'
// ★ 统一接口层（src/api）：Authorization、query 拼接、非 2xx 抛错、错误提示、401 跳登录都在里面，
//   本页只需关心「调哪个接口、拿到什么数据」
import api from '../api'
// 三态容器：骨架 / 空 / 错误（带重试）
import PageState from '../components/PageState.vue'

// 图标已在 main.js 全局注册；本页只有 Plus 需要按"属性变量"形式用（:icon="Plus"），所以单独 import
export default {
  components: { PageState },
  data() {
    return {
      list: [],
      loading: false,
      // 接口失败标记：由 PageState 渲染「加载失败 + 重试」
      error: false,
      dialogVisible: false,
      form: { title: '', content: '', expireTime: null },
      // ★ 关键：模板里用 :icon="Plus" 就要在 data 里返回
      Plus: markRaw(Plus)
    }
  },
  mounted() {
    this.loadList()
  },
  methods: {
    async loadList() {
      this.loading = true
      this.error = false
      try {
        // 接口层已带登录凭证且会按 HTTP 状态码判成败，成功直接 resolve 响应体（数组）
        this.list = await api.announcement.list()
      } catch {
        // ★ 接口层已统一 ElMessage.error，这里置 error 交 PageState 显示重试入口
        this.error = true
      } finally {
        this.loading = false
      }
    },

    openDialog() {
      this.form = { title: '', content: '', expireTime: null }
      this.dialogVisible = true
    },

    async handleSubmit() {
      if (!this.form.title.trim()) return ElMessage.warning('请输入标题')
      if (!this.form.content.trim()) return ElMessage.warning('请输入内容')
      try {
        // POST 与序列化由接口层负责，非 2xx 直接 throw ——「await 成功即成功」
        await api.announcement.add(this.form)
        ElMessage.success('发布成功')
        this.dialogVisible = false
        this.loadList()
      } catch {
        // ★ 失败必定走 throw，提示已由接口层统一弹出，这里只做流程控制（不关弹窗、不刷新列表）
      }
    },

    async handleToggle(row, status) {
      try {
        // updateStatus 内部就是 PUT /api/announcement/status/{id}?status=，成功才 resolve ——
        // ★ 不要退回"读 text 比对 msg"的写法，否则后端改文案前端就静默失效
        await api.announcement.updateStatus(row.id, status)
        ElMessage.success('操作成功')
        this.loadList()
      } catch {
        // 失败提示已由接口层统一处理，这里不再重复弹窗
      }
    },

    handleDelete(row) {
      ElMessageBox.confirm(`确定删除公告「${row.title}」吗？`, '警告', {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          // 删除是 DELETE，非 2xx 一律抛错，await 通过就说明删掉了
          await api.announcement.remove(row.id)
          ElMessage.success('删除成功')
          this.loadList()
        } catch {
          // 失败提示由接口层统一弹出；保留 catch 是为了不打断 Promise 链
          // （外层 .catch(() => {}) 负责吞掉「取消」分支，两者作用不同）
        }
      }).catch(() => {})
    }
  }
}
</script>