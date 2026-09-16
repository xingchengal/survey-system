<template>
  <div>
    <!-- 顶部栏 -->
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h2 style="margin: 0;">📝 我的问卷</h2>
      <div style="display: flex; gap: 12px;">
        <el-button
          class="ai-btn"
          :icon="MagicStick"
          @click="openAiDialog"
        >
          AI 创建
        </el-button>
        <el-button
          type="primary"
          size="large"
          :icon="Plus"
          @click="openCreateDialog"
        >
          新建问卷
        </el-button>
      </div>
    </div>

    <!-- 筛选工具栏 -->
    <div style="display: flex; align-items: center; justify-content: flex-end; margin-bottom: 16px; padding: 12px 16px; background: #fff; border-radius: 8px;">
      <el-dropdown @command="handleSort">
        <span style="cursor: pointer; color: #606266; display: flex; align-items: center; font-size: 14px;">
          {{ sortText }}
          <el-icon style="margin-left: 4px;"><Sort /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="desc">时间倒序</el-dropdown-item>
            <el-dropdown-item command="asc">时间正序</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <el-divider direction="vertical" />

      <el-dropdown @command="handleStatus">
        <span style="cursor: pointer; color: #606266; display: flex; align-items: center; font-size: 14px;">
          {{ statusText }}
          <el-icon style="margin-left: 4px;"><Sort /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="all">全部状态</el-dropdown-item>
            <el-dropdown-item command="0">草稿</el-dropdown-item>
            <el-dropdown-item command="1">已发布</el-dropdown-item>
            <el-dropdown-item command="2">已结束</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <el-divider direction="vertical" />

      <div style="width: 280px;">
        <el-input
          v-model="keyword"
          placeholder="请输入问卷名进行搜索..."
          clearable
          :prefix-icon="Search"
        />
      </div>
    </div>

    <!-- 美化后的列表 -->
    <div v-if="filteredSurveys.length > 0" class="survey-list">
      <div class="list-header">
        <div class="col-title">问卷</div>
        <div class="col-status">状态</div>
        <div class="col-time">定时 / 截止</div>
        <div class="col-created">创建时间</div>
        <div class="col-actions">操作</div>
      </div>

      <div
        v-for="row in filteredSurveys"
        :key="row.id"
        class="list-row"
      >
        <div class="col-title">
          <div class="title-main">
            <span class="title-text">{{ row.title }}</span>
            <span class="id-badge">#{{ row.id }}</span>
          </div>
          <div class="title-desc">{{ row.description || '暂无描述' }}</div>
        </div>

        <div class="col-status">
          <span v-if="row.status === 1" class="badge badge-published">
            <span class="dot"></span> 已发布
          </span>
          <span v-else-if="row.status === 2" class="badge badge-ended">
            <span class="dot"></span> 已结束
          </span>
          <span v-else class="badge badge-draft">
            <span class="dot"></span> 草稿
          </span>
        </div>

        <div class="col-time">
          <div v-if="row.startTime" class="time-line time-start">
            <el-icon><Timer /></el-icon>
            <span>{{ row.startTime }}</span>
          </div>
          <div v-if="row.endTime" class="time-line time-end">
            <el-icon><AlarmClock /></el-icon>
            <span>{{ row.endTime }}</span>
          </div>
          <span v-if="!row.startTime && !row.endTime" class="time-empty">—</span>
        </div>

        <div class="col-created">
          <el-icon style="margin-right: 4px;"><Calendar /></el-icon>
          {{ row.createdTime }}
        </div>

        <div class="col-actions">
          <el-tooltip content="编辑" placement="top">
            <el-button type="primary" :icon="Edit" circle size="small" @click="handleEdit(row)" />
          </el-tooltip>
          <el-tooltip v-if="row.status === 0" content="发布" placement="top">
            <el-button type="success" :icon="Promotion" circle size="small" @click="handlePublish(row)" />
          </el-tooltip>
          <el-tooltip v-if="row.status === 1 || row.status === 2" content="分享" placement="top">
            <el-button type="warning" :icon="Share" circle size="small" @click="handleShare(row)" />
          </el-tooltip>
          <el-tooltip content="统计" placement="top">
            <el-button type="info" :icon="DataAnalysis" circle size="small" @click="handleStatistics(row)" />
          </el-tooltip>
          <el-tooltip content="复制" placement="top">
            <el-button color="#909399" :icon="CopyDocument" circle size="small" @click="handleCopy(row)" />
          </el-tooltip>
          <el-tooltip content="删除" placement="top">
            <el-button type="danger" :icon="Delete" circle size="small" @click="handleDelete(row)" />
          </el-tooltip>
        </div>
      </div>
    </div>

    <el-empty v-else description="没有匹配的问卷" />

    <!-- ========== AI 创建问卷对话框 ========== -->
    <el-dialog
      v-model="aiDialogVisible"
      width="620px"
      :close-on-click-modal="false"
      :show-close="!aiLoading"
    >
      <template #header>
        <div style="display: flex; align-items: center;">
          <el-icon style="color: #8e44ad; margin-right: 8px; font-size: 22px;"><MagicStick /></el-icon>
          <span style="font-weight: bold; font-size: 17px;">AI 创建问卷</span>
          <el-tag type="warning" size="small" style="margin-left: 12px;">Qwen2.5-7B</el-tag>
        </div>
      </template>

      <el-alert type="info" :closable="false" style="margin-bottom: 20px;">
        <template #title>
          <strong>💡 使用说明</strong>
        </template>
        输入问卷主题，AI 会自动生成问卷标题、描述和 5-8 道题目。生成后可以手动调整内容。
      </el-alert>

      <el-form :model="aiForm" label-width="90px">
        <el-form-item label="问卷主题" required>
          <el-input
            v-model="aiForm.topic"
            placeholder="例如：员工满意度调查、客户反馈问卷、课程评价..."
            maxlength="50"
            show-word-limit
            size="large"
          />
        </el-form-item>
        <el-form-item label="额外要求">
          <el-input
            v-model="aiForm.extra"
            type="textarea"
            :rows="3"
            placeholder="例如：题目要偏商务风、需要包含评分题、控制在5题以内...（选填）"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
      </el-form>

      <div v-if="aiLoading" class="ai-loading">
        <div class="ai-loading-icon">
          <el-icon class="is-loading" :size="40" color="#8e44ad"><Loading /></el-icon>
        </div>
        <div class="ai-loading-text">AI 正在思考中...</div>
        <div class="ai-loading-sub">通常需要 10-30 秒，请稍候</div>
      </div>

      <template #footer>
        <el-button @click="aiDialogVisible = false" :disabled="aiLoading">取消</el-button>
        <el-button
          class="ai-btn"
          @click="handleAiGenerate"
          :loading="aiLoading"
          :disabled="aiLoading"
        >
          ✨ 开始生成
        </el-button>
      </template>
    </el-dialog>

    <!-- ========== 新建问卷对话框（带模板） ========== -->
    <el-dialog v-model="createDialogVisible" title="新建问卷" width="900px" top="8vh">
      <el-tabs v-model="createTab">
        <el-tab-pane label="✏️ 空白创建" name="blank">
          <el-form :model="newForm" label-width="80px" style="margin-top: 20px; max-width: 500px;">
            <el-form-item label="标题">
              <el-input v-model="newForm.title" placeholder="请输入问卷标题" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="newForm.description" type="textarea" :rows="3" placeholder="请输入问卷描述（选填）" />
            </el-form-item>
          </el-form>
          <div style="text-align: right; margin-top: 20px;">
            <el-button @click="createDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="handleCreate">创建并编辑</el-button>
          </div>
        </el-tab-pane>

        <el-tab-pane label="📋 从模板创建" name="template">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 10px;">
            <!-- ★ 这里改成 :value -->
            <el-radio-group v-model="templateCategory" size="default">
              <el-radio-button value="全部">全部</el-radio-button>
              <el-radio-button
                v-for="cat in templateCategories"
                :key="cat.name"
                :value="cat.name"
              >
                {{ cat.name }} ({{ cat.count }})
              </el-radio-button>
            </el-radio-group>

            <div style="display: flex;">
              <el-input
                v-model="templateKeyword"
                placeholder="搜索模板名称..."
                clearable
                :prefix-icon="Search"
                style="width: 220px;"
                @keyup.enter="loadTemplates"
                @clear="loadTemplates"
              />
              <el-button type="primary" style="margin-left: 8px;" @click="loadTemplates">搜索</el-button>
            </div>
          </div>

          <div v-loading="templateLoading" style="min-height: 300px; max-height: 520px; overflow-y: auto; padding: 4px;">
            <div v-if="filteredTemplates.length > 0" class="template-grid">
              <div
                v-for="tpl in filteredTemplates"
                :key="tpl.id"
                class="template-card"
                @click="useTemplate(tpl)"
              >
                <div class="tpl-header">
                  <el-tag size="small" type="success">{{ tpl.templateCategory || '通用' }}</el-tag>
                </div>
                <h4 class="tpl-title">{{ tpl.title }}</h4>
                <p class="tpl-desc">{{ tpl.description || '暂无描述' }}</p>
                <div class="tpl-footer">
                  <span style="color: #409eff; font-size: 13px;">使用此模板 →</span>
                </div>
              </div>
            </div>
            <el-empty v-else description="没有匹配的模板" :image-size="80" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- ========== 分享对话框 ========== -->
    <el-dialog v-model="shareVisible" title="分享问卷" width="550px">
      <p style="color: #666;">把下面的链接发给需要填写的人，他们无需登录即可填写：</p>
      <el-input v-model="shareLink" readonly style="margin-top: 10px;">
        <template #append>
          <el-button @click="copyLink">复制链接</el-button>
        </template>
      </el-input>

      <div style="text-align: center; margin-top: 20px;">
        <p style="color: #666;">手机扫一扫直接填写：</p>
        <canvas ref="qrCanvas" style="border: 1px solid #eee; padding: 8px;"></canvas>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus, CopyDocument, Edit, Promotion, Share, DataAnalysis, Delete,
  Sort, Search, Calendar, Timer, AlarmClock, MagicStick, Loading
} from '@element-plus/icons-vue'
import QRCode from 'qrcode'

