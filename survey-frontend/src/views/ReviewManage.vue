<!--
  举报审核（管理端）：只处理 PENDING。
  ★ 通过 = 只把 lifecycle 转 SUSPENDED（status 不变），通知作者与举报人；驳回 = 只通知举报人。
  ★ 前端不判断「能不能通过」：非 PENDING 按钮不出现，后端 WHERE status='PENDING' 再兜一次幂等。
  已知缺口：举报人不能自批 —— 后端当前未强制，具备 review:handle 的账号仍可处理自己的举报。
-->
<template>
  <div class="review-page">
    <div class="review-head">
      <h2 class="page-title">举报审核</h2>
      <div class="head-actions">
        <el-switch
          v-if="status === 'APPROVED'"
          v-model="showRestored"
          active-text="显示已恢复"
          size="small"
        />
        <span v-if="status === 'APPROVED' && hiddenCount > 0" class="muted">
          已隐藏 {{ hiddenCount }} 条已恢复
        </span>
        <el-select v-model="status" placeholder="全部" style="width: 140px;" @change="load">
          <el-option label="待处理" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="全部" value="" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-table :data="visibleRows" v-loading="loading" empty-text="没有举报记录">
      <el-table-column prop="surveyTitle" label="问卷" min-width="180">
        <template #default="{ row }">
          {{ row.surveyTitle || '（问卷已删）' }}<span class="id-badge">#{{ row.surveyId }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="reporterId" label="举报人" width="100" />
      <el-table-column prop="reason" label="理由" min-width="240" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'PENDING' ? 'warning' : (row.status === 'APPROVED' ? 'success' : 'info')" size="small">
            {{ { PENDING: '待处理', APPROVED: '已通过', REJECTED: '已驳回' }[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <!-- 已恢复的记录默认在「已通过」里隐藏；「恢复上架」只对仍 SUSPENDED 的记录显示，否则必 400 -->
      <el-table-column label="问卷状态" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.surveyLifecycle === 'SUSPENDED'" type="danger" size="small">已下架</el-tag>
          <el-tag v-else-if="row.surveyLifecycle" type="success" size="small">已恢复</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdTime" label="提交时间" width="170" />
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button link type="danger" @click="handle(row, 'SUSPEND')">通过并下架</el-button>
            <el-button link @click="handle(row, 'IGNORE')">驳回</el-button>
          </template>
          <template v-else>
            <el-button
              v-if="row.action === 'SUSPEND' && row.surveyLifecycle === 'SUSPENDED'"
              link type="success" @click="restore(row)"
            >恢复上架</el-button>
            <span v-else-if="row.action === 'SUSPEND'" class="muted">已恢复</span>
            <span v-else class="muted">{{ row.action || '—' }}</span>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <p class="review-hint">
      「通过并下架」只会把问卷的业务生命周期改成 SUSPENDED（已发布的 status 不变），
      填写页会立刻显示「该问卷已被下架」，作者与举报人都会收到通知。<br />
      「已通过」里默认只列出「仍在 下架」的问卷；恢复上架后记录自动隐藏（数据库仍保留，可开「显示已恢复」查看）。
    </p>
  </div>
</template>

<script>
import { ElMessage, ElMessageBox } from 'element-plus'
import reviewApi from '../api/review'

export default {
  name: 'ReviewManage',
  data() {
    return {
      rows: [],
      loading: false,
      status: 'PENDING',
      /** 「已通过」里是否显示已恢复的记录（默认关：日常只看仍在下架的） */
      showRestored: false
    }
  },
  computed: {
    /**
     * 「已通过」默认只列仍 SUSPENDED 的记录；开 showRestored 或切「全部」才展示全部。
     */
    visibleRows() {
      if (this.status !== 'APPROVED' || this.showRestored) return this.rows
      return this.rows.filter(r => r.surveyLifecycle === 'SUSPENDED')
    },
    hiddenCount() {
      if (this.status !== 'APPROVED' || this.showRestored) return 0
      return this.rows.filter(r => r.surveyLifecycle !== 'SUSPENDED').length
    }
  },
  mounted() {
    // 从「恢复上架申请」通知带 ?status=APPROVED 进来：只接受已知状态值，避免把下拉弄成空选中
    const q = this.$route.query.status
    if (typeof q === 'string' && ['PENDING', 'APPROVED', 'REJECTED'].includes(q)) {
      this.status = q
    }
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        this.rows = await reviewApi.list(this.status || null)
      } catch {
        this.rows = []
      } finally {
        this.loading = false
      }
    },
    /** 取消下架：只对"通过并下架"的那条有效（后端还会校验 lifecycle 是否 SUSPENDED） */
    async restore(row) {
      try {
        await ElMessageBox.confirm(`恢复后《${row.surveyTitle || '该问卷'}》会重新接收答卷。确定吗？`, '恢复上架', { type: 'warning' })
      } catch {
        return
      }
      await reviewApi.unsuspend(row.surveyId)
      ElMessage.success('已恢复上架')
      this.load()
    },

    async handle(row, action) {
      const tip = action === 'SUSPEND'
        ? `通过后《${row.surveyTitle || '该问卷'}》会被下架，填写页立即不能作答。确定吗？`
        : '确定驳回这条举报吗？'
      try {
        await ElMessageBox.confirm(tip, '处理举报', { type: 'warning' })
      } catch {
        return // 用户取消
      }
      await reviewApi.handle(row.id, action)
      ElMessage.success(action === 'SUSPEND' ? '已通过并下架' : '已驳回')
      this.load()
    }
  }
}
</script>

<style scoped>
.review-page { padding: 4px 0; }
.review-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.head-actions { display: flex; gap: 10px; }
.review-hint { margin-top: 14px; font-size: 12px; color: var(--ink-3); line-height: 1.7; }
.muted { color: var(--ink-3); font-size: 12px; }
</style>
