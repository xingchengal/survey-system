<template>
  <el-drawer
    v-model="visible"
    :size="drawerSize"
    :title="ticket ? ticket.subject : '工单详情'"
    @closed="handleClosed"
  >
    <template #header>
      <div class="thread-head">
        <div class="head-title">{{ ticket ? ticket.subject : '工单详情' }}</div>
        <div class="head-sub">
          <span>工单 #{{ ticketId || '—' }}</span>
          <span v-if="isAdmin && peerName">发起人：{{ peerName }}</span>
          <el-tag
            v-if="ticket"
            size="small"
            :type="ticket.status === 'OPEN' ? 'success' : 'info'"
            effect="plain"
          >
            {{ statusText }}
          </el-tag>
        </div>
      </div>
    </template>

    <div class="thread-shell">
      <!-- 消息区独立滚动：header 与 composer 都不随消息变长 -->
      <div ref="scroller" class="thread-body">
        <el-skeleton v-if="!ticket" :rows="6" animated />
        <template v-else>
          <div v-if="messages.length === 0" class="thread-empty">暂无消息</div>

          <section v-for="group in groups" :key="group.key" class="msg-group">
            <div v-if="group.timeLabel" class="time-divider">
              <span>{{ group.timeLabel }}</span>
            </div>
            <div
              v-for="(msg, index) in group.messages"
              :key="msg.id"
              class="msg-row"
              :class="isMine(msg) ? 'is-mine' : 'is-peer'"
            >
              <div class="msg-avatar" :class="{ 'is-continuation': index > 0 }">
                {{ avatarOf(msg) }}
              </div>
              <div class="msg-main">
                <div v-if="index === 0" class="msg-name">{{ senderName(msg) }}</div>
                <div class="bubble">{{ msg.content }}</div>
              </div>
            </div>
          </section>

          <div v-if="ticket.status === 'CLOSED'" class="system-divider">
            {{ isFeedback ? '已标记为已处理' : '工单已关闭' }}
          </div>
        </template>
      </div>

      <div class="thread-foot">
        <template v-if="ticket && ticket.status === 'OPEN' && !readonly">
          <el-input
            v-model="reply"
            type="textarea"
            :rows="3"
            maxlength="2000"
            show-word-limit
            resize="none"
            :placeholder="isAdmin ? '回复用户（Enter 发送，Shift+Enter 换行）' : '继续补充（Enter 发送，Shift+Enter 换行）'"
            @keydown="handleKeydown"
          />
          <div class="foot-actions">
            <el-button type="danger" plain :loading="closing" @click="closeTicket">
              {{ isFeedback ? '标记已处理' : '关闭工单' }}
            </el-button>
            <el-button
              type="primary"
              :loading="sending"
              :disabled="sending || !reply.trim()"
              @click="sendReply"
            >
              {{ isAdmin ? '回复' : '发送' }}
            </el-button>
          </div>
        </template>
        <div v-else-if="ticket && readonly" class="foot-note">
          {{ ticket.status === 'CLOSED'
            ? (isFeedback ? '该反馈已处理。' : '工单已关闭。')
            : '反馈已提交，管理员回复后会显示在这里，并通过消息中心通知你。' }}
        </div>
        <div v-else-if="ticket" class="closed-tip">
          {{ isFeedback
            ? '该反馈已标记为已处理。'
            : `工单已关闭，${isAdmin ? '如需继续请让用户重新提交。' : '如有新问题请重新提交一条。'}` }}
        </div>
        <div v-else class="foot-placeholder">正在加载工单…</div>
      </div>
    </div>
  </el-drawer>
</template>

<script>
/**
 * 反馈 / 客服工单线程抽屉：用户端反馈页、客服中心与管理端反馈管理共用。
 * ★ 轮询只给客服工单：用户端 3s、管理端 5s；`pollInterval=0` 时只在打开时拉一次全量（反馈）。
 *   afterId 增量、单次 50 条 + hasMore、页面隐藏暂停、失败按 3/5/10/20（管理端 5/10/20/30）秒退避。
 * ★ `readonly` 隐藏输入框与关闭按钮，用于用户端反馈详情；页面只传 loader/sender/closer/reader，
 *   不要把轮询、分组合并、Enter 发送再各抄一套。
 * ★ 请求带序号 loadSeq：切换工单后旧请求的响应直接丢弃，避免 A 工单消息串到 B 工单。
 */
import { ElMessage } from 'element-plus'
import { newClientMsgId } from '../api/feedback'

const FIVE_MINUTES = 5 * 60 * 1000
/** 距底部小于这个距离才自动跟随新消息，避免打断用户翻历史 */
const NEAR_BOTTOM = 120
const pad = (n) => String(n).padStart(2, '0')