export default {
  components: { Plus, Search, MagicStick, Loading },
  data() {
    return {
      surveys: [],
      // 新建对话框
      createDialogVisible: false,
      createTab: 'blank',
      newForm: { title: '', description: '' },
      // 模板相关
      templateKeyword: '',
      templateCategory: '全部',
      templates: [],
      templateLoading: false,
      // AI 创建
      aiDialogVisible: false,
      aiForm: { topic: '', extra: '' },
      aiLoading: false,
      // 分享
      shareVisible: false,
      shareLink: '',
      // 筛选
      keyword: '',
      sortOrder: 'desc',
      statusFilter: 'all',

      // ★ 所有在模板里通过 :icon / :prefix-icon 使用的图标，都要在这里 markRaw 暴露
      Plus: markRaw(Plus),
      CopyDocument: markRaw(CopyDocument),
      Edit: markRaw(Edit),
      Promotion: markRaw(Promotion),
      Share: markRaw(Share),
      DataAnalysis: markRaw(DataAnalysis),
      Delete: markRaw(Delete),
      Sort: markRaw(Sort),
      Search: markRaw(Search),
      Calendar: markRaw(Calendar),
      Timer: markRaw(Timer),
      AlarmClock: markRaw(AlarmClock),
      MagicStick: markRaw(MagicStick),
      Loading: markRaw(Loading)
    }
  },
  computed: {
    sortText() {
      return this.sortOrder === 'desc' ? '时间倒序' : '时间正序'
    },
    statusText() {
      const map = { all: '状态', 0: '草稿', 1: '已发布', 2: '已结束' }
      return map[this.statusFilter] || '状态'
    },
    filteredSurveys() {
      let list = [...this.surveys]
      if (this.statusFilter !== 'all') {
        list = list.filter(s => s.status === Number(this.statusFilter))
      }
      if (this.keyword.trim()) {
        const kw = this.keyword.trim().toLowerCase()
        list = list.filter(s => (s.title || '').toLowerCase().includes(kw))
      }
      list.sort((a, b) => {
        const ta = new Date((a.createdTime || '').replace(/-/g, '/')).getTime() || 0
        const tb = new Date((b.createdTime || '').replace(/-/g, '/')).getTime() || 0
        return this.sortOrder === 'desc' ? tb - ta : ta - tb
      })
      return list
    },
    templateCategories() {
      const map = {}
      this.templates.forEach(t => {
        const cat = t.templateCategory || '其他'
        map[cat] = (map[cat] || 0) + 1
      })
      return Object.entries(map)
        .map(([name, count]) => ({ name, count }))
        .sort((a, b) => b.count - a.count)
    },
    filteredTemplates() {
      if (this.templateCategory === '全部') return this.templates
      return this.templates.filter(t => (t.templateCategory || '其他') === this.templateCategory)
    }
  },
  watch: {
    createTab(val) {
      if (val === 'template' && this.templates.length === 0) {
        this.loadTemplates()
      }
    }
  },
  mounted() {
    this.loadSurveys()
  },
  methods: {
    async loadSurveys() {
      try {
        const res = await fetch('/api/survey/my')
        this.surveys = await res.json()
      } catch {
        ElMessage.error('加载问卷失败')
      }
    },

    handleSort(command) { this.sortOrder = command },
    handleStatus(command) { this.statusFilter = command },

    // ============ 新建相关 ============
    openCreateDialog() {
      this.createDialogVisible = true
      this.createTab = 'blank'
      this.newForm = { title: '', description: '' }
      this.templateCategory = '全部'
    },

    async handleCreate() {
      if (!this.newForm.title.trim()) {
        ElMessage.warning('请输入问卷标题')
        return
      }
      const user = JSON.parse(localStorage.getItem('user') || '{}')
      try {
        const res = await fetch('/api/survey/add', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            userId: user.id,
            title: this.newForm.title,
            description: this.newForm.description,
            status: 0
          })
        })
        const data = await res.json()
        if (data.id) {
          ElMessage.success('创建成功，即将进入编辑页')
          this.createDialogVisible = false
          this.newForm = { title: '', description: '' }
          this.$router.push(`/my/surveys/${data.id}/edit`)
        }
      } catch {
        ElMessage.error('创建失败')
      }
    },

    // ============ AI 创建 ============
    openAiDialog() {
      this.aiDialogVisible = true
      this.aiForm = { topic: '', extra: '' }
    },

    async handleAiGenerate() {
      if (!this.aiForm.topic.trim()) {
        ElMessage.warning('请输入问卷主题')
        return
      }
      this.aiLoading = true
      try {
        const res = await fetch('/api/ai/generate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.aiForm)
        })
        const data = await res.json()
        if (data.success && data.surveyId) {
          ElMessage.success('AI 问卷生成成功！')
          this.aiDialogVisible = false
          this.$router.push(`/my/surveys/${data.surveyId}/edit`)
        } else {
          ElMessage.error(data.message || '生成失败')
        }
      } catch {
        ElMessage.error('请求异常，请检查后端日志')
      } finally {
        this.aiLoading = false
      }
    },

    // ============ 模板相关 ============
    async loadTemplates() {
      this.templateLoading = true
      try {
        const params = new URLSearchParams()
        if (this.templateKeyword.trim()) params.append('keyword', this.templateKeyword.trim())
        const res = await fetch(`/api/survey/templates?${params.toString()}`)
        this.templates = await res.json()
      } catch {
        ElMessage.error('加载模板失败')
      } finally {
        this.templateLoading = false
      }
    },

    async useTemplate(tpl) {
      try {
        const res = await fetch(`/api/survey/from-template/${tpl.id}`, { method: 'POST' })
        const data = await res.json()
        if (data && data.id) {
          ElMessage.success('已从模板创建，即将进入编辑页')
          this.createDialogVisible = false
          this.$router.push(`/my/surveys/${data.id}/edit`)
        } else {
          ElMessage.error('创建失败')
        }
      } catch {
        ElMessage.error('请求异常')
      }
    },

    // ============ 列表操作 ============
    handleCopy(row) {
      ElMessageBox.confirm(`确定复制「${row.title}」吗？`, '提示', {
        confirmButtonText: '确定复制',
        cancelButtonText: '取消',
        type: 'info'
      }).then(async () => {
        try {
          const res = await fetch(`/api/survey/copy/${row.id}`, { method: 'POST' })
          const data = await res.json()
          if (data && data.id) {
            ElMessage.success('复制成功，即将进入编辑页')
            this.$router.push(`/my/surveys/${data.id}/edit`)
          } else {
            ElMessage.error('复制失败')
          }
        } catch {
          ElMessage.error('请求异常')
        }
      }).catch(() => {})
    },

    handleEdit(row) {
      this.$router.push(`/my/surveys/${row.id}/edit`)
    },

    handlePublish(row) {
      ElMessageBox.confirm(`确定发布「${row.title}」吗？发布后将生成分享链接。`, '提示', {
        confirmButtonText: '确定发布',
        cancelButtonText: '取消',
        type: 'info'
      }).then(async () => {
        try {
          const res = await fetch(`/api/survey/publish/${row.id}`, { method: 'POST' })
          const data = await res.json()
          if (data && data.shareCode) {
            ElMessage.success('发布成功')
            this.loadSurveys()
          } else {
            ElMessage.error('发布失败')
          }
        } catch {
          ElMessage.error('请求异常')
        }
      }).catch(() => {})
    },

    handleShare(row) {
      if (!row.shareCode) {
        ElMessage.warning('该问卷还没有分享码')
        return
      }
      this.shareLink = `${window.location.origin}/s/${row.shareCode}`
      this.shareVisible = true
      this.$nextTick(() => {
        QRCode.toCanvas(this.$refs.qrCanvas, this.shareLink, {
          width: 200,
          margin: 1,
          color: { dark: '#000', light: '#fff' }
        }, (error) => {
          if (error) console.error(error)
        })
      })
    },

    copyLink() {
      navigator.clipboard.writeText(this.shareLink).then(() => {
        ElMessage.success('链接已复制')
      }).catch(() => {
        ElMessage.warning('复制失败，请手动选择复制')
      })
    },

    handleStatistics(row) {
      this.$router.push(`/my/surveys/${row.id}/statistics`)
    },

    handleDelete(row) {
      ElMessageBox.confirm(`确定将「${row.title}」移入回收站吗？可以在回收站里恢复。`, '提示', {
        confirmButtonText: '移入回收站',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          const res = await fetch(`/api/survey/recycle/${row.id}`, { method: 'PUT' })
          const msg = await res.text()
          if (msg === '已移入回收站') {
            ElMessage.success('已移入回收站')
            this.loadSurveys()
          } else {
            ElMessage.error(msg)
          }
        } catch {
          ElMessage.error('操作失败')
        }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
/* ============== AI 按钮 ============== */
.ai-btn {
  background: linear-gradient(135deg, #8e44ad 0%, #667eea 50%, #3a7bd5 100%);
  background-size: 200% 200%;
  color: #fff !important;
  border: none !important;
  font-weight: 600;
  height: 40px;
  padding: 0 22px;
  border-radius: 8px;
  position: relative;
  overflow: hidden;
  transition: all 0.3s ease;
  animation: aiGradientShift 6s ease infinite;
}

.ai-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(102, 126, 234, 0.45);
  color: #fff !important;
}

.ai-btn::before {
  content: '';
  position: absolute;
  top: 0;
  left: -100%;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.35), transparent);
  transition: left 0.6s ease;
}

