<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>我的答卷</h2>
        <p class="page-sub">
          这里收录你登录后提交的全部答卷（含成绩与逐题详情）；匿名答卷无法关联账号，不会出现在这里。
        </p>
      </div>
    </div>

    <el-card class="mb-16">
      <!-- 筛选：标题关键词 + 提交日期（闭区间），改条件后回到第 1 页 -->
      <div class="filter-bar">
        <el-input
          v-model="filters.keyword"
          placeholder="按问卷标题搜索"
          clearable
          style="width: 220px;"
          @keyup.enter="search"
          @clear="search"
        />
        <el-date-picker
          v-model="filters.range"
          type="daterange"
          value-format="yyyy-MM-dd"
          range-separator="至"
          start-placeholder="提交开始"
          end-placeholder="提交结束"
          style="width: 260px;"
          @change="search"
        />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <el-skeleton v-if="loading" :rows="5" animated />
      <el-table
        v-else
        :data="rows"
        stripe
        class="w-full"
        empty-text="还没有提交过答卷（登录后提交的答卷才会出现在这里）"
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
        <el-table-column label="操作" width="180" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openDetail(row)">查看详情</el-button>
            <el-button v-if="canOpen(row)" type="primary" link size="small" @click="openSurvey(row)">
              打开问卷
            </el-button>
            <span v-else-if="!canOpen(row)" class="muted">—</span>
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

    <!-- 答卷详情：逐题回放（对错来自 t_answer_detail.is_correct；正确答案只有作者开启时才给） -->
    <el-dialog v-model="detailVisible" title="答卷详情" width="720px">
      <div v-if="detailLoading" class="detail-loading">
        <el-skeleton :rows="6" animated />
      </div>
      <template v-else-if="detail">
        <div class="detail-head">
          <span class="detail-survey">{{ detail.surveyTitle }}</span>
          <span class="detail-time">{{ detail.submitTime }}</span>
        </div>
        <div class="detail-score">
          <template v-if="detail.earnedScore != null">
            得分 <b>{{ detail.earnedScore }}</b> / {{ detail.totalScore }}
            <el-tag v-if="detail.passed === 1" type="success" size="small" round>及格</el-tag>
            <el-tag v-else-if="detail.passed === 0" type="danger" size="small" round>未及格</el-tag>
            <span v-if="detail.passScore != null" class="muted">（及格线 {{ detail.passScore }}）</span>
          </template>
          <span v-else class="muted">这份答卷未判分</span>
        </div>
        <div class="detail-list">
          <div v-for="(q, i) in detail.questions" :key="q.questionId" class="detail-item">
            <div class="detail-q">
              <span class="detail-idx">{{ i + 1 }}</span>
              <span class="detail-title">{{ q.title }}</span>
              <el-tag v-if="q.correct === true" type="success" size="small">答对</el-tag>
              <el-tag v-else-if="q.correct === false" type="danger" size="small">答错</el-tag>
            </div>
            <div class="detail-a" :class="{ muted: q.answer === '未作答' }">
              我的答案：{{ q.answer }}
            </div>
            <div v-if="q.correctAnswers && q.correctAnswers.length" class="detail-correct">
              正确答案：{{ q.correctAnswers.join('、') }}
            </div>
          </div>
        </div>
      </template>
      <el-empty v-else description="详情加载失败" :image-size="70" />
    </el-dialog>
  </div>
</template>

<script>
import api from '../api'

/**
 * 我的答卷：只查当前登录用户（后端按登录态取 user_id），分页加载。
 * ★ 匿名答卷走 submit-share，userId 恒为 null，无法关联账号，不会出现在这里 —— 产品口径，不是漏查。
 * 已删除/已结束的问卷仍保留记录，只是入口禁用（canOpen 只放行未删除、进行中、有分享码）。
 */
export default {
  data() {
    return {
      loading: true,
      rows: [],
      total: 0,
      pageNum: 1,
      pageSize: 10,
      /** 筛选条件：关键词 + 日期范围（value-format=yyyy-MM-dd） */
      filters: { keyword: '', range: [] },
      /** 请求序号：日期控件 change 与重置会连续发请求，慢响应不能覆盖新结果 */
      loadSeq: 0,
      detailVisible: false,
      detailLoading: false,
      detail: null
    }
  },
  mounted() {
    this.load()
  },
  methods: {
    async load() {
      const seq = ++this.loadSeq
      this.loading = true
      try {
        const range = this.filters.range || []
        const data = await api.answer.my(this.pageNum, this.pageSize, {
          keyword: this.filters.keyword.trim(),
          startDate: range[0] || undefined,
          endDate: range[1] || undefined
        })
        if (seq !== this.loadSeq) return
        this.rows = data.list || []
        this.total = data.total || 0
        // ★ 后端把页码夹在 [1,10000]；当前页超界（数据被删）时回退第 1 页，否则会停在空页
        if (this.rows.length === 0 && this.total > 0 && this.pageNum > 1) {
          this.pageNum = 1
          await this.load()
        }
      } catch {
        // 失败提示由 http.js 统一弹出；保持当前列表即可
      } finally {
        if (seq === this.loadSeq) this.loading = false
      }
    },
    search() {
      this.pageNum = 1
      this.load()
    },
    reset() {
      this.filters = { keyword: '', range: [] }
      this.pageNum = 1
      this.load()
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
    },
    /** 查看逐题详情（对错 + 我的答案；正确答案由后端按作者设置决定给不给） */
    async openDetail(row) {
      this.detailVisible = true
      this.detailLoading = true
      this.detail = null
      try {
        this.detail = await api.answer.myDetail(row.answerId)
      } catch {
        // 失败提示由 http.js 统一弹出；弹窗里显示空态即可
      } finally {
        this.detailLoading = false
      }
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
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
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

/* 答卷详情弹窗 */
.detail-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line-1, #eef2f7);
}
.detail-survey {
  font-weight: 600;
}
.detail-time {
  font-size: 12px;
  color: var(--ink-3, #94a3b8);
}
.detail-score {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0;
  font-size: 14px;
}
.detail-score b {
  color: var(--sky-600, #0284c7);
  font-size: 20px;
}
.detail-list {
  max-height: 420px;
  overflow-y: auto;
  padding-right: 4px;
}
.detail-item {
  padding: 10px 0;
  border-bottom: 1px dashed var(--line-1, #f1f5f9);
}
.detail-item:last-child {
  border-bottom: none;
}
.detail-q {
  display: flex;
  align-items: center;
  gap: 8px;
}
.detail-idx {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--sky-50, #eff6ff);
  color: var(--sky-600, #0284c7);
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.detail-title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
}
.detail-a {
  margin: 6px 0 0 30px;
  font-size: 13px;
  color: var(--ink-2, #475569);
  white-space: pre-wrap;
  word-break: break-word;
}
.detail-correct {
  margin: 4px 0 0 30px;
  font-size: 12px;
  color: #16a34a;
}
.detail-loading {
  padding: 6px 2px;
}
</style>
