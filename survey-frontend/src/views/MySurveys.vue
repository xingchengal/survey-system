<template>
  <div>
    <!-- 顶部栏 -->
    <div class="page-header">
      <div>
        <h2 class="page-title">我的问卷</h2>
        <p class="page-subtitle">共 {{ surveys.length }} 份 · 创建、发布、分享与查看数据</p>
      </div>
      <div class="header-actions">
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
    <div class="toolbar-card">
      <el-dropdown @command="handleSort">
        <span class="toolbar-trigger">
          {{ sortText }}
          <el-icon class="ml-4"><Sort /></el-icon>
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
        <span class="toolbar-trigger">
          {{ statusText }}
          <el-icon class="ml-4"><Sort /></el-icon>
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

      <div class="toolbar-search">
        <el-input
          v-model="keyword"
          placeholder="请输入问卷名进行搜索..."
          clearable
          :prefix-icon="Search"
        />
      </div>
    </div>

    <!-- 骨架屏：首屏加载时占位，避免白屏闪烁 -->
    <div v-if="loading" class="list-skeleton">
      <div class="sk-block" style="height: 54px; margin-bottom: 12px;"></div>
      <div v-for="i in 5" :key="i" class="sk-block" style="height: 80px; margin-bottom: 10px;"></div>
    </div>

    <template v-else>
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
        v-for="row in pagedSurveys"
        :key="row.id"
        class="list-row"
      >
        <div class="col-title">
          <div class="title-main">
            <span class="title-text">{{ row.title }}</span>
            <span class="id-badge">#{{ row.id }}</span>
            <!-- ★ 作者视角：哪几份已经共享出去了（数据来自 GET /api/survey/my 的 collaboratorCount） -->
            <span v-if="row.collaboratorCount > 0" class="shared-badge">🤝 已共享 {{ row.collaboratorCount }} 人</span>
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
          <el-icon class="mr-4"><Calendar /></el-icon>
          <!-- ★ 包一层 span 才能做"超出省略号"：grid 单元格不裁剪内容，
               日期文本会溢出到右侧操作列、被按钮压在下面（实测截图就是这样重叠的）。 -->
          <span class="created-text">{{ row.createdTime }}</span>
        </div>

        <div class="col-actions">
          <el-button class="row-btn rb-edit" :icon="Edit" @click="handleEdit(row)">
            <span class="btn-label">编辑</span>
          </el-button>
          <!-- ★ 只要不是"已发布"就给发布入口，否则会出现"后端能救、界面无路可走"的 UI 死路
                （后端是允许"延长（或清空）截止时间后重新发布"的）：
                  · 草稿（含 status 为 NULL 的历史数据，后端一律视为草稿）→ 发布
                  · 已结束 → 重新发布；若截止时间还在过去，后端会返回明确文案
                    「问卷已结束，如需重新开放请先延长结束时间」，由 http.js 弹出
                status 的判定与上面徽标的口径一致（NULL 也算草稿）。 -->
          <el-tooltip v-if="row.status !== 1" :content="row.status === 2 ? '重新发布（需先延长截止时间）' : '发布'" placement="top">
            <el-button class="row-btn rb-publish" :icon="Promotion" @click="handlePublish(row)">
              <span class="btn-label">{{ row.status === 2 ? '重新发布' : '发布' }}</span>
            </el-button>
          </el-tooltip>
          <el-button v-if="row.status === 1 || row.status === 2" class="row-btn rb-share" :icon="Share" @click="handleShare(row)">
            <span class="btn-label">分享</span>
          </el-button>
          <el-button class="row-btn rb-stats" :icon="DataAnalysis" @click="handleStatistics(row)">
            <span class="btn-label">统计</span>
          </el-button>
          <el-button class="row-btn rb-copy" :icon="CopyDocument" @click="handleCopy(row)">
            <span class="btn-label">复制</span>
          </el-button>
          <!-- ★ 只有作者/管理员能管名单（后端判）；别人的问卷这里点了也会 400，所以先按"是不是自己的"显示 -->
          <el-button v-if="row.userId === currentUserId()" class="row-btn rb-collab" :icon="Connection" @click="openCollaborators(row)">
            <span class="btn-label">协作</span>
          </el-button>
          <el-button class="row-btn rb-delete" :icon="Delete" @click="handleDelete(row)">
            <span class="btn-label">删除</span>
          </el-button>
        </div>
      </div>
    </div>

    <!-- ★ 共享给我的问卷（H2 协作）：没有这块，被邀请的人根本找不到入口 -->
    <div v-if="sharedSurveys.length" class="shared-block mb-16">
      <div class="shared-title">🤝 共享给我的问卷（{{ sharedSurveys.length }}）</div>
      <div v-for="item in sharedSurveys" :key="item.id" class="shared-row">
        <span class="shared-name">{{ item.title }}<span class="id-badge">#{{ item.id }}</span></span>
        <span class="shared-role">{{ item.status === 1 ? '已发布' : '未发布' }}</span>
        <el-button size="small" type="primary" plain @click="handleEdit(item)">打开</el-button>
        <el-button size="small" @click="handleStatistics(item)">统计</el-button>
        <el-button size="small" type="warning" plain @click="openApply(item)">申请权限</el-button>
        <el-button size="small" type="danger" plain @click="leaveCollaboration(item)">退出</el-button>
      </div>
    </div>

    <!-- 分页：与表格页同一套 .pager-row + el-pagination 胶囊风格（数据是前端全量拿的，这里只切页） -->
    <div v-if="filteredSurveys.length > 0" class="pager-row">
      <el-pagination
        :current-page="pageNum"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="filteredSurveys.length"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>

    <el-empty v-if="filteredSurveys.length === 0" description="没有匹配的问卷" />
    </template>

    <!-- ========== 申请变更权限（被邀请人） ========== -->
    <el-dialog v-model="applyVisible" title="申请变更权限" width="430px">
      <p class="apply-tip">
        问卷：{{ applySurvey && applySurvey.title }}<br />
        请选择你希望申请的角色，提交后作者会收到通知。
      </p>
      <el-select v-model="applyRole" style="width: 100%;">
        <el-option label="只读（viewer）—— 只能看问卷" value="viewer" />
        <el-option label="可编辑（editor）—— 能改内容，不能删除/发布" value="editor" />
        <el-option label="只看数据（analyst）—— 只能看统计" value="analyst" />
      </el-select>
      <template #footer>
        <el-button @click="applyVisible = false">取消</el-button>
        <el-button type="primary" :loading="applying" @click="submitApply">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- ========== 协作者管理（作者/管理员） ========== -->
    <el-dialog v-model="collabVisible" title="协作者" width="560px">
      <div class="collab-add">
        <el-input v-model="collabForm.username" placeholder="对方的用户名" style="width: 190px;" />
        <el-select v-model="collabForm.role" style="width: 150px;">
          <el-option label="只读（viewer）" value="viewer" />
          <el-option label="可编辑（editor）" value="editor" />
          <el-option label="只看数据（analyst）" value="analyst" />
        </el-select>
        <el-button type="primary" @click="addCollaborator">邀请</el-button>
      </div>
      <el-table :data="collaborators" size="small" empty-text="还没有协作者">
        <el-table-column label="成员">
          <template #default="{ row }">{{ row.nickname || row.username }}（{{ row.username }}）</template>
        </el-table-column>
        <el-table-column label="角色" width="160">
          <template #default="{ row }">
            <el-select :model-value="row.collaboratorRole" size="small" @change="(v) => changeCollaboratorRole(row, v)">
              <el-option label="只读 viewer" value="viewer" />
              <el-option label="可编辑 editor" value="editor" />
              <el-option label="只看数据 analyst" value="analyst" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeCollaborator(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="collab-hint">只读 = 只能看问卷；可编辑 = 能改内容、不能删除；只看数据 = 只能看统计。<br />
        被邀请人会收到站内通知；对方也可以在自己的「共享给我」里主动退出。</p>
    </el-dialog>

    <!-- ========== AI 创建问卷对话框 ========== -->
    <el-dialog
      v-model="aiDialogVisible"
      width="620px"
      :close-on-click-modal="false"
      :show-close="!aiLoading"
    >
      <template #header>
        <div class="flex-center">
          <el-icon style="color: #8e44ad; margin-right: 8px; font-size: 22px;"><MagicStick /></el-icon>
          <span style="font-weight: bold; font-size: 17px;">AI 创建问卷</span>
          <el-tag type="warning" size="small" style="margin-left: 12px;">Qwen2.5-7B</el-tag>
        </div>
      </template>

      <el-alert class="mb-20" type="info" :closable="false">
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
          <el-form class="form-panel" :model="newForm" label-width="80px">
            <el-form-item label="标题">
              <!-- ★ maxlength 与后端 TextLimits.SURVEY_TITLE(200) / SURVEY_DESCRIPTION(500) 对齐，否则超长只有提交后吃 400 -->
              <el-input v-model="newForm.title" placeholder="请输入问卷标题" maxlength="200" show-word-limit />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="newForm.description" type="textarea" :rows="3" placeholder="请输入问卷描述（选填）" maxlength="500" show-word-limit />
            </el-form-item>
          </el-form>
          <div style="text-align: right; margin-top: 20px;">
            <el-button @click="createDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="handleCreate">创建并编辑</el-button>
          </div>
        </el-tab-pane>

        <el-tab-pane label="📋 从模板创建" name="template">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 10px;">
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
              <el-button class="ml-8" type="primary" @click="loadTemplates">搜索</el-button>
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
      <p class="text-secondary">把下面的链接发给需要填写的人，他们无需登录即可填写：</p>
      <el-input v-model="shareLink" readonly style="margin-top: 10px;">
        <template #append>
          <el-button @click="copyLink">复制链接</el-button>
        </template>
      </el-input>

      <div style="text-align: center; margin-top: 20px;">
        <p class="text-secondary">手机扫一扫直接填写：</p>
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
  Search, MagicStick, Connection } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