.ai-btn:hover::before {
  left: 100%;
}

@keyframes aiGradientShift {
  0%, 100% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
}

.ai-loading {
  text-align: center;
  padding: 20px 0;
}
.ai-loading-icon { margin-bottom: 12px; }
.ai-loading-text {
  font-size: 15px;
  font-weight: 500;
  color: #303133;
}
.ai-loading-sub {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

/* ============== 列表样式 ============== */
.survey-list {
  background: #fff;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
}

.list-header {
  display: grid;
  grid-template-columns: 3fr 110px 200px 180px 230px;
  padding: 14px 24px;
  background: #fafbfc;
  border-bottom: 1px solid #ebeef5;
  font-size: 13px;
  color: #909399;
  font-weight: 500;
}

.list-row {
  display: grid;
  grid-template-columns: 3fr 110px 200px 180px 230px;
  padding: 18px 24px;
  border-bottom: 1px solid #f5f7fa;
  align-items: center;
  transition: all 0.2s ease;
  position: relative;
}

.list-row:last-child { border-bottom: none; }

.list-row::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: transparent;
  transition: background 0.2s;
}

.list-row:hover { background: #f8fafc; }
.list-row:hover::before { background: #409eff; }

.col-title { min-width: 0; padding-right: 16px; }

.title-main {
  display: flex;
  align-items: center;
  margin-bottom: 4px;
}

.title-text {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-right: 8px;
}

.id-badge {
  font-size: 11px;
  color: #909399;
  background: #f0f2f5;
  padding: 1px 6px;
  border-radius: 4px;
  flex-shrink: 0;
}

.title-desc {
  font-size: 13px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.col-status { display: flex; justify-content: center; }

.badge {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 12px;
  font-weight: 500;
}

.badge .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 5px;
}

.badge-published { background: #f0f9eb; color: #67c23a; }
.badge-published .dot { background: #67c23a; }

.badge-draft { background: #fdf6ec; color: #e6a23c; }
.badge-draft .dot { background: #e6a23c; }

.badge-ended { background: #fef0f0; color: #f56c6c; }
.badge-ended .dot { background: #f56c6c; }

.col-time { font-size: 12px; }

.time-line {
  display: flex;
  align-items: center;
  margin-bottom: 4px;
  color: #909399;
}

.time-line:last-child { margin-bottom: 0; }

.time-line .el-icon {
  margin-right: 5px;
  font-size: 13px;
}

.time-start { color: #67c23a; }
.time-end { color: #f56c6c; }

.time-empty {
  color: #dcdfe6;
  font-size: 14px;
}

.col-created {
  display: flex;
  align-items: center;
  font-size: 13px;
  color: #909399;
}

.col-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}

.col-actions .el-button { margin: 0; }

/* ============== 模板卡片 ============== */
.template-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.template-card {
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.25s ease;
  background: #fff;
  display: flex;
  flex-direction: column;
  min-height: 150px;
}

.template-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
  border-color: #409eff;
}

.tpl-header { margin-bottom: 8px; }

.tpl-title {
  margin: 0 0 6px 0;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tpl-desc {
  margin: 0 0 auto 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  display: -webkit-box;
  line-clamp: 2;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 36px;
}

.tpl-footer {
  margin-top: 12px;
  text-align: right;
  opacity: 0;
  transition: opacity 0.25s;
}

.template-card:hover .tpl-footer { opacity: 1; }

@media (max-width: 1100px) {
  .list-header, .list-row {
    grid-template-columns: 2fr 100px 180px 150px 200px;
    padding: 14px 16px;
  }
  .template-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>