<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">反馈管理</h2>
        <p class="page-subtitle">
          处理用户反馈与客服工单。只有被授予「反馈 / 客服工单处理」的次级管理员才能进入。
        </p>
      </div>
      <el-tag v-if="unreadTotal > 0" type="danger" effect="light">未读 {{ unreadTotal }}</el-tag>
    </div>

    <el-card>
      <template #header>
        <div class="list-head">
          <el-tabs v-model="activeType" class="type-tabs" @tab-change="reload">
            <el-tab-pane label="反馈" name="FEEDBACK" />
            <el-tab-pane label="客服" name="SERVICE" />
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

      <el-table v-loading="loading" :data="rows" stripe @row-click="openTicket">
        <el-table-column label="" width="42" align="center">
          <template #default="{ row }">
            <span class="unread-dot" :class="{ on: row.unread === 1 }"></span>
          </template>
        </el-table-column>
        <el-table-column prop="subject" label="主题" min-width="220" show-overflow-tooltip />
        <el-table-column label="发起人" width="150">
          <template #default="{ row }">{{ row.nickname || row.username }}</template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'OPEN' ? 'success' : 'info'" effect="plain">
              {{ row.status === 'OPEN' ? '处理中' : '已关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="处理人" width="120">
          <template #default="{ row }">{{ row.handledByName || '未分配' }}</template>
        </el-table-column>
        <el-table-column prop="lastMessagePreview" label="最新消息" min-width="220" show-overflow-tooltip />
        <el-table-column prop="lastMessageTime" label="更新时间" width="160" />
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

    <el-drawer
      v-model="drawerVisible"
      size="560px"
      :title="current ? current.subject : '工单详情'"
      @closed="stopPolling"
    >
      <template #header>
        <div class="drawer-head">
          <div class="drawer-title">{{ current ? current.subject : '工单详情' }}</div>
          <div class="drawer-sub">
            <span>工单 #{{ current ? current.id : '' }}</span>
            <span v-if="current">{{ current.nickname || current.username }}</span>
            <el-tag v-if="current" size="small" :type="current.status === 'OPEN' ? 'success' : 'info'" effect="plain">
              {{ current.status === 'OPEN' ? '处理中' : '已关闭' }}
            </el-tag>
          </div>
        </div>
      </template>

      <div v-if="current" class="thread">
        <el-empty v-if="messages.length === 0" description="暂无消息" :image-size="60" />
        <div
          v-for="msg in messages"
          :key="msg.id"
          class="bubble-row"
          :class="msg.senderRole === 'admin' ? 'is-admin' : 'is-user'"
        >
          <div class="bubble-meta">
            <span>{{ msg.senderRole === 'admin' ? '管理员' : (current.nickname || current.username) }}</span>
            <span class="bubble-time">{{ msg.createdTime }}</span>
          </div>
          <div class="bubble">{{ msg.content }}</div>
        </div>

        <div v-if="current.status === 'CLOSED'" class="closed-tip">工单已关闭，如需继续请让用户新建。</div>
        <div v-else class="composer">
          <el-input
            v-model="reply"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="回复用户（≤2000 字）"
          />
          <div class="composer-actions">
            <el-button type="danger" plain :loading="closing" @click="closeTicket">关闭工单</el-button>
            <el-button type="primary" :loading="sending" @click="sendReply">回复</el-button>
          </div>
        </div>
      </div>
      <el-skeleton v-else :rows="6" animated />
    </el-drawer>
  </div>
</template>

<script>
// ★ 管理端反馈/客服：后端每个端点都挂 @RequirePermission(feedback:handle)。
//   详情打开后每 5 秒按 afterId 增量拉取；隐藏暂停，失败 5/10/20/30 秒退避。
import { ElMessage } from 'element-plus'
import { feedback as feedbackApi } from '../api'
import { newClientMsgId } from '../api/feedback'

const POLL_BASE = 5000
const POLL_MAX = 30000

export default {
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
      drawerVisible: false,
      current: null,
      messages: [],
      lastId: 0,
      reply: '',
      sending: false,
      closing: false,
      pollTimer: null,
      pollStopped: true,
      pollDelay: POLL_BASE,
      visibilityHandler: null
    }
  },
  mounted() {
    this.loadList()
    this.loadUnread()
    this.visibilityHandler = () => {
      if (document.visibilityState === 'visible' && this.drawerVisible && this.current) {
        this.loadDetail(this.current.id, this.lastId)
      }
    }
    document.addEventListener('visibilitychange', this.visibilityHandler)

    const id = Number(this.$route.query.id)
    if (id) {
      this.openTicket({ id })
      this.$router.replace({ path: this.$route.path })
    }
  },
  beforeUnmount() {
    this.stopPolling()
    if (this.visibilityHandler) document.removeEventListener('visibilitychange', this.visibilityHandler)
  },
  methods: {
    async loadList() {
      this.loading = true
      try {
        const data = await feedbackApi.adminPage({
          type: this.activeType,
          status: this.status || undefined,
          pageNum: this.pageNum,
          pageSize: this.pageSize
        })
        this.rows = data.list || data.records || []
        this.total = data.total || 0
      } catch {
        // 失败提示由 http.js 统一弹出
      } finally {
        this.loading = false
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

    async openTicket(row) {
      this.drawerVisible = true
      this.messages = []
      this.lastId = 0
      this.reply = ''
      this.current = null
      await this.loadDetail(row.id, 0)
      this.startPolling()
    },

    async loadDetail(id, afterId) {
      const data = await feedbackApi.adminDetail(id, afterId)
      this.applyDetail(data)
      if (data.hasMore) {
        await this.loadDetail(id, this.lastId)
      }
    },

    applyDetail(data) {
      if (!data || !data.feedback) return
      this.current = data.feedback
      const list = data.messages || []
      if (!this.lastId) {
        this.messages = list
      } else {
        const seen = new Set(this.messages.map(m => m.id))
        this.messages = this.messages.concat(list.filter(m => !seen.has(m.id)))
      }
      if (list.length) this.lastId = list[list.length - 1].id
    },

    startPolling() {
      this.stopPolling()
      this.pollStopped = false
      this.pollDelay = POLL_BASE
      this.schedulePoll()
    },

    stopPolling() {
      this.pollStopped = true
      if (this.pollTimer) {
        clearTimeout(this.pollTimer)
        this.pollTimer = null
      }
    },

    schedulePoll() {
      if (this.pollStopped || !this.current) return
      this.pollTimer = setTimeout(async () => {
        if (this.pollStopped) return
        if (document.visibilityState === 'hidden') {
          this.schedulePoll()
          return
        }
        try {
          await this.loadDetail(this.current.id, this.lastId)
          this.pollDelay = POLL_BASE
        } catch {
          this.pollDelay = this.pollDelay < 10000 ? 10000
            : this.pollDelay < 20000 ? 20000 : POLL_MAX
        }
        this.schedulePoll()
      }, this.pollDelay)
    },

    async sendReply() {
      const text = this.reply.trim()
      if (!text || !this.current) return
      this.sending = true
      try {
        const data = await feedbackApi.adminReply(this.current.id, text, newClientMsgId())
        this.reply = ''
        this.applyDetail(data)
        await this.loadList()
        await this.loadUnread()
      } catch {
        // 失败提示由 http.js 统一弹出；输入保留
      } finally {
        this.sending = false
      }
    },

    async closeTicket() {
      if (!this.current) return
      try {
        await this.$confirm('关闭后用户不能再回复，确定关闭？', '关闭工单', { type: 'warning' })
      } catch {
        return
      }
      this.closing = true
      try {
        await feedbackApi.adminClose(this.current.id)
        ElMessage.success('工单已关闭')
        this.current.status = 'CLOSED'
        this.stopPolling()
        await this.loadList()
        await this.loadUnread()
      } catch {
        // 失败提示由 http.js 统一弹出
      } finally {
        this.closing = false
      }
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
  font-size: 13px;
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
  background: #f56c6c;
}
.pager-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* 详情线程 */
.drawer-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-right: 24px;
}
.drawer-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.drawer-sub {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--ink-3);
}
.thread {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.bubble-row {
  display: flex;
  flex-direction: column;
  max-width: 86%;
}
.bubble-row.is-admin {
  align-self: flex-end;
  align-items: flex-end;
}
.bubble-row.is-user {
  align-self: flex-start;
  align-items: flex-start;
}
.bubble-meta {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: var(--ink-3);
  margin-bottom: 4px;
}
.bubble {
  padding: 9px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.is-admin .bubble {
  background: #2f80ed;
  color: #fff;
}
.is-user .bubble {
  background: #f4f7fc;
  color: var(--ink);
}
.closed-tip {
  margin-top: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f4f4f5;
  color: var(--ink-3);
  font-size: 12px;
  text-align: center;
}
.composer {
  margin-top: 12px;
  border-top: 1px solid #f0f2f5;
  padding-top: 12px;
}
.composer-actions {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
}
html[data-theme="dark"] .is-user .bubble {
  background: #16263f;
}
html[data-theme="dark"] .closed-tip {
  background: #1e293b;
}
</style>