// ★ 统一接口层：本页请求一律走 src/api/（改法与 AdminLayout.vue 一致）。
//   http.js 统一带 Authorization: Bearer token、统一按 HTTP 状态码判成败、统一弹后端 message，
//   页面只关心「调哪个接口、拿到什么数据」。
//   路径说明：本文件在 src/views/ 下，api 在 src/api/，所以是 '../api'。
import api from '../api'
import { getUser } from '../utils/auth'
import collaboratorApi from '../api/collaborator'
// 页码记忆（sessionStorage）：从统计/编辑页返回时仍停在第 N 页
import { readPageNum, savePageNum } from '../utils/pageMemory'

/** 本页在 sessionStorage 里的页码键 */
const PAGE_KEY = 'MySurveys'

// 图标已在 main.js 里全局注册，这里不再重复注册
// （模板标签走全局注册；:prefix-icon="Search" 这种属性变量走本文件顶部的 import，两者都不受影响）
export default {
  data() {
    return {
      surveys: [],
      /** 首次加载骨架屏（数据回来即关，失败也关，避免一直闪） */
      loading: true,
      /** 共享给我的问卷（H2） */
      sharedSurveys: [],
      /** 协作者弹窗 */
      collabVisible: false,
      collabSurveyId: null,
      collaborators: [],
      collabForm: { username: '', role: 'viewer' },
      /** 申请变更权限弹窗（用下拉，不用手填） */
      applyVisible: false,
      applySurvey: null,
      applyRole: 'editor',
      applying: false,
      /** 列表静默刷新的定时器 / 可见性监听（定时任务每分钟才改 status，列表不能只取一次） */
      refreshTimer: null,
      visibilityHandler: null,
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
      // 列表分页：问卷是 api.survey.my() 一次性拿回来的，这里只做**前端分页**
      // （不新增后端分页接口）；页码按 sessionStorage 记忆，与表格页同一套工具
      pageNum: 1,
      pageSize: 10,

      // ★ 所有在模板里通过 :icon / :prefix-icon 使用的图标，都要在这里 markRaw 暴露
      Plus: markRaw(Plus),
      CopyDocument: markRaw(CopyDocument),
      Edit: markRaw(Edit),
      Promotion: markRaw(Promotion),
      Share: markRaw(Share),
      DataAnalysis: markRaw(DataAnalysis),
      Connection: markRaw(Connection),
      Delete: markRaw(Delete),
      Search: markRaw(Search),
      MagicStick: markRaw(MagicStick)
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
        // ★ 不能用**严格相等** `s.status === Number(statusFilter)`：全项目的口径是
        //   "**NULL 视为草稿**"（common/SurveyStatus.isDraft），而 NULL !== 0，
        //   否则会出现"列表里明明标着草稿、点'草稿'筛选却找不到它"。
        //   这里按后端同一口径：选草稿时同时收 NULL。
        const want = Number(this.statusFilter)
        list = list.filter(s => (want === 0 ? (s.status == null || s.status === 0) : s.status === want))
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
    },
    /** 当前页要渲染的问卷（筛选 + 排序之后切片） */
    pagedSurveys() {
      const start = (this.pageNum - 1) * this.pageSize
      return this.filteredSurveys.slice(start, start + this.pageSize)
    }
  },
  watch: {
    createTab(val) {
      if (val === 'template' && this.templates.length === 0) {
        this.loadTemplates()
      }
    },
    // 搜索 / 筛选 / 排序一变就回第 1 页，否则会停在"第 3 页但只剩 1 页"的空页上
    keyword() {
      this.resetPage()
    },
    statusFilter() {
      this.resetPage()
    },
    sortOrder() {
      this.resetPage()
    },
    // 删除 / 刷新后列表变短：把页码夹回最后一页
    'filteredSurveys.length'(len) {
      const maxPage = Math.max(1, Math.ceil(len / this.pageSize))
      if (this.pageNum > maxPage) {
        this.pageNum = maxPage
        savePageNum(PAGE_KEY, maxPage)
      }
    }
  },
  mounted() {
    // 恢复上次停留的页码（从统计/编辑页返回时不会被弹回第 1 页）
    this.pageNum = readPageNum(PAGE_KEY)
    this.loadSurveys()
    this.loadSharedSurveys()
    this.startAutoRefresh()
    // 从看板快捷入口跳过来时直接打开对应弹窗：?ai=1 / ?create=1
    // ★ 打开后把 query 清掉，避免刷新页面又弹一次
    if (this.$route.query.ai) {
      this.openAiDialog()
      this.$router.replace({ path: this.$route.path })
    } else if (this.$route.query.create) {
      this.openCreateDialog()
      this.$router.replace({ path: this.$route.path })
    }
  },
  beforeUnmount() {
    this.stopAutoRefresh()
  },
  methods: {
    /**
     * 静默刷新列表（不碰 loading / 分页 / 弹窗）。
     *
     * ★ 为什么需要：问卷的「已结束」是后端**定时任务每分钟**扫一次改出来的
     *   （SurveyService.autoPublishAndClose，cron 每分钟第 0 秒），
     *   而列表只在 mounted 时取一次 —— 于是"截止时间到了，页面还显示已发布"，
     *   看起来两边不同步（用户实测报障）。
     * ★ 频率取 30 秒：后端是每分钟第 0 秒扫一次，取一半周期，
     *   这样"到点/收满"在页面上最多晚 30 秒出现（再密就是白刷）。
     */
    async refreshSilently() {
      try {
        this.surveys = await api.survey.my()
      } catch {
        // 静默：定时刷新失败不打扰用户（下次再刷）
      }
    },

    /** 定时 + 窗口重新可见时各刷一次（切走再回来是最常见的"看到旧数据"场景） */
    startAutoRefresh() {
      this.stopAutoRefresh()
      this.refreshTimer = setInterval(() => {
        if (document.visibilityState === 'visible') this.refreshSilently()
      }, 30 * 1000)
      this.visibilityHandler = () => {
        if (document.visibilityState === 'visible') this.refreshSilently()
      }
      document.addEventListener('visibilitychange', this.visibilityHandler)
    },

    stopAutoRefresh() {
      if (this.refreshTimer) {
        clearInterval(this.refreshTimer)
        this.refreshTimer = null
      }
      if (this.visibilityHandler) {
        document.removeEventListener('visibilitychange', this.visibilityHandler)
        this.visibilityHandler = null
      }
    },

    async loadSurveys() {
      //   只保留 try/catch 避免非 2xx 抛出的 ApiError 变成未捕获异常。
      try {
        this.surveys = await api.survey.my()
      } catch {
        // 提示已由接口层统一弹出；这里什么都不做，列表保持原样，用户可手动刷新重试
      } finally {
        this.loading = false
      }
    },

    /** 当前用户 id（只用于"这条问卷是不是我的"的按钮显隐；后端仍会校验） */
    currentUserId() {
      const user = getUser()
      return user ? user.id : null
    },

    /** 共享给我的问卷（静默失败：没协作者时接口就是空数组） */
    async loadSharedSurveys() {
      try {
        this.sharedSurveys = await collaboratorApi.shared()
      } catch {
        this.sharedSurveys = []
      }
    },

    async openCollaborators(row) {
      this.collabSurveyId = row.id
      this.collabForm = { username: '', role: 'viewer' }
      this.collabVisible = true
      await this.reloadCollaborators()
    },

    async reloadCollaborators() {
      try {
        this.collaborators = await collaboratorApi.list(this.collabSurveyId)
      } catch {
        this.collaborators = []
      }
    },

    async addCollaborator() {
      if (!this.collabForm.username.trim()) {
        ElMessage.warning('请输入对方的用户名')
        return
      }
      await collaboratorApi.invite(this.collabSurveyId, this.collabForm.username.trim(), this.collabForm.role)
      ElMessage.success('已邀请')
      this.collabForm.username = ''
      await this.reloadCollaborators()
    },

    async removeCollaborator(row) {
      try {
        await this.$confirm(`把 ${row.nickname || row.username} 从协作者里移除？`, '移除协作者', { type: 'warning' })
      } catch {
        return // 用户取消
      }
      await collaboratorApi.remove(this.collabSurveyId, row.userId)
      ElMessage.success('已移除')
      await this.reloadCollaborators()
    },

    async changeCollaboratorRole(row, role) {
      if (role === row.collaboratorRole) return
      await collaboratorApi.changeRole(this.collabSurveyId, row.userId, role)
      ElMessage.success('已改为 ' + role)
      await this.reloadCollaborators()
    },

    /** 打开"申请变更权限"弹窗（下拉选角色，避免手填写错） */
    openApply(item) {
      this.applySurvey = item
      this.applyRole = 'editor'
      this.applyVisible = true
    },

    async submitApply() {
      this.applying = true
      try {
        await collaboratorApi.apply(this.applySurvey.id, this.applyRole)
        ElMessage.success('已提交申请，作者会收到通知')
        this.applyVisible = false
      } finally {
        this.applying = false
      }
    },

    async leaveCollaboration(item) {
      try {
        await this.$confirm(`退出协作《${item.title}》？退出后这份问卷会从"共享给我"里消失。`, '退出协作', { type: 'warning' })
      } catch {
        return
      }
      await collaboratorApi.leave(item.id)
      ElMessage.success('已退出协作')
      await this.loadSharedSurveys()
    },

    handleSort(command) { this.sortOrder = command },
    handleStatus(command) { this.statusFilter = command },

    /** 列表分页：翻页时记页码；筛选变化时由 watch 调 resetPage 回到第 1 页 */
    handlePageChange(page) {
      this.pageNum = page
      savePageNum(PAGE_KEY, page)
    },
    handleSizeChange(size) {
      this.pageSize = size
      this.pageNum = 1
      savePageNum(PAGE_KEY, 1)
    },
    resetPage() {
      this.pageNum = 1
      savePageNum(PAGE_KEY, 1)
    },

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
      // ★ 请求体**不传 userId**（也不需要读本地用户）：归属由服务端按 JWT 里的 X-User-Id 决定，
      //   客户端传什么都会被覆盖。
      try {
        // ★ 请求体只留真正的业务字段：userId 与 status 都被服务端**无条件忽略/覆盖**
        //   （归属取 JWT 里的 X-User-Id；status/shareCode/isTemplate/deleted 由 createSurvey 强制归零），
        //   传了会让人误以为"客户端能指定归属或初始状态"。
        const data = await api.survey.add({
          title: this.newForm.title,
          description: this.newForm.description
        })
        if (data.id) {
          ElMessage.success('创建成功，即将进入编辑页')
          this.createDialogVisible = false
          this.newForm = { title: '', description: '' }
          this.$router.push(`/my/surveys/${data.id}/edit`)
        }
      } catch {
        // 失败提示由接口层统一弹出；这里不关弹窗，用户可以改完标题再试
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
      // ★ 慢接口：后端 OkHttp 的 read 超时是 120 秒（配置在 service/QwenClient.java），
      //   免费额度排队时可能等十几秒 —— 接口层没有设任何超时，页面这里保留 aiLoading 转圈提示，
      //   绝不能加 AbortController / 短超时。
      this.aiLoading = true
      try {
        // ★ api.ai.generate(topic, extra) 已经与后端契约对齐（后端读的是 topic / extra 两个字段）。
        const data = await api.ai.generate(this.aiForm.topic, this.aiForm.extra)
        ElMessage.success('AI 问卷生成成功！')
        this.aiDialogVisible = false
        this.$router.push(`/my/surveys/${data.surveyId}/edit`)
      } catch {
        // 网络异常 / 4xx / 5xx 的提示已由接口层统一弹出
      } finally {
        this.aiLoading = false
      }
    },

    // ============ 模板相关 ============
    async loadTemplates() {
      this.templateLoading = true
      try {
        this.templates = await api.survey.templates({ keyword: this.templateKeyword.trim() })
      } catch {
        // 加载模板失败：提示由接口层统一弹出
      } finally {
        this.templateLoading = false
      }
    },

    async useTemplate(tpl) {
      try {
        const data = await api.survey.fromTemplate(tpl.id)
        if (data && data.id) {
          ElMessage.success('已从模板创建，即将进入编辑页')
          this.createDialogVisible = false
          this.$router.push(`/my/surveys/${data.id}/edit`)
        } else {
          // 保留：必须拿到新问卷 id 才能跳编辑页，拿不到就按失败提示（这是业务字段判断，不是中文比对）
          ElMessage.error('创建失败')
        }
      } catch {
        // 请求失败提示由接口层统一弹出
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
          const data = await api.survey.copy(row.id)
          if (data && data.id) {
            ElMessage.success('复制成功，即将进入编辑页')
            this.$router.push(`/my/surveys/${data.id}/edit`)
          } else {
            ElMessage.error('复制失败')
          }
        } catch {
          // 提示由接口层统一弹出
        }
      }).catch(() => {})
    },

    handleEdit(row) {
      this.$router.push(`/my/surveys/${row.id}/edit`)
    },

    handlePublish(row) {
      // ★ 已结束的问卷也能走这里，但**必须先延长/清空截止时间**（后端 SurveyService.publish
      //   会校验 endTime 是否已到未来）。与其让用户点了才吃 400，不如先告诉他该做什么。
      if (row.status === 2 && row.endTime && new Date(String(row.endTime).replace(/-/g, '/')).getTime() <= Date.now()) {
        ElMessageBox.confirm(
          `「${row.title}」已过截止时间（${row.endTime}）。要重新开放，需要先把截止时间改到未来（或清空）。现在去编辑页修改吗？`,
          '该问卷已结束',
          { confirmButtonText: '去修改截止时间', cancelButtonText: '取消', type: 'warning' }
        ).then(() => {
          this.$router.push(`/my/surveys/${row.id}/edit`)
        }).catch(() => {})
        return
      }

      const isReopen = row.status === 2
      ElMessageBox.confirm(
        isReopen
          ? `确定重新发布「${row.title}」吗？它会回到"已发布"状态，原有的分享码继续有效。`
          : `确定发布「${row.title}」吗？发布后将生成分享链接。`,
        '提示',
        { confirmButtonText: isReopen ? '确定重新发布' : '确定发布', cancelButtonText: '取消', type: 'info' }
      ).then(async () => {
        try {
          //   shareCode 是后端发布后生成的业务字段，
          //   拿到它才说明发布成功（也可能后端按需生成，所以这个判断保留）。
          const data = await api.survey.publish(row.id)
          if (data && data.shareCode) {
            ElMessage.success(isReopen ? '已重新发布' : '发布成功')
            this.loadSurveys()
          } else {
            ElMessage.error('发布失败')
          }
        } catch {
          // 提示由接口层统一弹出
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
          // ★ api.survey.recycle(row.id) 是 PUT（不是 POST，见 src/api/survey.js），
          //   成败只看 HTTP 状态码 —— 不要退回"比对后端中文文案"的写法，
          //   否则后端措辞改一个字，前端就静默失效。
          await api.survey.recycle(row.id)
          ElMessage.success('已移入回收站')
          this.loadSurveys()
        } catch {
          // 失败提示由接口层统一弹出
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

/* ============== 顶部 / 工具栏 ============== */
.page-subtitle {
  margin: 6px 0 0 15px;
  font-size: 13px;
  color: var(--ink-3);
}
.header-actions {
  display: flex;
  gap: 12px;
}

.toolbar-card {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  margin-bottom: 16px;
  padding: 12px 18px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid var(--stroke);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-sm);
}
.toolbar-trigger {
  display: flex;
  align-items: center;
  cursor: pointer;
  font-size: 14px;
  color: var(--ink-2);
  padding: 6px 10px;
  border-radius: 8px;
  transition:
    background 0.18s var(--ease),
    color 0.18s var(--ease);
}
.toolbar-trigger:hover {
  background: var(--sky-50);
  color: var(--sky-600);
}
.toolbar-search {
  width: 280px;
}

.list-skeleton {
  padding: 4px;
}

/* ============== 列表样式 ============== */
.survey-list {
  background: #fff;
  border: 1px solid var(--stroke);
  border-radius: var(--r-lg);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
}

/* ==================== 共享给我的问卷（H2） ==================== */
.shared-block {
  padding: 14px 18px;
  border: 1px solid var(--stroke);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #f8fbff, #f2f8ff);
}
.shared-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 10px;
}
.shared-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-top: 1px dashed var(--stroke);
}
.shared-row:first-of-type { border-top: none; }
.shared-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  color: var(--ink);
}
.shared-name .id-badge { margin-left: 8px; }
.shared-role { font-size: 12px; color: var(--ink-3); }

