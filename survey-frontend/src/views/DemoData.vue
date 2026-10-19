<template>
  <div class="demo-page">
    <el-card>
      <template #header>
        <div class="flex-center">
          <el-icon style="color: #e6a23c; margin-right: 8px; font-size: 20px;"><Warning /></el-icon>
          <span style="font-weight: bold; font-size: 16px;">演示数据管理</span>
          <el-tag type="warning" size="small" style="margin-left: 12px;">测试专用</el-tag>
        </div>
      </template>

      <el-alert class="mb-20" type="warning" :closable="false">
        <template #title><strong>⚠️ 请谨慎操作</strong></template>
        此功能用于系统演示和答辩准备。<br>
        「生成」按钮会<strong>追加</strong>新数据；<br>
        「重置」按钮会<strong>清空所有演示数据</strong>。
      </el-alert>

      <div class="demo-info-box">
        <h4 style="margin: 0 0 12px 0;">📦 每次生成将添加：</h4>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="测试用户">20 个（demo_userN，密码 123456）</el-descriptions-item>
          <el-descriptions-item label="问卷数据">30 份（单选/多选/填空）</el-descriptions-item>
          <el-descriptions-item label="答卷数据">每份已发布问卷 3~8 份答卷</el-descriptions-item>
        </el-descriptions>
      </div>

      <div style="display: flex; gap: 16px; justify-content: center; padding: 20px 0;">
        <el-button type="primary" size="large" :loading="loading" :icon="MagicStick" @click="handleInit">
          {{ loading ? '正在生成...' : '生成演示数据' }}
        </el-button>
        <el-button type="danger" size="large" :loading="resetting" :icon="Delete" @click="handleReset">
          {{ resetting ? '正在清空...' : '重置演示数据' }}
        </el-button>
      </div>

      <el-card v-if="initResult" shadow="never" class="demo-result">
        <div style="display: flex; align-items: center; margin-bottom: 12px;">
          <el-icon style="color: #67c23a; font-size: 20px; margin-right: 8px;"><SuccessFilled /></el-icon>
          <span style="font-weight: bold; color: #67c23a;">{{ initResult.message }}</span>
        </div>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="批次">第 {{ initResult.batchNo }} 批</el-descriptions-item>
          <el-descriptions-item label="用户">{{ initResult.userCount }}</el-descriptions-item>
          <el-descriptions-item label="问卷">{{ initResult.surveyCount }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card v-if="resetResult" shadow="never" style="margin-top: 20px; background: #fef0f0; border-color: #f56c6c;">
        <div style="display: flex; align-items: center; margin-bottom: 12px;">
          <el-icon style="color: #f56c6c; font-size: 20px; margin-right: 8px;"><CircleCloseFilled /></el-icon>
          <span style="font-weight: bold; color: #f56c6c;">{{ resetResult.message }}</span>
        </div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="删除用户">{{ resetResult.deletedUsers }}</el-descriptions-item>
          <el-descriptions-item label="删除问卷">{{ resetResult.deletedSurveys }}</el-descriptions-item>
        </el-descriptions>
      </el-card>
    </el-card>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
// MagicStick / Delete 用于属性变量（:icon="..."），必须 import；标签形式由 main.js 全局注册
import { MagicStick, Delete } from '@element-plus/icons-vue'

// ★ 仅非 prod 可用（后端 @Profile("!prod")）；生成=追加下一批，重置=清空全部演示数据
export default {
  data() {
    return {
      loading: false,
      resetting: false,
      initResult: null,
      resetResult: null,
      MagicStick: markRaw(MagicStick),
      Delete: markRaw(Delete)
    }
  },
  methods: {
    // 生成 = 追加下一批（非幂等）；页面提示必须说明是「追加」，避免被当成重置
    handleInit() {
      ElMessageBox.confirm('将追加一批新演示数据，确定继续吗？', '确认生成', {
        confirmButtonText: '确定生成',
        cancelButtonText: '取消',
        type: 'info'
      }).then(async () => {
        this.loading = true
        this.initResult = null
        try {
          this.initResult = await api.demo.init()
          ElMessage.success('演示数据生成成功！')
        } catch {
          // 错误提示由 http.js 统一弹出，这里只复位 loading
        } finally {
          this.loading = false
        }
      }).catch(() => {})
    },

    // ★ 重置 = 清空全部演示数据且不可恢复，必须两次确认；后端按 demo_user 前缀回收，不碰真实数据
    handleReset() {
      ElMessageBox.confirm('⚠️ 此操作将彻底删除所有演示数据！确定继续吗？', '⚠️ 危险操作确认', {
        confirmButtonText: '确定清空',
        cancelButtonText: '取消',
        type: 'error'
      }).then(async () => {
        try {
          await ElMessageBox.confirm('再次确认：所有演示数据将被永久删除，不可恢复！', '最后确认', {
            confirmButtonText: '我确定，清空',
            cancelButtonText: '取消',
            type: 'error'
          })
        } catch {
          return
        }

        this.resetting = true
        this.resetResult = null
        try {
          this.resetResult = await api.demo.reset()
          ElMessage.success('演示数据已清空')
        } catch {
          // 错误提示已由 http.js 弹出，这里只做流程控制
        } finally {
          this.resetting = false
        }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.demo-page {
  max-width: 800px;
  margin: 0 auto;
}
.demo-info-box {
  padding: 16px;
  margin-bottom: 16px;
  background: #fafbfc;
  border: 1px solid var(--stroke);
  border-radius: var(--r-md);
}
.demo-result {
  margin-top: 20px;
  background: #f0f9eb;
  border-color: #67c23a;
}
html[data-theme="dark"] .demo-info-box {
  background: #13223a;
}
html[data-theme="dark"] .demo-result {
  background: #102a21;
  border-color: #1f5c3d;
}
</style>
