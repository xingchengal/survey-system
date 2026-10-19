<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">客服中心</h2>
        <p class="page-subtitle">
          先搜一下常见问题；需要人工协助或跟进处理进度时，发起客服工单。
          投诉建议请用下方入口，会直接进入管理员反馈列表。
        </p>
      </div>
      <el-tag size="small" effect="plain">工作日 09:00–18:00</el-tag>
    </div>

    <el-row :gutter="20" class="equal-height-row">
      <el-col :span="15" :xs="24" :sm="24" :md="15">
        <el-card class="help-card">
          <template #header>
            <span class="text-bold">📖 帮助中心</span>
          </template>

          <div class="service-brief">
            <div class="brief-item">
              <el-icon class="brief-icon"><AlarmClock /></el-icon>
              <div>
                <div class="brief-title">服务时间</div>
                <div class="brief-text">周一至周五 09:00–18:00，法定节假日除外</div>
              </div>
            </div>
            <div class="brief-item">
              <el-icon class="brief-icon"><Timer /></el-icon>
              <div>
                <div class="brief-title">响应时效</div>
                <div class="brief-text">首次响应不超过 1 个工作日，非服务时间顺延</div>
              </div>
            </div>
            <div class="brief-item">
              <el-icon class="brief-icon"><ChatDotRound /></el-icon>
              <div>
                <div class="brief-title">服务方式</div>
                <div class="brief-text">工单全程留痕，进度在「我的客服会话」里看</div>
              </div>
            </div>
          </div>

          <el-input
            v-model="faqKeyword"
            class="faq-search"
            placeholder="搜索问题，例如：导出、发布、扣点、注销"
            clearable
          />

          <el-empty
            v-if="filteredFaq.length === 0"
            description="没有匹配的问题，可以直接发起客服工单"
            :image-size="70"
          />
          <el-collapse v-else v-model="openedFaq" class="faq-list">
            <el-collapse-item v-for="item in filteredFaq" :key="item.id" :name="item.id">
              <template #title>
                <span class="faq-question">{{ item.question }}</span>
              </template>
              <div class="faq-answer">{{ item.answer }}</div>
            </el-collapse-item>
          </el-collapse>

          <div class="help-cta">
            <span class="cta-hint">没找到答案？人工协助请在右侧发起客服工单。</span>
            <el-button plain @click="openComplaint">投诉建议</el-button>
          </div>
        </el-card>
      </el-col>

      <el-col :span="9" :xs="24" :sm="24" :md="9">
        <el-card class="session-card">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">💬 我的客服会话</span>
              <el-button link type="primary" :loading="loading" @click="loadTickets">刷新</el-button>
            </div>
          </template>

          <el-skeleton v-if="loading && serviceTickets.length === 0" :rows="3" animated />
          <el-empty
            v-else-if="serviceTickets.length === 0"
            description="还没有客服工单"
            :image-size="70"
          />
          <div v-else class="ticket-list">
            <div
              v-for="item in serviceTickets"
              :key="item.id"
              class="ticket-row"
              @click="openTicket(item)"
            >
              <span class="ticket-unread" :class="{ on: item.unread === 1 }"></span>
              <div class="ticket-main">
                <div class="ticket-subject">{{ item.subject }}</div>
                <div class="ticket-preview">{{ item.lastMessagePreview || '—' }}</div>
              </div>
              <div class="ticket-meta">
                <el-tag size="small" :type="item.status === 'OPEN' ? 'success' : 'info'" effect="plain">
                  {{ item.status === 'OPEN' ? '进行中' : '已关闭' }}
                </el-tag>
                <span class="ticket-time">{{ item.lastMessageTime }}</span>
              </div>
            </div>
          </div>

          <el-button type="primary" plain class="new-session-btn" @click="openCreate()">
            发起客服工单
          </el-button>
          <p class="session-tip">同一时间只保留一条未关闭的客服工单，再次发起会续在原工单里。</p>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog
      v-model="createVisible"
      :title="createTitle"
      width="520px"
      :close-on-click-modal="false"
    >
      <div v-if="hasOpenService" class="dialog-tip">
        你有一条进行中的客服工单，这次提交的内容会追加到那条工单里。
      </div>
      <el-input
        v-model="createForm.subject"
        maxlength="200"
        show-word-limit
        placeholder="主题，例如：问卷导出失败"
      />
      <el-input
        v-model="createForm.content"
        type="textarea"
        :rows="6"
        maxlength="2000"
        show-word-limit
        placeholder="请把事情经过、问卷名称或链接写清楚（≤2000 字）"
        class="mt-12"
      />
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">提交</el-button>
      </template>
    </el-dialog>

    <!-- 投诉走意见反馈：一次性提交，落到管理端「反馈管理」，不产生客服工单 -->
    <el-dialog
      v-model="complaintVisible"
      title="投诉建议"
      width="520px"
      :close-on-click-modal="false"
    >
      <div class="dialog-tip">
        投诉会直接进入管理员的「反馈管理」列表，请写清投诉对象与经过；这里不提供在线对话。
      </div>
      <el-input
        v-model="complaintContent"
        type="textarea"
        :rows="6"
        maxlength="2000"
        show-word-limit
        placeholder="请描述要投诉的问题、涉及的问卷或账号（≤2000 字）"
      />
      <template #footer>
        <el-button @click="complaintVisible = false">取消</el-button>
        <el-button type="primary" :loading="complaintSubmitting" @click="submitComplaint">提交投诉</el-button>
      </template>
    </el-dialog>

    <feedback-thread
      v-model="drawerVisible"
      :ticket-id="activeTicket ? activeTicket.id : 0"
      :initial-ticket="activeTicket"
      :loader="loadThread"
      :sender="sendThread"
      :closer="closeThread"
      :reader="markRead"
      @updated="handleThreadUpdated"
      @closed="handleThreadUpdated"
    />
  </div>