.list-header {
  display: grid;
  /* ★ 三件事一起才不重叠：
     ① minmax(0, 3fr)：标题列可以被压缩（默认下限是 min-content，会把整行撑爆）；
     ② 操作列 minmax(460px, auto)：6 个胶囊按钮实测约 454px，给足下限；
     ③ .col-actions 允许换行：真放不下时换行，绝不向左溢出压住"创建时间"。 */
  grid-template-columns: minmax(0, 3fr) 96px 170px 172px minmax(460px, auto);
  padding: 14px 24px;
  background: linear-gradient(180deg, #f8fbff 0%, #f3f8ff 100%);
  border-bottom: 1px solid var(--stroke);
  font-size: 12px;
  color: var(--ink-3);
  font-weight: 600;
  letter-spacing: 0.4px;
}

.list-row {
  display: grid;
  grid-template-columns: minmax(0, 3fr) 96px 170px 172px minmax(460px, auto);
  padding: 18px 24px;
  border-bottom: 1px solid #f1f5fd;
  align-items: center;
  transition: background 0.2s var(--ease);
  position: relative;
}

.list-row:last-child { border-bottom: none; }

.list-row::before {
  content: '';
  position: absolute;
  left: 0;
  top: 14px;
  bottom: 14px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: transparent;
  transition: background 0.2s var(--ease);
}

.list-row:hover { background: #f7fbff; }
.list-row:hover::before { background: var(--brand-gradient); }

.col-title { min-width: 0; padding-right: 16px; }

.title-main {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

/* "已共享 N 人"角标：与状态徽标同尺寸，颜色走靛蓝（与协作按钮同一色系） */
.shared-badge {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  font-size: 11px;
  font-weight: 500;
  color: #4338ca;
  background: #eef2ff;
  border: 1px solid #dcdffb;
  border-radius: 999px;
  white-space: nowrap;
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
  padding: 5px 12px;
  border-radius: 999px;
  font-weight: 500;
  border: 1px solid transparent;
}

.badge .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 6px;
  box-shadow: 0 0 0 3px rgba(255, 255, 255, 0.6);
}

.badge-published { background: #ecfdf3; color: #16a34a; border-color: #c9f2d9; }
.badge-published .dot { background: #16a34a; }

.badge-draft { background: #fff8e8; color: #d97706; border-color: #ffe7bd; }
.badge-draft .dot { background: #d97706; }

.badge-ended { background: #f1f5f9; color: #64748b; border-color: #e2e8f0; }
.badge-ended .dot { background: #64748b; }

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
  min-width: 0;          /* ★ 允许被压缩，而不是顶到隔壁列 */
  white-space: nowrap;
}

.created-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.col-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 8px;
  /* ★★ 必须允许换行：.col-actions 的内容比所在网格列宽时，
     justify-content: flex-end 会把溢出部分**推到左边**，
     正好压在"创建时间"上（实测截图就是重叠）。换行比溢出安全得多。 */
  flex-wrap: wrap;
  row-gap: 6px;
}

/* ============== 行内操作按钮：带文字的胶囊，一种功能一个色 ============== */
.row-btn {
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid transparent;
  background: #f4f7fc;
  white-space: nowrap;
  color: var(--ink-2);
  box-shadow: none;
  transition:
    transform 0.18s var(--ease),
    background-color 0.18s var(--ease),
    color 0.18s var(--ease),
    border-color 0.18s var(--ease),
    box-shadow 0.18s var(--ease);
}
.row-btn .el-icon {
  font-size: 13px;
  margin-right: 4px;
}
.row-btn:hover {
  transform: translateY(-1px);
}
.row-btn:active {
  transform: scale(0.97);
}

/* 协作者 = 靛蓝（与编辑的蓝、统计的紫刻意区分） */
.rb-collab {
  background: #eef2ff;
  color: #4338ca;
  border-color: #dcdffb;
}
.rb-collab:hover {
  background: #4f46e5;
  color: #fff;
  border-color: #4f46e5;
  box-shadow: 0 6px 14px rgba(79, 70, 229, 0.3);
}

/* 编辑 = 天空蓝（主操作） */
.rb-edit {
  background: #eaf3ff;
  color: #1d4ed8;
  border-color: #d5e7ff;
}
.rb-edit:hover {
  background: #2f80ed;
  color: #fff;
  border-color: #2f80ed;
  box-shadow: 0 6px 14px rgba(47, 128, 237, 0.3);
}

/* 发布 = 绿 */
.rb-publish {
  background: #e9f9ef;
  color: #15803d;
  border-color: #cdf2dc;
}
.rb-publish:hover {
  background: #16a34a;
  color: #fff;
  border-color: #16a34a;
  box-shadow: 0 6px 14px rgba(22, 163, 74, 0.28);
}

/* 分享 = 橙 */
.rb-share {
  background: #fff6e8;
  color: #b45309;
  border-color: #ffe6bf;
}
.rb-share:hover {
  background: #f59e0b;
  color: #fff;
  border-color: #f59e0b;
  box-shadow: 0 6px 14px rgba(245, 158, 11, 0.3);
}

/* 统计 = 紫（与"复制"的青色刻意区分开） */
.rb-stats {
  background: #f3ecff;
  color: #6d28d9;
  border-color: #e4d6ff;
}
.rb-stats:hover {
  background: #7c3aed;
  color: #fff;
  border-color: #7c3aed;
  box-shadow: 0 6px 14px rgba(124, 58, 237, 0.3);
}

/* 复制 = 青 */
.rb-copy {
  background: #e6f9fd;
  color: #0e7490;
  border-color: #c8f1fa;
}
.rb-copy:hover {
  background: #0891b2;
  color: #fff;
  border-color: #0891b2;
  box-shadow: 0 6px 14px rgba(8, 145, 178, 0.3);
}

/* 删除 = 红 */
.rb-delete {
  background: #ffefef;
  color: #b91c1c;
  border-color: #ffd9d9;
}
.rb-delete:hover {
  background: #ef4444;
  color: #fff;
  border-color: #ef4444;
  box-shadow: 0 6px 14px rgba(239, 68, 68, 0.3);
}

.col-actions .el-button {
  margin: 0;
}

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

/* 宽屏收窄一点、操作列按屏幕逐级压缩；窄屏只留图标（但按钮仍是 30px，不再是小圆点） */
@media (max-width: 1500px) {
  .list-header, .list-row {
    grid-template-columns: minmax(0, 2.4fr) 88px 150px 140px minmax(420px, auto);
    padding: 14px 18px;
  }
  .row-btn {
    padding: 0 10px;
  }
}

@media (max-width: 1280px) {
  .list-header, .list-row {
    grid-template-columns: minmax(0, 2fr) 80px 130px 120px minmax(360px, auto);
  }
  .row-btn {
    padding: 0 8px;
    font-size: 11px;
  }
}

@media (max-width: 1150px) {
  .btn-label {
    display: none;
  }
  .row-btn {
    width: 30px;
    padding: 0;
  }
  .row-btn .el-icon {
    margin-right: 0;
  }
  .list-header, .list-row {
    grid-template-columns: minmax(0, 2fr) 80px 130px 120px minmax(220px, auto);
  }
  .template-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 900px) {
  /* 窄屏：列表自己横向滚动（与 .table-card 同一口径），不要把整页撑出横向滚动条 */
  .survey-list {
    overflow-x: auto;
  }
  .list-header,
  .list-row {
    min-width: 800px;
  }
}
</style>