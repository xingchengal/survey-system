<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">反馈中心</h2>
        <p class="page-subtitle">
          提交问题或发起客服咨询。客服工单同一时间只保留一条未关闭记录，再点客服会续开原工单。
        </p>
      </div>
      <el-tag size="small" effect="plain">所有消息均为纯文本</el-tag>
    </div>

    <el-row :gutter="20" class="equal-height-row">
      <el-col :span="9" :xs="24" :sm="24" :md="9">
        <el-card>
          <template #header>
            <span class="text-bold">✍️ 新建</span>
          </template>

          <el-radio-group v-model="form.type" class="type-group">
            <el-radio-button value="FEEDBACK">反馈</el-radio-button>
            <el-radio-button value="SERVICE">客服</el-radio-button>
          </el-radio-group>

          <div class="form-tip">
            {{ form.type === 'SERVICE'
              ? '已有未关闭的客服工单时，这次内容会直接续在原工单里。'
              : '反馈可以提交多条，管理员处理后会回复到这里。' }}
          </div>

          <el-input
            v-model="form.subject"
            maxlength="200"
            show-word-limit
            placeholder="主题，例如：导出 Excel 失败"
            class="mt-12"
          />
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            maxlength="2000"
            show-word-limit
            placeholder="请描述遇到的问题或想咨询的内容（≤2000 字）"
            class="mt-12"
          />
          <el-button
            type="primary"
            class="submit-btn"
            :loading="creating"
            @click="submit"
          >
            提交
          </el-button>
        </el-card>
      </el-col>

      <el-col :span="15" :xs="24" :sm="24" :md="15">
        <el-card class="list-card">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">📨 我的工单</span>
              <el-button link type="primary" :loading="loading" @click="loadMy">刷新</el-button>
            </div>
          </template>

          <el-skeleton v-if="loading && myList.length === 0" :rows="4" animated />
          <el-empty v-else-if="myList.length === 0" description="还没有提交过反馈" :image-size="80" />
          <div v-else class="ticket-list">
            <div
              v-for="item in myList"
              :key="item.id"
              class="ticket-row"
              @click="openTicket(item.id)"
            >
              <span class="ticket-unread" :class="{ on: item.unread === 1 }"></span>
              <div class="ticket-main">
                <div class="ticket-title">
                  <el-tag size="small" :type="item.type === 'SERVICE' ? 'warning' : 'primary'" effect="light">
                    {{ item.type === 'SERVICE' ? '客服' : '反馈' }}
                  </el-tag>
                  <span class="ticket-subject">{{ item.subject }}</span>
                </div>
                <div class="ticket-preview">{{ item.lastMessagePreview || '—' }}</div>
              </div>
              <div class="ticket-meta">
                <el-tag size="small" :type="item.status === 'OPEN' ? 'success' : 'info'" effect="plain">
                  {{ item.status === 'OPEN' ? '处理中' : '已关闭' }}
                </el-tag>
                <span class="ticket-time">{{ item.lastMessageTime }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-drawer
      v-model="drawerVisible"
      size="520px"
      :title="current ? current.subject : '工单详情'"
      @closed="stopPolling"
    >
      <template #header>
        <div class="drawer-head">
          <div>
            <div class="drawer-title">{{ current ? current.subject : '工单详情' }}</div>
            <div class="drawer-sub">
              工单 #{{ current ? current.id : '' }}
              <el-tag v-if="current" size="small" :type="current.status === 'OPEN' ? 'success' : 'info'" effect="plain">
                {{ current.status === 'OPEN' ? '处理中' : '已关闭' }}
              </el-tag>
            </div>
          </div>
        </div>
      </template>

      <div v-if="current" class="thread">
        <el-empty v-if="messages.length === 0" description="暂无消息" :image-size="60" />
        <div
          v-for="msg in messages"
          :key="msg.id"
          class="bubble-row"
          :class="msg.senderRole === 'user' ? 'is-user' : 'is-admin'"
        >
          <div class="bubble-meta">
            <span>{{ msg.senderRole === 'user' ? '我' : '客服' }}</span>
            <span class="bubble-time">{{ msg.createdTime }}</span>
          </div>
          <div class="bubble">{{ msg.content }}</div>
        </div>

        <div v-if="current.status === 'CLOSED'" class="closed-tip">工单已关闭，如需继续请新建一条。</div>
        <div v-else class="composer">
          <el-input
            v-model="reply"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            placeholder="继续补充（≤2000 字）"
          />
          <div class="composer-actions">
            <el-button type="danger" plain :loading="closing" @click="closeTicket">关闭工单</el-button>
            <el-button type="primary" :loading="sending" @click="sendReply">发送</el-button>
          </div>
        </div>
      </div>
      <el-skeleton v-else :rows="6" animated />
    </el-drawer>
  </div>
</template>

<script>
// ★ 用户端反馈：所有登录用户可用；后端按 feedback.user_id 校验归属。
//   详情打开后每 3 秒按 afterId 增量拉取；页面隐藏时暂停，失败按 3/5/10/20 秒退避。
import { ElMessage } from 'element-plus'
import { feedback as feedbackApi } from '../api'
import { newClientMsgId } from '../api/feedback'

const POLL_BASE = 3000
const POLL_MAX = 20000

export default {
  data() {
    return {
      form: { type: 'FEEDBACK', subject: '', content: '' },
      creating: false,
      loading: false,
      myList: [],
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
    this.loadMy()
    this.visibilityHandler = () => {
      if (document.visibilityState === 'visible' && this.drawerVisible && this.current) {
        this.loadDetail(this.current.id, this.lastId)
      }
    }
    document.addEventListener('visibilitychange', this.visibilityHandler)

    // 通知 link=/feedback?ticket={id}：进来直接打开对应工单
    const ticket = Number(this.$route.query.ticket)
    if (ticket) {
      this.openTicket(ticket)
      this.$router.replace({ path: this.$route.path })
    }
  },
  beforeUnmount() {
    this.stopPolling()
    if (this.visibilityHandler) document.removeEventListener('visibilitychange', this.visibilityHandler)
  },
  methods: {
    async loadMy() {
      this.loading = true
      try {
        this.myList = await feedbackApi.my()
      } catch {
        // 失败提示由 http.js 统一弹出
      } finally {
        this.loading = false
      }
    },

    async submit() {
      if (!this.form.subject.trim()) {
        ElMessage.warning('请填写主题')
        return
      }
      if (!this.form.content.trim()) {
        ElMessage.warning('请填写内容')
        return
      }
      this.creating = true
      try {
        const detail = await feedbackApi.create({
          type: this.form.type,
          subject: this.form.subject.trim(),
          content: this.form.content.trim(),
          clientMsgId: newClientMsgId()
        })
        this.form.subject = ''
        this.form.content = ''
        this.$router.replace({ path: this.$route.path, query: { ticket: detail.feedback.id } })
        await this.loadMy()
        this.openTicket(detail.feedback.id, detail)
        ElMessage.success(this.form.type === 'SERVICE' ? '客服工单已提交' : '反馈已提交')
      } catch {
        // 失败提示由 http.js 统一弹出
      } finally {
        this.creating = false
      }
    },

    async openTicket(id, preloaded) {
      this.drawerVisible = true
      this.messages = []
      this.lastId = 0
      this.reply = ''
      this.current = preloaded ? preloaded.feedback : null
      if (preloaded) {
        this.applyDetail(preloaded)
      } else {
        await this.loadDetail(id, 0)
      }
      this.startPolling()
      if (this.current) feedbackApi.read(this.current.id).catch(() => {})
    },

    /** 拉详情：afterId=0 全量替换，否则按 id 合并去重 */
    async loadDetail(id, afterId) {
      const data = await feedbackApi.detail(id, afterId)
      this.applyDetail(data)
      // 还有更多就继续拉（单次最多 50 条）
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
          // 隐藏时暂停请求，但保持调度；切回后 visibilitychange 会立即补拉
          this.schedulePoll()
          return
        }
        try {
          await this.loadDetail(this.current.id, this.lastId)
          this.pollDelay = POLL_BASE
        } catch {
          this.pollDelay = this.pollDelay < 5000 ? 5000
            : this.pollDelay < 10000 ? 10000 : POLL_MAX
        }
        this.schedulePoll()
      }, this.pollDelay)
    },

    async sendReply() {
      const text = this.reply.trim()
      if (!text || !this.current) return
      this.sending = true
      try {
        const data = await feedbackApi.send(this.current.id, text, newClientMsgId())
        this.reply = ''
        this.applyDetail(data)
        await this.loadMy()
      } catch {
        // 失败提示由 http.js 统一弹出；输入内容保留，方便重试
      } finally {
        this.sending = false
      }
    },

    async closeTicket() {
      if (!this.current) return
      try {
        await this.$confirm('关闭后不能再发消息，确定关闭这个工单？', '关闭工单', { type: 'warning' })
      } catch {
        return
      }
      this.closing = true
      try {
        await feedbackApi.close(this.current.id)
        ElMessage.success('工单已关闭')
        this.current.status = 'CLOSED'
        this.stopPolling()
        await this.loadMy()
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
  line-height: 1.6;
}
.equal-height-row :deep(.el-col) {
  display: flex;
}
.equal-height-row :deep(.el-card) {
  width: 100%;
}
.type-group {
  width: 100%;
}
.type-group :deep(.el-radio-button) {
  flex: 1;
}
.type-group :deep(.el-radio-button__inner) {
  width: 100%;
}
.form-tip {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-3);
}
.mt-12 {
  margin-top: 12px;
}
.submit-btn {
  margin-top: 14px;
  width: 100%;
}
.list-card :deep(.el-card__body) {
  padding-top: 8px;
}

/* 工单列表 */
.ticket-list {
  display: flex;
  flex-direction: column;
}
.ticket-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 8px;
  border-bottom: 1px dashed #f0f2f5;
  cursor: pointer;
  transition: background-color 0.16s var(--ease);
}
.ticket-row:last-child {
  border-bottom: none;
}
.ticket-row:hover {
  background: var(--sky-50, #eff6ff);
}
.ticket-unread {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
  background: transparent;
}
.ticket-unread.on {
  background: #f56c6c;
}
.ticket-main {
  flex: 1;
  min-width: 0;
}
.ticket-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ticket-subject {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ticket-preview {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ticket-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
  flex-shrink: 0;
}
.ticket-time {
  font-size: 11px;
  color: var(--ink-3);
}

/* 详情线程 */
.drawer-head {
  display: flex;
  justify-content: space-between;
  width: 100%;
  padding-right: 24px;
}
.drawer-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.drawer-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
  display: flex;
  align-items: center;
  gap: 8px;
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
.bubble-row.is-user {
  align-self: flex-end;
  align-items: flex-end;
}
.bubble-row.is-admin {
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
.is-user .bubble {
  background: #2f80ed;
  color: #fff;
}
.is-admin .bubble {
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
html[data-theme="dark"] .is-admin .bubble {
  background: #16263f;
}
html[data-theme="dark"] .closed-tip {
  background: #1e293b;
}
</style>
