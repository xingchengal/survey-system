<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>我的成绩</h2>
        <p class="page-sub">
          这里只列**登录后提交**的答卷；匿名答卷没有账号可关联，不会出现在这里。
        </p>
      </div>
    </div>

    <el-card class="mb-16">
      <el-skeleton v-if="loading" :rows="5" animated />
      <el-table
        v-else
        :data="rows"
        stripe
        class="w-full"
        empty-text="还没有答过问卷（登录后提交的答卷才会出现在这里）"
      >
        <el-table-column prop="surveyTitle" label="问卷" min-width="220" show-overflow-tooltip />
        <el-table-column prop="submitTime" label="提交时间" width="180" />
        <el-table-column label="得分" width="140" align="center">
          <template #default="{ row }">
            <span v-if="row.earnedScore == null" class="muted">未判分</span>
            <span v-else class="score"><b>{{ row.earnedScore }}</b> / {{ row.totalScore }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isPassed === 1" type="success" size="small" round>及格</el-tag>
            <el-tag v-else-if="row.isPassed === 0" type="danger" size="small" round>未及格</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="问卷状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.deleted === 1" type="info" size="small">已删除</el-tag>
            <el-tag v-else-if="row.status === 2" type="warning" size="small">已结束</el-tag>
            <el-tag v-else-if="row.status === 1" type="success" size="small">进行中</el-tag>
            <el-tag v-else type="info" size="small">草稿</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" align="center">
          <template #default="{ row }">
            <el-button v-if="canOpen(row)" type="primary" link size="small" @click="openSurvey(row)">
              打开问卷
            </el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && total > 0" class="pager">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="total"
          :current-page="pageNum"
          :page-size="pageSize"
          @current-change="onPageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script>
import api from '../api'

/**
 * 我的成绩 / 答题记录。
 *
 * ── 为什么只有"登录态提交"的记录 ──────────────────────
 *   t_answer.user_id 只在 POST /answer/submit/{surveyId} 这条路径上写入；
 *   网页上的匿名问卷走 submit-share（userId 恒为 null），没有身份可回溯，
 *   所以匿名答卷**不会**出现在这里 —— 这是产品口径，不是漏查。
 *
 * ── 为什么还要显示"已删除/已结束" ────────────────────
 *   历史记录不该因为作者删了问卷就凭空消失；只是不能再打开（前端禁用入口）。
 */
export default {
  data() {
    return {
      loading: true,
      rows: [],
      total: 0,
      pageNum: 1,
      pageSize: 10
    }
  },
  mounted() {
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        const data = await api.answer.my(this.pageNum, this.pageSize)
        this.rows = data.list || []
        this.total = data.total || 0
        // ★ 后端会把页码夹在 [1,10000]；如果当前页已超出（比如删了数据），回退到第 1 页
        if (this.rows.length === 0 && this.total > 0 && this.pageNum > 1) {
          this.pageNum = 1
          await this.load()
        }
      } catch {
        // 失败提示由 http.js 统一弹出，这里保持页面即可
      } finally {
        this.loading = false
      }
    },
    onPageChange(page) {
      this.pageNum = page
      this.load()
    },
    /** 还能不能打开：未删除、进行中、且有分享码 */
    canOpen(row) {
      return row.deleted !== 1 && row.status === 1 && !!row.shareCode
    },
    openSurvey(row) {
      window.open(`/s/${row.shareCode}`, '_blank')
    }
  }
}
</script>

<style scoped>
.page {
  padding: 4px 2px;
}
.page-head h2 {
  margin: 0 0 6px;
  font-size: 20px;
}
.page-sub {
  margin: 0 0 16px;
  color: var(--ink-3, #64748b);
  font-size: 13px;
}
.muted {
  color: var(--ink-3, #94a3b8);
}
.score b {
  color: var(--sky-600, #0284c7);
  font-size: 16px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
