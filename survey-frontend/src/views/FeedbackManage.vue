<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">反馈管理</h2>
        <p class="page-subtitle">
          意见反馈按处理状态跟进；客服工单按响应时效跟进。只有被授予「反馈 / 客服工单处理」的次级管理员才能进入。
        </p>
      </div>
      <el-tag v-if="unreadTotal > 0" type="danger" effect="light">未读 {{ unreadTotal }}</el-tag>
    </div>

    <el-card>
      <template #header>
        <div class="list-head">
          <el-tabs v-model="activeType" class="type-tabs" @tab-change="reload">
            <el-tab-pane label="意见反馈" name="FEEDBACK" />
            <el-tab-pane label="客服工单" name="SERVICE" />
          </el-tabs>
          <div class="list-controls">
            <el-select v-model="status" style="width: 120px;" @change="reload">
              <el-option label="全部状态" value="" />
              <el-option label="处理中" value="OPEN" />
              <el-option label="已关闭" value="CLOSED" />
            </el-select>
            <el-button :loading="loading" @click="loadList">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table
        v-if="activeType === 'FEEDBACK'"
        v-loading="loading"
        :data="rows"
        stripe
        @row-click="openTicket"
      >
        <el-table-column label="" width="42" align="center">
          <template #default="{ row }">
            <span class="unread-dot" :class="{ on: row.unread === 1 }"></span>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="主题" min-width="220" show-overflow-tooltip />
        <el-table-column label="发起人" width="150">
          <template #default="{ row }">{{ row.nickname || row.username }}</template>
        </el-table-column>
        <el-table-column label="处理状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'OPEN' ? 'success' : 'info'" effect="plain">
              {{ row.status === 'OPEN' ? '待处理' : '已处理' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处理人" width="120">
          <template #default="{ row }">{{ row.handledByName || '未分配' }}</template>
        </el-table-column>
        <el-table-column prop="lastMessagePreview" label="最新消息" min-width="200" show-overflow-tooltip />
        <el-table-column prop="lastMessageTime" label="更新时间" width="160" />
        <el-table-column label="操作" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openTicket(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-table
        v-else
        v-loading="loading"
        :data="rows"
        stripe
        @row-click="openTicket"
      >
        <el-table-column label="" width="42" align="center">
          <template #default="{ row }">
            <span class="unread-dot" :class="{ on: row.unread === 1 }"></span>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="主题" min-width="220" show-overflow-tooltip />
        <el-table-column label="发起人" width="150">
          <template #default="{ row }">{{ row.nickname || row.username }}</template>
        </el-table-column>
        <el-table-column label="响应状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="responseTagType(row)" effect="plain">
              {{ responseText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="等待时长" width="120" align="center">
          <template #default="{ row }">{{ waitingText(row) }}</template>
        </el-table-column>
        <el-table-column label="处理人" width="120">
          <template #default="{ row }">{{ row.handledByName || '未分配' }}</template>
        </el-table-column>
        <el-table-column prop="lastMessagePreview" label="最近消息" min-width="200" show-overflow-tooltip />
        <el-table-column prop="lastMessageTime" label="最近更新" width="160" />
        <el-table-column label="操作" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openTicket(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager-row">
        <el-pagination
          :current-page="pageNum"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          background
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>

    <feedback-thread
      v-model="drawerVisible"
      :ticket-id="activeTicket ? activeTicket.id : 0"
      :initial-ticket="activeTicket"
      :is-admin="true"
      :peer-name="peerName"
      :variant="activeType === 'SERVICE' ? 'service' : 'feedback'"
      :poll-interval="activeType === 'SERVICE' ? 5000 : 0"
      :loader="loadThread"
      :sender="replyThread"
      :closer="closeThread"
      @updated="handleThreadUpdated"
      @closed="handleThreadUpdated"
    />
  </div>
</template>

<script>
// ★ 管理端反馈 / 客服：后端每个端点都挂 @RequirePermission(feedback:handle)。
//   详情线程复用 components/FeedbackThread.vue（5 秒轮询、固定 composer、分组排版）。
import FeedbackThread from '../components/FeedbackThread.vue'
import { feedback as feedbackApi } from '../api'

export default {
  components: { FeedbackThread },
  data() {
    return {
      activeType: 'FEEDBACK',
      status: '',
      loading: false,
      rows: [],
      total: 0,
      pageNum: 1,
      pageSize: 10,
      unreadTotal: 0,
      loadSeq: 0,
      drawerVisible: false,
      activeTicket: null
    }
  },
  computed: {
    peerName() {
      const row = this.activeTicket
      return row ? (row.nickname || row.username || '用户') : ''
    }
  },
  mounted() {
    const type = this.$route.query.type
    if (type === 'FEEDBACK' || type === 'SERVICE') this.activeType = type
    this.loadList()
    this.loadUnread()

    const id = Number(this.$route.query.id)
    if (id) {
      this.openTicket({ id })
      this.$router.replace({ path: this.$route.path })
    }
  },
  methods: {
    async loadList() {
      const seq = ++this.loadSeq
      this.loading = true
      try {
        const data = await feedbackApi.adminPage({
          type: this.activeType,
          status: this.status || undefined,
          pageNum: this.pageNum,
          pageSize: this.pageSize
        })
        if (seq !== this.loadSeq) return
        this.rows = data.list || data.records || []
        this.total = data.total || 0
      } catch {
        // 失败提示由 http.js 统一弹出
      } finally {
        if (seq === this.loadSeq) this.loading = false
      }
    },

    async loadUnread() {
      try {
        const data = await feedbackApi.unread()
        this.unreadTotal = data.adminUnread || 0
      } catch {
        // 红点是辅助信息，失败静默
      }
    },

    reload() {
      this.pageNum = 1
      this.loadList()
    },

    handlePageChange(page) {
      this.pageNum = page
      this.loadList()
    },

    handleSizeChange(size) {
      this.pageSize = size
      this.pageNum = 1
      this.loadList()
    },

    openTicket(row) {
      this.activeTicket = row
      this.drawerVisible = true
      // 详情接口会把管理员已读推到最新，红点跟着刷新
      this.loadUnread()
    },

    loadThread(id, afterId) {
      return feedbackApi.adminDetail(id, afterId)
    },
    replyThread(id, content, clientMsgId) {
      return feedbackApi.adminReply(id, content, clientMsgId)
    },
    closeThread(id) {
      return feedbackApi.adminClose(id)
    },

    handleThreadUpdated() {
      this.loadList()
      this.loadUnread()
    },

    responseText(row) {
      if (row.status === 'CLOSED') return '已关闭'
      return row.handledBy ? '已响应' : '待响应'
    },

    responseTagType(row) {
      if (row.status === 'CLOSED') return 'info'
      return row.handledBy ? 'success' : 'warning'
    },

    /** 未响应时按建单时间算已经等了多久；只作运营提示，不是 SLA */
    waitingText(row) {
      if (row.status === 'CLOSED') return '—'
      if (row.handledBy) return '已响应'
      const created = new Date(String(row.createdTime || '').replace(' ', 'T')).getTime()
      if (!created) return '待响应'
      const minutes = Math.floor((Date.now() - created) / 60000)
      if (minutes < 60) return `已等 ${Math.max(1, minutes)} 分钟`
      if (minutes < 1440) return `已等 ${Math.floor(minutes / 60)} 小时`
      return `已等 ${Math.floor(minutes / 1440)} 天`
    }
  }
}
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.page-title {
  margin: 0 0 6px;
  font-size: 22px;
  color: var(--ink);
}
.page-subtitle {
  margin: 0;
  max-width: 760px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink-3);
}
.list-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.type-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.list-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.unread-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: transparent;
}
.unread-dot.on {
  background: var(--app-danger);
}
</style>
