<template>
  <div>
    <div class="page-head">
      <div>
        <h2 class="page-title">意见反馈</h2>
        <p class="page-subtitle">
          你的意见会直接给到管理员。提交即完成，不需要等待回复；
          需要人工协助、跟进处理进度或投诉，请到「客服中心」。
        </p>
      </div>
      <el-button link type="primary" @click="$router.push('/support')">去客服中心 →</el-button>
    </div>

    <el-card class="feedback-card">
      <el-result
        v-if="submitted"
        icon="success"
        title="已收到你的反馈"
        sub-title="感谢反馈。管理员会在后台逐条查看，有回复时会通过消息中心通知你。"
      >
        <template #extra>
          <el-button type="primary" @click="writeAnother">再提一条</el-button>
          <el-button @click="$router.push('/support')">去客服中心</el-button>
        </template>
      </el-result>

      <template v-else>
        <div class="form-block">
          <div class="block-label">
            你对整体体验满意吗？<span class="optional">选填</span>
          </div>
          <el-rate v-model="form.rating" :max="5" />
        </div>

        <div class="form-block">
          <div class="block-label">
            你想提供什么意见或建议？<span class="required">必填</span>
          </div>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="8"
            maxlength="2000"
            show-word-limit
            resize="none"
            placeholder="例如：统计页希望能按题筛选；导出 Excel 时表头能固定；某个按钮不好找……"
          />
        </div>

        <div class="form-footer">
          <span class="form-hint">提交后即完成，管理员回复会通过消息中心通知你。</span>
          <el-button
            type="primary"
            :loading="creating"
            :disabled="!form.content.trim()"
            @click="submit"
          >
            提交反馈
          </el-button>
        </div>
      </template>
    </el-card>

    <!-- 从消息中心通知进入：只读查看管理员回复，不提供对话与轮询 -->
    <feedback-thread
      v-model="drawerVisible"
      :ticket-id="activeTicket ? activeTicket.id : 0"
      :initial-ticket="activeTicket"
      :readonly="true"
      :poll-interval="0"
      variant="feedback"
      :loader="loadThread"
      :reader="markRead"
    />
  </div>
</template>

<script>
// ★ 意见箱：一屏提交即完成，不列工单、不轮询；管理员回复通过消息中心通知，点进来只读查看。
import { ElMessage } from 'element-plus'
import FeedbackThread from '../components/FeedbackThread.vue'
import { feedback as feedbackApi } from '../api'
import { newClientMsgId } from '../api/feedback'

const MAX_CONTENT = 2000

export default {
  components: { FeedbackThread },
  data() {
    return {
      form: { rating: 0, content: '' },
      creating: false,
      submitted: false,
      drawerVisible: false,
      activeTicket: null
    }
  },
  mounted() {
    const ticket = Number(this.$route.query.ticket)
    if (ticket) this.openFromQuery(ticket)
  },
  methods: {
    async submit() {
      const content = this.form.content.trim()
      if (!content) {
        ElMessage.warning('请写下你的意见或建议')
        return
      }
      // 评分拼进正文，主题自动生成；后端仍是一条普通 FEEDBACK，不改表、不加端点
      const rating = Number(this.form.rating) || 0
      const prefix = rating ? `满意度：${rating}/5\n建议：` : ''
      const subject = rating ? `意见反馈（满意度 ${rating} 星）` : '意见反馈'
      const body = prefix + content.slice(0, Math.max(0, MAX_CONTENT - prefix.length))
      this.creating = true
      try {
        await feedbackApi.create({
          type: 'FEEDBACK',
          subject,
          content: body,
          clientMsgId: newClientMsgId()
        })
        this.form = { rating: 0, content: '' }
        this.submitted = true
        ElMessage.success('已收到你的反馈')
      } catch {
        // 失败提示由 http.js 统一弹出
      } finally {
        this.creating = false
      }
    },

    writeAnother() {
      this.submitted = false
    },

    /** 通知 link 进来直接打开；旧链接可能指向已拆到客服中心的 SERVICE 工单 */
    async openFromQuery(id) {
      try {
        const data = await feedbackApi.detail(id, 0)
        const ticket = data.feedback
        if (ticket && ticket.type === 'SERVICE') {
          this.$router.replace({ path: '/support', query: { ticket: id } })
          return
        }
        this.$router.replace({ path: this.$route.path })
        this.activeTicket = ticket || { id, subject: '工单详情' }
        this.drawerVisible = true
      } catch {
        this.$router.replace({ path: this.$route.path })
      }
    },

    loadThread(id, afterId) {
      return feedbackApi.detail(id, afterId)
    },
    markRead(id) {
      return feedbackApi.read(id)
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

.feedback-card {
  max-width: 760px;
  margin: 0 auto;
}
.feedback-card :deep(.el-card__body) {
  padding: 28px 30px;
}

.form-block {
  margin-bottom: 22px;
}
.block-label {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.block-label .optional,
.block-label .required {
  margin-left: 6px;
  font-size: 11px;
  font-weight: 400;
  color: var(--ink-3);
}
.block-label .required {
  color: var(--app-danger);
}

.form-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding-top: 18px;
  border-top: 1px dashed var(--stroke);
}
.form-hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-3);
}

@media (max-width: 640px) {
  .feedback-card :deep(.el-card__body) {
    padding: 20px 16px;
  }
  .form-footer {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