/** 后端是 'yyyy-MM-dd HH:mm:ss'；Safari 不认空格分隔，统一换成 T 再解析 */
function parseTime(text) {
  if (!text) return 0
  const ts = new Date(String(text).replace(' ', 'T')).getTime()
  return Number.isNaN(ts) ? 0 : ts
}

function sameDay(a, b) {
  return a.getFullYear() === b.getFullYear()
    && a.getMonth() === b.getMonth()
    && a.getDate() === b.getDate()
}

/** 时间分隔标签：今天 / 昨天 / 月-日 / 完整日期，统一带时分 */
function formatTimeLabel(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (sameDay(d, now)) return `今天 ${hm}`
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (sameDay(d, yesterday)) return `昨天 ${hm}`
  if (d.getFullYear() === now.getFullYear()) return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`
}

export default {
  name: 'FeedbackThread',
  props: {
    modelValue: { type: Boolean, default: false },
    ticketId: { type: Number, default: 0 },
    /** 管理端视角：自己的消息 senderRole=admin，对端是发起人 */
    isAdmin: { type: Boolean, default: false },
    /** 管理端显示发起人昵称；用户端固定显示「客服」 */
    peerName: { type: String, default: '' },
    /** 详情轮询间隔：客服中心 3000、管理端客服 5000；传 0 = 不轮询（反馈只在打开时拉一次） */
    pollInterval: { type: Number, default: 3000 },
    /** 只读：隐藏输入框与关闭按钮，只展示线程（用户端反馈就是这种） */
    readonly: { type: Boolean, default: false },
    /** 线程语义：service = 客服工单（关闭工单）；feedback = 一次性反馈（标记已处理） */
    variant: { type: String, default: 'service' },
    /** 列表行对象：详情返回前先用它填标题与状态，避免抽屉头部空一拍 */
    initialTicket: { type: Object, default: null },
    /** (id, afterId) => Promise<FeedbackDetail> */
    loader: { type: Function, required: true },
    /** (id, content, clientMsgId) => Promise<FeedbackDetail>；只读时不用传 */
    sender: { type: Function, default: null },
    /** (id) => Promise<void> */
    closer: { type: Function, default: null },
    /** (id) => Promise<void>，标记已读；管理端详情接口自动已读，可不传 */
    reader: { type: Function, default: null }
  },
  emits: ['update:modelValue', 'updated', 'closed'],
  data() {
    return {
      ticket: null,
      messages: [],
      lastId: 0,
      reply: '',
      sending: false,
      closing: false,
      pollTimer: null,
      pollStopped: true,
      pollDelay: 0,
      loadSeq: 0,
      loadingTicketId: 0,
      viewportWidth: window.innerWidth
    }
  },
  computed: {
    visible: {
      get() {
        return this.modelValue
      },
      set(val) {
        this.$emit('update:modelValue', val)
      }
    },
    isFeedback() {
      return this.variant === 'feedback'
    },
    /** 反馈是意见箱，状态只有「待处理 / 已处理」；客服才是工单状态 */
    statusText() {
      if (!this.ticket) return ''
      const open = this.ticket.status === 'OPEN'
      if (this.isFeedback) return open ? '待处理' : '已处理'
      return open ? '处理中' : '已关闭'
    },
    drawerSize() {
      return this.viewportWidth < 768 ? '100%' : '560px'
    },
    /** 连续同角色、间隔 ≤5 分钟的消息合成一组；组首才显示时间分隔、头像与昵称 */
    groups() {
      const result = []
      let current = null
      let lastRole = ''
      let lastTs = 0
      for (const msg of this.messages) {
        const ts = parseTime(msg.createdTime)
        const role = msg.senderRole
        if (!current || role !== lastRole || ts - lastTs > FIVE_MINUTES) {
          current = { key: `g-${msg.id}`, timeLabel: formatTimeLabel(ts), messages: [] }
          result.push(current)
        }
        current.messages.push(msg)
        lastRole = role
        lastTs = ts
      }
      return result
    }
  },
  watch: {
    modelValue: {
      immediate: true,
      handler(val) {
        if (val) this.openThread()
        else this.stopPolling()
      }
    },
    ticketId() {
      if (this.modelValue) this.openThread()
    },
    messages() {
      // DOM 更新前先判断"原本是否在底部"，否则新气泡把 scrollHeight 撑大后就永远判为不在底部
      const box = this.$refs.scroller
      const nearBottom = !box || box.scrollHeight - box.scrollTop - box.clientHeight < NEAR_BOTTOM
      this.$nextTick(() => {
        const el = this.$refs.scroller
        if (el && nearBottom) el.scrollTop = el.scrollHeight
      })
    }
  },
  mounted() {
    this.resizeHandler = () => {
      this.viewportWidth = window.innerWidth
    }
    window.addEventListener('resize', this.resizeHandler)
    this.visibilityHandler = () => {
      if (document.visibilityState === 'visible' && this.modelValue && this.ticketId && this.pollInterval > 0) {
        this.loadDetail(this.ticketId, this.lastId, this.loadSeq).catch(() => {})
      }
    }
    document.addEventListener('visibilitychange', this.visibilityHandler)
  },
  beforeUnmount() {
    this.stopPolling()
    window.removeEventListener('resize', this.resizeHandler)
    document.removeEventListener('visibilitychange', this.visibilityHandler)
  },
  methods: {
    /** 打开/切换工单：重置状态后全量拉一次，再开始轮询 */
    async openThread() {
      if (!this.ticketId) return
      // visible 与 ticketId 同一次赋值会触发两个 watcher，这里挡掉重复加载
      if (this.loadingTicketId === this.ticketId) return
      const seq = ++this.loadSeq
      this.loadingTicketId = this.ticketId
      this.stopPolling()
      this.messages = []
      this.lastId = 0
      this.reply = ''
      this.sending = false
      this.closing = false
      this.ticket = this.initialTicket || null
      this.startPolling(seq)
      if (this.reader) {
        Promise.resolve(this.reader(this.ticketId)).catch(() => {})
      }
      try {
        await this.loadDetail(this.ticketId, 0, seq)
        this.scrollToBottom()
      } catch {
        // 失败提示由 api/http 统一弹出；抽屉保持可关闭
      } finally {
        if (seq === this.loadSeq) this.loadingTicketId = 0
      }
    },

    /** afterId=0 全量替换，否则按 id 合并去重；hasMore 时立即续拉 */
    async loadDetail(id, afterId, seq) {
      const data = await this.loader(id, afterId)
      if (seq !== this.loadSeq) return
      this.applyDetail(data)
      if (data && data.hasMore) {
        await this.loadDetail(id, this.lastId, seq)
      }
    },

    applyDetail(data) {
      if (!data || !data.feedback) return
      this.ticket = data.feedback
      const list = data.messages || []
      if (!this.lastId) {
        this.messages = list
      } else {
        const seen = new Set(this.messages.map(m => m.id))
        this.messages = this.messages.concat(list.filter(m => !seen.has(m.id)))
      }
      if (list.length) this.lastId = list[list.length - 1].id
    },

    startPolling(seq) {
      // pollInterval <= 0：反馈是异步意见箱，打开时拉一次即可，不做增量轮询
      if (!this.pollInterval || this.pollInterval <= 0) return
      this.pollStopped = false
      this.pollDelay = this.pollInterval
      this.schedulePoll(seq)
    },

    stopPolling() {
      this.pollStopped = true
      if (this.pollTimer) {
        clearTimeout(this.pollTimer)
        this.pollTimer = null
      }
    },

    schedulePoll(seq) {
      if (this.pollStopped || seq !== this.loadSeq || !this.ticketId) return
      this.pollTimer = setTimeout(async () => {
        if (this.pollStopped || seq !== this.loadSeq) return
        if (document.visibilityState === 'hidden') {
          // 隐藏时暂停请求但保持调度；切回后 visibilitychange 会立即补拉
          this.schedulePoll(seq)
          return
        }
        try {
          await this.loadDetail(this.ticketId, this.lastId, seq)
          this.pollDelay = this.pollInterval
        } catch {
          this.pollDelay = this.nextRetryDelay()
        }
        this.schedulePoll(seq)
      }, this.pollDelay)
    },

    /** 退避与 ARCHITECTURE 的固定口径一致：用户端 5/10/20 秒，管理端 10/20/30 秒 */
    nextRetryDelay() {
      if (this.isAdmin) {
        if (this.pollDelay < 10000) return 10000
        if (this.pollDelay < 20000) return 20000
        return 30000
      }
      if (this.pollDelay < 5000) return 5000
      if (this.pollDelay < 10000) return 10000
      return 20000
    },

    isMine(msg) {
      return this.isAdmin ? msg.senderRole === 'admin' : msg.senderRole === 'user'
    },

    senderName(msg) {
      if (this.isMine(msg)) return '我'
      if (!this.isAdmin) return '客服'
      // 从通知链接直接打开时列表行没有昵称，详情返回后从 ticket 补齐
      const ticket = this.ticket || {}
      return this.peerName || ticket.nickname || ticket.username || '用户'
    },

    avatarOf(msg) {
      return this.senderName(msg).slice(0, 1)
    },

    scrollToBottom() {
      this.$nextTick(() => {
        const el = this.$refs.scroller
        if (el) el.scrollTop = el.scrollHeight
      })
    },

    handleKeydown(event) {
      if (event.key !== 'Enter' || event.shiftKey) return
      // 中文输入法确认候选词也会触发 Enter，此时绝不能发送
      if (event.isComposing || event.keyCode === 229) return
      event.preventDefault()
      this.sendReply()
    },

    async sendReply() {
      const text = this.reply.trim()
      if (!text || this.sending || !this.sender || !this.ticket || this.ticket.status !== 'OPEN') return
      this.sending = true
      try {
        const data = await this.sender(this.ticket.id, text, newClientMsgId())
        this.reply = ''
        this.applyDetail(data)
        this.$emit('updated', data)
      } catch {
        // 失败提示由 api/http 统一弹出；输入内容保留，方便重试
      } finally {
        this.sending = false
      }
    },

    async closeTicket() {
      if (!this.closer || !this.ticket) return
      const title = this.isFeedback ? '标记已处理' : '关闭工单'
      const message = this.isFeedback
        ? '标记后该反馈归入已处理，确定？'
        : (this.isAdmin ? '关闭后用户不能再回复，确定关闭？' : '关闭后不能再发消息，确定关闭这个工单？')
      try {
        await this.$confirm(message, title, { type: 'warning' })
      } catch {
        return
      }
      this.closing = true
      try {
        await this.closer(this.ticket.id)
        this.ticket = { ...this.ticket, status: 'CLOSED' }
        this.stopPolling()
        this.$emit('closed', this.ticket.id)
        ElMessage.success(this.isFeedback ? '已标记为已处理' : '工单已关闭')
        this.scrollToBottom()
      } catch {
        // 失败提示由 api/http 统一弹出
      } finally {
        this.closing = false
      }
    },

    handleClosed() {
      this.stopPolling()
    }
  }
}
</script>

<style scoped>
.thread-head {
  padding-right: 8px;
}
.head-title {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--ink);
  word-break: break-word;
}
.head-sub {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--ink-3);
}

/* drawer 自己只负责"壳"：正文区不滚动，滚动交给 .thread-body */
:deep(.el-drawer__header) {
  margin-bottom: 0;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--stroke);
  color: var(--ink);
}
:deep(.el-drawer__body) {
  padding: 0;
  overflow: hidden;
}
:deep(.el-drawer) {
  background: var(--surface);
}

.thread-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--surface);
}

.thread-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 20px 10px;
  background: var(--surface-2);
}

.thread-empty {
  padding: 32px 0;
  text-align: center;
  font-size: 13px;
  color: var(--ink-3);
}

.msg-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
}

.time-divider {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 4px 0 10px;
  font-size: 11px;
  color: var(--ink-3);
}
.time-divider::before,
.time-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--stroke);
}

.msg-row {
  display: flex;
  gap: 8px;
  max-width: 88%;
}
.msg-row.is-peer {
  align-self: flex-start;
}
.msg-row.is-mine {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.msg-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
}
.msg-avatar.is-continuation {
  visibility: hidden;
}
.is-peer .msg-avatar {
  background: var(--sky-100);
  color: var(--sky-700);
}
.is-mine .msg-avatar {
  background: var(--btn-gradient);
  color: #fff;
}

.msg-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.is-mine .msg-main {
  align-items: flex-end;
}
.msg-name {
  margin: 2px 4px 4px;
  font-size: 11px;
  color: var(--ink-3);
}

.bubble {
  padding: 9px 12px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.is-peer .bubble {
  border: 1px solid var(--stroke);
  border-top-left-radius: 4px;
  background: var(--surface);
  color: var(--ink);
}
.is-mine .bubble {
  border-top-right-radius: 4px;
  background: var(--btn-gradient);
  color: #fff;
}

.system-divider {
  width: fit-content;
  margin: 16px auto 8px;
  padding: 4px 12px;
  border: 1px solid var(--stroke);
  border-radius: 999px;
  background: var(--surface);
  font-size: 12px;
  color: var(--ink-3);
}

.thread-foot {
  flex-shrink: 0;
  padding: 12px 20px 16px;
  border-top: 1px solid var(--stroke);
  background: var(--surface);
}
.foot-actions {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
}
.closed-tip {
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--surface-2);
  font-size: 12px;
  text-align: center;
  color: var(--ink-3);
}
.foot-note {
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--surface-2);
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
  color: var(--ink-3);
}
.foot-placeholder {
  padding: 8px 0;
  font-size: 12px;
  text-align: center;
  color: var(--ink-3);
}

@media (max-width: 768px) {
  .thread-body {
    padding: 14px 14px 8px;
  }
  .thread-foot {
    padding: 10px 14px 12px;
  }
  .msg-row {
    max-width: 94%;
  }
}

html[data-theme="dark"] .is-peer .msg-avatar {
  background: #223a5e;
  color: #9ed0ff;
}
</style>