</template>

<script>
// ★ 客服中心：帮助中心（静态 FAQ）+ 在线客服（只列 SERVICE 工单），复用 3s 轮询。
//   同一用户只保留一个 OPEN SERVICE 工单由后端生成列唯一索引保证，前端只做提示。
import { ElMessage } from 'element-plus'
import FeedbackThread from '../components/FeedbackThread.vue'
import { feedback as feedbackApi } from '../api'
import { newClientMsgId } from '../api/feedback'

/** FAQ 是前端静态常量：不做管理后台，改内容只动这里 */
const FAQ_ITEMS = [
  {
    id: 'create',
    question: '怎么创建并发布问卷？',
    keywords: '新建 创建 发布 分享 分享码',
    answer: '侧栏「我的问卷」→ 新建问卷，编辑题目后点「发布」。发布成功后才会生成分享链接 / 分享码；未发布的问卷只能自己预览。'
  },
  {
    id: 'edit-published',
    question: '问卷发布后还能改题目吗？',
    keywords: '修改 编辑 发布后 版本 回滚',
    answer: '可以，编辑并保存即时生效。系统保留最近 50 个版本，可在编辑页的版本记录里回滚；回滚不会修复指向已删除题目的跳转，删题前先检查跳转规则。'
  },
  {
    id: 'link-broken',
    question: '分享链接打不开，或提示问卷已结束？',
    keywords: '链接 打不开 结束 截止 回收站 下架',
    answer: '先确认问卷已发布、没被移入回收站，且当前时间没超过截止时间。被举报下架的问卷会显示下架提示，可在「我的问卷」里申请恢复上架。'
  },
  {
    id: 'count-mismatch',
    question: '为什么统计的答卷数和导出条数对不上？',
    keywords: '统计 答卷数 导出 无效样本 对不上',
    answer: '提交时会按过快、直线作答、答案不在选项里、同题干答案不一致标「无效样本」，默认只打标不删除。统计页可勾选「排除无效样本」，导出不过滤。'
  },
  {
    id: 'export',
    question: '答卷能导出 Excel 吗？',
    keywords: '导出 excel 表格 答案',
    answer: '能。问卷统计页右上角「导出 Excel」导出该问卷的全部答卷；「我的答卷」页可导出自己提交过的答卷，管理员还可以在用户 / 日志页导出全量数据。'
  },
  {
    id: 'wallet',
    question: '钱包点数是怎么扣的？',
    keywords: '钱包 点数 扣费 充值 退款 流水',
    answer: '发布问卷、购买进阶题型包按次扣点；把问卷移入回收站会退回发布扣的点。充值与消费的每一笔都能在「我的钱包」的流水里查到。'
  },
  {
    id: 'account',
    question: '怎么修改密码或注销账号？',
    keywords: '密码 注销 账号 恢复 个人中心',
    answer: '进「个人中心」→「修改密码」；注销在个人中心底部的「注销账号」。注销后用户名仍被占用、数据不级联删除，恢复需要联系管理员。'
  },
  {
    id: 'feedback-vs-service',
    question: '反馈和客服有什么区别？',
    keywords: '反馈 客服 区别 意见箱 工单',
    answer: '反馈是意见箱：一次性提交意见、建议或投诉，管理员在后台逐条查看回复，不承诺即时对话（投诉也走这条）。客服是在线协助：需要人工跟进、处理进度时发起客服工单，有服务时间与响应时效。'
  },
  {
    id: 'response-time',
    question: '客服多久回复？',
    keywords: '多久 回复 响应 时间 时效',
    answer: '首次响应不超过 1 个工作日；服务时间为工作日 09:00–18:00，非服务时间的留言顺延到下一个工作日。客服只会通过工单回复你。'
  },
  {
    id: 'report',
    question: '怎么举报违规问卷？',
    keywords: '举报 违规 下架 审核',
    answer: '在问卷填写页右上角点「举报」，填写理由后提交（需要登录）。管理员在「举报审核」里处理；通过后问卷会被下架，作者与举报人都会收到通知。'
  }
]

