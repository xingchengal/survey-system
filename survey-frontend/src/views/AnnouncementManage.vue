<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h2 style="margin: 0;">📢 公告管理</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog">发布公告</el-button>
    </div>

    <el-table :data="list" stripe border style="width: 100%;">
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

    <el-empty v-if="list.length === 0" description="暂无公告" />

    <!-- 发布对话框 -->
    <el-dialog v-model="dialogVisible" title="发布公告" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="请输入公告标题" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="6" placeholder="请输入公告内容" maxlength="1000" show-word-limit />
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker
            v-model="form.expireTime"
            type="datetime"
            placeholder="不设置则永不过期"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%;"
          />
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
import { Plus } from '@element-plus/icons-vue'

export default {
  components: { Plus },
  data() {
    return {
      list: [],
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
      try {
        const res = await fetch('/api/announcement/list')
        this.list = await res.json()
      } catch {
        ElMessage.error('加载失败')
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
        const res = await fetch('/api/announcement/add', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.form)
        })
        const data = await res.json()
        if (data.success) {
          ElMessage.success('发布成功')
          this.dialogVisible = false
          this.loadList()
        } else {
          ElMessage.error(data.message)
        }
      } catch {
        ElMessage.error('发布失败')
      }
    },

    async handleToggle(row, status) {
      try {
        const res = await fetch(`/api/announcement/status/${row.id}?status=${status}`, { method: 'PUT' })
        const msg = await res.text()
        if (msg === '操作成功') {
          ElMessage.success('操作成功')
          this.loadList()
        } else {
          ElMessage.error(msg)
        }
      } catch {
        ElMessage.error('操作失败')
      }
    },

    handleDelete(row) {
      ElMessageBox.confirm(`确定删除公告「${row.title}」吗？`, '警告', {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          const res = await fetch(`/api/announcement/delete/${row.id}`, { method: 'DELETE' })
          const msg = await res.text()
          if (msg === '删除成功') {
            ElMessage.success('删除成功')
            this.loadList()
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