export default {
  components: { FeedbackThread },
  data() {
    return {
      faqKeyword: '',
      openedFaq: [],
      loading: false,
      myList: [],
      createVisible: false,
      creating: false,
      createForm: { subject: '', content: '' },
      createTitle: '发起客服工单',
      complaintVisible: false,
      complaintContent: '',
      complaintSubmitting: false,
      drawerVisible: false,
      activeTicket: null
    }
  },
  computed: {
    filteredFaq() {
      const keyword = this.faqKeyword.trim().toLowerCase()
      if (!keyword) return FAQ_ITEMS
      return FAQ_ITEMS.filter(item =>
        `${item.question}${item.answer}${item.keywords}`.toLowerCase().includes(keyword)
      )
    },
    serviceTickets() {
      return this.myList.filter(item => item.type === 'SERVICE')
    },
    hasOpenService() {
      return this.serviceTickets.some(item => item.status === 'OPEN')
    }
  },
  mounted() {
    this.loadTickets()
    const ticket = Number(this.$route.query.ticket)
    if (ticket) this.openFromQuery(ticket)
  },
  methods: {
    async loadTickets() {
      this.loading = true
      try {
        this.myList = await feedbackApi.my()
      } catch {
        // 失败提示由 http.js 统一弹出
      } finally {
        this.loading = false
      }
    },

    openCreate() {
      this.createTitle = '发起客服工单'
      this.createForm = { subject: '', content: '' }
      this.createVisible = true
    },

    openComplaint() {
      this.complaintContent = ''
      this.complaintVisible = true
    },

    /** 投诉直接写成一条 FEEDBACK，管理端在「反馈管理」里处理 */
    async submitComplaint() {
      const content = this.complaintContent.trim()
      if (!content) {
        ElMessage.warning('请填写投诉内容')
        return
      }
      this.complaintSubmitting = true
      try {
        await feedbackApi.create({
          type: 'FEEDBACK',
          subject: '投诉建议',
          content: `投诉建议：${content}`.slice(0, 2000),
          clientMsgId: newClientMsgId()
        })
        this.complaintVisible = false
        this.complaintContent = ''
        ElMessage.success('投诉已提交，管理员会核实处理')
      } catch {
        // 失败提示由 http.js 统一弹出
      } finally {
        this.complaintSubmitting = false
      }
    },

    async submitCreate() {
      const subject = this.createForm.subject.trim()
      const content = this.createForm.content.trim()
      if (!subject) {
        ElMessage.warning('请填写主题')
        return
      }
      if (!content) {
        ElMessage.warning('请填写问题描述')
        return
      }
      const hadOpen = this.hasOpenService
      this.creating = true
      try {
        const detail = await feedbackApi.create({
          type: 'SERVICE',
          subject,
          content,
          clientMsgId: newClientMsgId()
        })
        this.createVisible = false
        await this.loadTickets()
        this.$router.replace({ path: this.$route.path, query: { ticket: detail.feedback.id } })
        this.openTicket(detail.feedback)
        ElMessage.success(hadOpen ? '已追加到进行中的客服工单' : '客服工单已提交，客服会尽快回复')
      } catch {
        // 失败提示由 http.js 统一弹出
      } finally {
        this.creating = false
      }
    },

    openTicket(item) {
      this.activeTicket = item
      this.drawerVisible = true
    },

    /** 通知 link 进来直接打开；旧链接可能指向反馈工单，转去意见反馈 */
    async openFromQuery(id) {
      try {
        const data = await feedbackApi.detail(id, 0)
        const ticket = data.feedback
        if (ticket && ticket.type !== 'SERVICE') {
          this.$router.replace({ path: '/feedback', query: { ticket: id } })
          return
        }
        this.$router.replace({ path: this.$route.path })
        this.openTicket(ticket || { id, subject: '客服工单' })
      } catch {
        this.$router.replace({ path: this.$route.path })
      }
    },

    loadThread(id, afterId) {
      return feedbackApi.detail(id, afterId)
    },
    sendThread(id, content, clientMsgId) {
      return feedbackApi.send(id, content, clientMsgId)
    },
    closeThread(id) {
      return feedbackApi.close(id)
    },
    markRead(id) {
      return feedbackApi.read(id)
    },

    handleThreadUpdated() {
      this.loadTickets()
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
  color: var(--ink-3);
  line-height: 1.6;
}
.equal-height-row :deep(.el-col) {
  display: flex;
}
.equal-height-row :deep(.el-card) {
  width: 100%;
}

/* 帮助中心 */
.service-brief {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.brief-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--r-sm);
  background: var(--surface-2);
}
.brief-icon {
  margin-top: 2px;
  font-size: 16px;
  color: var(--sky-500);
  flex-shrink: 0;
}
.brief-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.brief-text {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink-3);
}
.faq-search {
  margin: 16px 0 10px;
}
.faq-list {
  border-top: none;
  border-bottom: none;
}
.faq-list :deep(.el-collapse-item__header) {
  border-bottom-color: var(--stroke);
  background: transparent;
  color: var(--ink);
  font-size: 13px;
}
.faq-list :deep(.el-collapse-item__wrap) {
  border-bottom-color: var(--stroke);
  background: transparent;
}
.faq-list :deep(.el-collapse-item__content) {
  padding-bottom: 12px;
}
.faq-question {
  font-size: 13px;
  color: var(--ink);
}
.faq-answer {
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-2);
}
.help-cta {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--stroke);
}
.cta-hint {
  margin-right: auto;
  font-size: 12px;
  color: var(--ink-3);
}

/* 我的客服会话 */
.session-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}
.ticket-list {
  display: flex;
  flex-direction: column;
}
.ticket-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 6px;
  border-bottom: 1px dashed var(--stroke);
  cursor: pointer;
  transition: background-color 0.16s var(--ease);
}
.ticket-row:last-child {
  border-bottom: none;
}
.ticket-row:hover {
  background: var(--sky-50);
}
html[data-theme="dark"] .ticket-row:hover {
  background: #16263f;
}
.ticket-unread {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
  background: transparent;
}
.ticket-unread.on {
  background: var(--app-danger);
}
.ticket-main {
  flex: 1;
  min-width: 0;
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
  margin-top: 3px;
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
.new-session-btn {
  width: 100%;
  margin-top: 14px;
}
.session-tip {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-3);
}
.dialog-tip {
  margin-bottom: 12px;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  background: var(--surface-2);
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-2);
}

@media (max-width: 900px) {
  .service-brief {
    grid-template-columns: 1fr;
  }
  .help-cta {
    flex-wrap: wrap;
  }
  .cta-hint {
    width: 100%;
    margin-right: 0;
  }
}
</style>
