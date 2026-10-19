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
            <!-- 已共享人数：数据来自 GET /api/survey/my 的 collaboratorCount -->
            <span v-if="row.collaboratorCount > 0" class="shared-badge">🤝 已共享 {{ row.collaboratorCount }} 人</span>
          </div>
          <div class="title-desc">{{ row.description || '暂无描述' }}</div>
          <!-- 答卷数 / 回收率（responseCount 由 /api/survey/my 批量回填；浏览量为 0 时不显示回收率） -->
          <div class="row-metrics" :title="row.viewCount > 0 ? `浏览 ${row.viewCount} 次` : ''">
            <span class="row-metric">📊 {{ row.responseCount || 0 }} 份答卷</span>
            <template v-if="row.viewCount > 0">
              <span class="row-metric-dot">·</span>
              <span class="row-metric">回收率 {{ recoveryRate(row) }}%</span>
            </template>
          </div>
        </div>

        <div class="col-status">
          <!-- ★ 下架优先于 status 显示：被举报下架时 status 仍是 1，只看 status 会显示成"已发布" -->
          <span v-if="row.lifecycle === 'SUSPENDED'" class="badge badge-suspended">
            <span class="dot"></span> 已下架
          </span>
          <span v-else-if="row.status === 1" class="badge badge-published">
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
          <!-- 包一层 span 才能做省略号：grid 单元格不裁剪内容，日期会溢出到操作列 -->
          <span class="created-text">{{ row.createdTime }}</span>
        </div>

        <div class="col-actions">
          <!-- 已下架只留「申请恢复 / 统计 / 删除」：下架期间内容冻结，后端也会拦编辑/发布/回滚，
               再摆出编辑/分享/复制/协作只会点出一串 400。 -->
          <el-button v-if="row.lifecycle !== 'SUSPENDED'" class="row-btn rb-edit" :icon="Edit" @click="handleEdit(row)">
            <span class="btn-label">编辑</span>
          </el-button>
          <!-- 「申请恢复」只在 lifecycle=SUSPENDED 时出现：它给全部管理员发通知，不会直接恢复上架 -->
          <el-tooltip v-if="row.lifecycle === 'SUSPENDED'" content="问卷已被举报下架，可向管理员申请恢复上架" placement="top">
            <el-button class="row-btn rb-restore" :icon="RefreshLeft" @click="handleRequestRestore(row)">
              <span class="btn-label">申请恢复</span>
            </el-button>
          </el-tooltip>
          <!-- 不是"已发布"就给发布入口（后端允许延长/清空截止时间后重新发布）：
               草稿（NULL 也算草稿）→ 发布；已结束 → 重新发布，截止时间仍在过去时后端返回明确文案。 -->
          <el-tooltip v-if="row.status !== 1" :content="row.status === 2 ? '重新发布（需先延长截止时间）' : '发布'" placement="top">
            <el-button class="row-btn rb-publish" :icon="Promotion" @click="handlePublish(row)">
              <span class="btn-label">{{ row.status === 2 ? '重新发布' : '发布' }}</span>
            </el-button>
          </el-tooltip>
          <el-button v-if="(row.status === 1 || row.status === 2) && row.lifecycle !== 'SUSPENDED'" class="row-btn rb-share" :icon="Share" @click="handleShare(row)">
            <span class="btn-label">分享</span>
          </el-button>
          <el-button class="row-btn rb-stats" :icon="DataAnalysis" @click="handleStatistics(row)">
            <span class="btn-label">统计</span>
          </el-button>
          <!-- 低频/危险操作收进「更多」：主区只留编辑/发布/分享/统计，避免整行被胶囊挤满 -->
          <!-- 用 popover 而非 dropdown：内容直接复用 .row-btn 的胶囊样式 -->
          <el-popover
            v-model:visible="moreOpen[row.id]"
            class="row-more"
            trigger="click"
            placement="bottom-end"
            :width="156"
            popper-class="survey-more-popover"
          >
            <template #reference>
              <el-button class="row-btn rb-more" :icon="MoreFilled" title="更多操作">
                <span class="btn-label">更多</span>
              </el-button>
            </template>
            <div class="more-panel">
              <button type="button" class="row-btn rb-copy more-pill" @click="handleMoreCommand('copy', row)">
                <el-icon><component :is="CopyDocument" /></el-icon>
                <span>复制问卷</span>
              </button>
              <!-- 只有作者/管理员能管名单（后端判）：先按"是不是自己的"显示，别人的点了也会 400 -->
              <button
                v-if="row.userId === currentUserId() && row.lifecycle !== 'SUSPENDED'"
                type="button"
                class="row-btn rb-collab more-pill"
                @click="handleMoreCommand('collab', row)"
              >
                <el-icon><component :is="Connection" /></el-icon>
                <span>协作成员</span>
              </button>
              <button type="button" class="row-btn rb-delete more-pill" @click="handleMoreCommand('delete', row)">
                <el-icon><component :is="Delete" /></el-icon>
                <span>移入回收站</span>
              </button>
            </div>
          </el-popover>
        </div>
      </div>
    </div>

    <!-- 待接受的协作邀请：接受后才进入「共享给我」，并占协作者名额 -->
    <div v-if="invitations.length" class="invite-block mb-16">
      <div class="invite-head">
        <div class="shared-title">📨 待处理的协作邀请（{{ invitations.length }}）</div>
        <span class="invite-tip">接受后才会出现在「共享给我」；拒绝不占名额</span>
      </div>
      <div v-for="inv in invitations" :key="inv.id" class="invite-row">
        <div class="invite-main">
          <span class="invite-survey">{{ inv.surveyTitle }}<span class="id-badge">#{{ inv.surveyId }}</span></span>
          <span class="invite-owner">来自 {{ inv.ownerNickname || inv.ownerUsername }}</span>
          <el-tag size="small" :type="sharedRoleTagType(inv.collaboratorRole)" effect="light" round>
            {{ sharedRoleLabel(inv.collaboratorRole) }}
          </el-tag>
          <span class="invite-time">{{ inv.createdTime ? inv.createdTime.slice(0, 10) : '' }}</span>
        </div>
        <div class="invite-actions">
          <el-button size="small" type="primary" :loading="invitationActing === inv.id" @click="acceptInvitation(inv)">接受</el-button>
          <el-button size="small" :disabled="invitationActing === inv.id" @click="rejectInvitation(inv)">拒绝</el-button>
        </div>
      </div>
    </div>

    <!-- 共享给我的问卷：按作者分组，避免"多个作者 × 多份问卷"时一锅粥 -->
    <div v-if="sharedSurveys.length" class="shared-block mb-16">
      <div class="shared-head">
        <div class="shared-title">🤝 共享给我的问卷（{{ sharedSurveys.length }}）</div>
        <div class="shared-controls">
          <el-select v-model="sharedRoleFilter" size="small" style="width: 118px;">
            <el-option label="全部角色" value="all" />
            <el-option label="可编辑" value="editor" />
            <el-option label="仅看数据" value="analyst" />
            <el-option label="只读" value="viewer" />
          </el-select>
          <el-input
            v-model="sharedKeyword"
            size="small"
            clearable
            placeholder="搜索共享问卷..."
            :prefix-icon="Search"
            style="width: 210px;"
          />
        </div>
      </div>

      <div v-if="sharedGroups.length === 0" class="shared-empty">没有匹配的共享问卷</div>

      <div v-for="group in sharedGroups" :key="group.ownerId === null ? 'unknown' : group.ownerId" class="shared-group">
        <div class="shared-owner">
          <div class="owner-avatar">{{ (group.ownerName || '协').charAt(0) }}</div>
          <div class="owner-text">
            <div class="owner-name">{{ group.ownerName }}</div>
            <div class="owner-sub">
              <span v-if="group.ownerAccount">@{{ group.ownerAccount }} · </span>{{ group.items.length }} 份
            </div>
          </div>
        </div>

        <div v-for="item in group.items" :key="item.id" class="shared-row">
          <span class="shared-name">{{ item.title }}<span class="id-badge">#{{ item.id }}</span></span>
          <el-tag size="small" :type="sharedRoleTagType(item.myCollaboratorRole)" effect="light" round>
            {{ sharedRoleLabel(item.myCollaboratorRole) }}
          </el-tag>
          <el-tag size="small" :type="sharedStatusTagType(item)" effect="plain" round>
            {{ sharedStatusLabel(item) }}
          </el-tag>
          <span class="shared-time">{{ item.sharedTime ? item.sharedTime.slice(0, 10) : '' }}</span>
          <div class="shared-actions">
            <!-- 下架后协作者只能看数据/退出：不能再进编辑器改内容，也不再受理"申请权限" -->
            <el-button v-if="item.lifecycle !== 'SUSPENDED'" size="small" type="primary" plain @click="handleEdit(item)">打开</el-button>
            <el-button size="small" @click="handleStatistics(item)">统计</el-button>
            <el-button v-if="item.lifecycle !== 'SUSPENDED'" size="small" type="warning" plain @click="openApply(item)">申请权限</el-button>
            <el-button size="small" type="danger" plain @click="leaveCollaboration(item)">退出</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 分页：问卷是一次性全量拿回的，这里只做前端分页 -->
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

    <!-- 申请变更权限（被邀请人） -->
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

    <!-- 协作者管理（作者/管理员） -->
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
        <el-table-column label="状态" width="92" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'PENDING'" size="small" type="warning" effect="light">待接受</el-tag>
            <el-tag v-else size="small" type="success" effect="light">已生效</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="160">
          <template #default="{ row }">
            <el-select
              :model-value="row.collaboratorRole"
              size="small"
              :disabled="row.status === 'PENDING'"
              @change="(v) => changeCollaboratorRole(row, v)"
            >
              <el-option label="只读 viewer" value="viewer" />
              <el-option label="可编辑 editor" value="editor" />
              <el-option label="只看数据 analyst" value="analyst" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" link type="warning" @click="revokeInvitation(row)">撤销邀请</el-button>
            <el-button v-else link type="danger" @click="removeCollaborator(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="collab-hint">只读 = 只能看问卷；可编辑 = 能改内容、不能删除；只看数据 = 只能看统计。<br />
        被邀请人会收到站内通知，要在「我的问卷」页接受后才会生效；作者也可以撤销未接受的邀请。<br />
        <b>名额计费</b>：每份问卷前 2 名协作者免费，之后每<b>接受</b> 1 名扣 1 点（待接受不占名额，拒绝/撤销不扣费）。</p>
    </el-dialog>

    <!-- AI 创建问卷 -->
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
        <br />
        <span class="ai-cost-hint">本次生成消耗 <b>3 点</b>额度（生成失败自动退回），可在「我的钱包」查看流水。</span>
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

    <!-- 新建问卷：空白创建 / 从模板创建 -->
    <el-dialog v-model="createDialogVisible" title="新建问卷" width="960px" top="8vh">
      <!-- 双卡片入口：说明两种开始方式；键盘左右键切换，role=tab + aria-selected 保持可访问性 -->
      <div class="create-switch" role="tablist" aria-label="新建问卷方式">
        <button
          id="create-tab-blank"
          ref="createTabBlank"
          type="button"
          class="switch-card"
          :class="{ active: createTab === 'blank' }"
          role="tab"
          :aria-selected="createTab === 'blank'"
          aria-controls="create-panel-blank"
          :tabindex="createTab === 'blank' ? 0 : -1"
          @click="switchCreateTab('blank')"
          @keydown.left.prevent="switchCreateTab('template')"
          @keydown.right.prevent="switchCreateTab('template')"
        >
          <span class="switch-icon"><el-icon><EditPen /></el-icon></span>
          <span class="switch-text">
            <b>空白创建</b>
            <i>从零开始，自由添加题目</i>
          </span>
        </button>

        <button
          id="create-tab-template"
          ref="createTabTemplate"
          type="button"
          class="switch-card"
          :class="{ active: createTab === 'template' }"
          role="tab"
          :aria-selected="createTab === 'template'"
          aria-controls="create-panel-template"
          :tabindex="createTab === 'template' ? 0 : -1"
          @click="switchCreateTab('template')"
          @keydown.left.prevent="switchCreateTab('blank')"
          @keydown.right.prevent="switchCreateTab('blank')"
        >
          <span class="switch-icon"><el-icon><Files /></el-icon></span>
          <span class="switch-text">
            <b>从模板创建</b>
            <i>选一个现成模板，改成自己的</i>
          </span>
        </button>
      </div>

      <div
        v-show="createTab === 'blank'"
        id="create-panel-blank"
        role="tabpanel"
        aria-labelledby="create-tab-blank"
      >
          <!-- 场景类型：先选"要做什么"，再进入编辑；选择后预填标题/描述与一套可改的题目。
               模板只是前端预设（add + question.save 两步），不新增后端概念。 -->
          <div class="scene-picker">
            <button
              v-for="s in sceneTemplates"
              :key="s.code"
              type="button"
              class="scene-card"
              :class="{ active: newForm.scene === s.code }"
              @click="pickScene(s)"
            >
              <span class="scene-emoji">{{ s.emoji }}</span>
              <span class="scene-name">{{ s.name }}</span>
              <span class="scene-desc">{{ s.desc }}</span>
            </button>
          </div>

          <el-form class="form-panel" :model="newForm" label-width="80px">
            <el-form-item label="标题">
              <!-- maxlength 与后端 TextLimits.SURVEY_TITLE(200) / SURVEY_DESCRIPTION(500) 对齐 -->
              <el-input v-model="newForm.title" placeholder="请输入问卷标题" maxlength="200" show-word-limit />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="newForm.description" type="textarea" :rows="3" placeholder="请输入问卷描述（选填）" maxlength="500" show-word-limit />
            </el-form-item>
          </el-form>
          <p v-if="sceneQuestions.length" class="scene-hint">
            将自动加入 {{ sceneQuestions.length }} 道预设题目，创建后可直接在编辑器里修改。
          </p>
          <div style="text-align: right; margin-top: 20px;">
            <el-button @click="createDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="handleCreate">创建并编辑</el-button>
          </div>

          <p class="create-hint">
            没有头绪？
            <button type="button" class="create-link" @click="switchCreateTab('template')">
              从模板开始 →
            </button>
          </p>
        </div>

        <div
          v-show="createTab === 'template'"
          id="create-panel-template"
          role="tabpanel"
          aria-labelledby="create-tab-template"
        >
          <!-- 分类 + 搜索合一行：分类用带计数的下拉，避免多个分类胶囊在窄弹窗里换行 -->
          <div class="tpl-toolbar">
            <el-select v-model="templateCategory" class="tpl-category" size="default">
              <el-option label="全部分类" value="全部" />
              <el-option
                v-for="cat in templateCategories"
                :key="cat.name"
                :label="`${cat.name} (${cat.count})`"
                :value="cat.name"
              />
            </el-select>
            <span class="tpl-count">共 {{ filteredTemplates.length }} 个模板</span>

            <div class="tpl-search">
              <el-input
                v-model="templateKeyword"
                class="tpl-search-input"
                placeholder="搜索模板名称..."
                clearable
                :prefix-icon="Search"
                @keyup.enter="loadTemplates"
                @clear="loadTemplates"
              />
              <el-button type="primary" @click="loadTemplates">搜索</el-button>
            </div>
          </div>

          <div v-loading="templateLoading" class="tpl-panel">
            <div v-if="filteredTemplates.length > 0" class="template-grid">
              <div
                v-for="tpl in filteredTemplates"
                :key="tpl.id"
                class="template-card"
                @click="useTemplate(tpl)"
              >
                <div class="tpl-header">
                  <el-tag size="small" type="success">{{ tpl.templateCategory || '通用' }}</el-tag>
                  <!-- 热度：来自 t_survey.template_use_count，后端按它降序返回 -->
                  <span v-if="tpl.templateUseCount > 0" class="tpl-usage">🔥 {{ tpl.templateUseCount }} 次</span>
                </div>
                <h4 class="tpl-title">{{ tpl.title }}</h4>
                <p class="tpl-desc">{{ tpl.description || '暂无描述' }}</p>
                <div class="tpl-footer">
                  <span class="tpl-use-link">使用此模板 →</span>
                </div>
              </div>
            </div>
            <el-empty v-else description="没有匹配的模板" :image-size="80" />
          </div>
        </div>
    </el-dialog>

    <!-- 分享问卷 -->
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
  Plus, CopyDocument, Edit, EditPen, Promotion, Share, DataAnalysis, Delete,
  Search, MagicStick, Connection, RefreshLeft, MoreFilled } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
// 请求统一走 src/api/http.js：鉴权、成败判断、错误提示都收口在那里，页面只关心调哪个接口。
// 路径说明：本文件在 src/views/ 下，api 在 src/api/，所以是 '../api'。
import api from '../api'
import { getUser } from '../utils/auth'
import collaboratorApi from '../api/collaborator'
// 页码记忆（sessionStorage）：从统计/编辑页返回时仍停在第 N 页
import { readPageNum, savePageNum } from '../utils/pageMemory'

/** 本页在 sessionStorage 里的页码键 */
const PAGE_KEY = 'MySurveys'

/**
 * 场景模板（"先选类型再创建"）：纯前端预设，创建时先 add 再 question.save 落库。
 * 基础问卷 = 完全空白（默认），其余类型给一套可直接改的题目。
 */
const SCENE_TEMPLATES = [
  {
    code: 'basic', emoji: '📝', name: '基础问卷', desc: '从空白开始',
    title: '', description: '', questions: []
  },
  {
    code: 'exam', emoji: '📊', name: '考试测评', desc: '评分 + 选择题 + 建议',
    title: '课程测评问卷',
    description: '请根据本次体验完成以下问题，感谢你的参与。',
    questions: [
      { type: 6, title: '请为本次课程整体打分（1~5 分）', required: 1 },
      { type: 1, title: '你对课程节奏的感受是？', required: 1, options: ['太快', '合适', '太慢'] },
      { type: 2, title: '你希望加强哪些内容？', required: 0, options: ['理论', '案例', '实操', '答疑'] },
      { type: 4, title: '还有什么建议？', required: 0 }
    ]
  },
  {
    code: 'vote', emoji: '🗳️', name: '投票', desc: '快速收集选择',
    title: '投票问卷',
    description: '请选择你支持的一项，并可以补充理由。',
    questions: [
      { type: 1, title: '请选择你支持的一项', required: 1, options: ['选项 A', '选项 B', '选项 C'] },
      { type: 4, title: '补充理由（选填）', required: 0 }
    ]
  },
  {
    code: 'signup', emoji: '📋', name: '报名登记', desc: '姓名 + 联系方式 + 场次',
    title: '活动报名表',
    description: '请填写以下信息完成报名，我们会尽快与你联系。',
    questions: [
      { type: 3, title: '姓名', required: 1 },
      { type: 3, title: '手机号', required: 1 },
      { type: 1, title: '参加场次', required: 1, options: ['上午场', '下午场'] },
      { type: 4, title: '备注（选填）', required: 0 }
    ]
  },
  {
    code: 'relay', emoji: '🔗', name: '接龙', desc: '姓名 + 联系方式 + 人数',
    title: '活动接龙',
    description: '按顺序接龙报名，填好信息即可；人数或备注有变请重新提交一份。',
    // 接龙的核心就是公开名单：创建时直接打开，否则用户还得进设置手动开
    showParticipants: 1,
    questions: [
      { type: 3, title: '姓名', required: 1 },
      { type: 3, title: '手机号', required: 1 },
      { type: 1, title: '报名人数', required: 1, options: ['1 人', '2 人', '3 人', '4 人及以上'] },
      { type: 4, title: '备注（如携带物品 / 特殊需求，选填）', required: 0 }
    ]
  },
  {
    code: 'checkin', emoji: '🎟️', name: '报名签到', desc: '姓名 + 手机号 + 场次',
    title: '活动报名签到',
    description: '请先填写报名信息；到场后向工作人员报手机号完成签到。',
    // 报名签到同时要名单（谁报了）与核销（谁到了）
    checkinEnabled: 1,
    showParticipants: 1,
    questions: [
      { type: 3, title: '姓名', required: 1 },
      { type: 3, title: '手机号', required: 1 },
      { type: 1, title: '参加场次', required: 1, options: ['上午场', '下午场', '全天'] },
      { type: 4, title: '备注（选填）', required: 0 }
    ]
  },
  {
    code: 'satisfaction', emoji: '⭐', name: '满意度调查', desc: '评分 + 选项 + 建议',
    title: '满意度调查',
    description: '请根据你的真实体验打分，帮助我们持续改进。',
    questions: [
      { type: 6, title: '整体满意度（1~5 分）', required: 1 },
      { type: 1, title: '你最满意的方面是？', required: 1, options: ['服务', '环境', '效率', '价格'] },
      { type: 2, title: '希望改进的地方', required: 0, options: ['等待时间', '沟通', '设施', '其他'] },
      { type: 4, title: '其他建议', required: 0 }
    ]
  },
  {
    code: 'market', emoji: '📈', name: '市场调研', desc: '人群 + 渠道 + 意愿',
    title: '市场调研问卷',
    description: '我们想了解你的真实需求，问卷大约需要 2 分钟。',
    questions: [
      { type: 1, title: '你的年龄段', required: 1, options: ['18-25', '26-35', '36-45', '46 岁以上'] },
      { type: 2, title: '你通过哪些渠道了解我们？', required: 0, options: ['朋友推荐', '社交媒体', '搜索引擎', '线下活动'] },
      { type: 6, title: '购买意愿（1~5 分）', required: 1 },
      { type: 4, title: '其他想法', required: 0 }
    ]
  }
]

// 图标已在 main.js 全局注册；:icon / :prefix-icon 用的图标变量仍需从本文件 import 并 markRaw
export default {
  data() {
    return {
      surveys: [],
      /** 「更多」popover 的展开状态（按问卷 id 存；每行独立，互不影响） */
      moreOpen: {},
      /** 首次加载骨架屏（数据回来即关，失败也关，避免一直闪） */
      loading: true,
      /** 共享给我的问卷 */
      sharedSurveys: [],
      /** 待我接受的协作邀请 */
      invitations: [],
      /** 正在接受/拒绝的邀请 id（按钮 loading，防止连点重复提交） */
      invitationActing: null,
      /** 共享列表的筛选：角色（all/editor/analyst/viewer）+ 标题搜索 */
      sharedRoleFilter: 'all',
      sharedKeyword: '',
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
      createDialogVisible: false,
      createTab: 'blank',
      newForm: { title: '', description: '', scene: 'basic', checkinEnabled: 0, showParticipants: 0 },
      /** 场景模板目录（纯前端预设，选择后预填标题/描述/题目） */
      sceneTemplates: SCENE_TEMPLATES,
      /** 当前场景带出的题目（创建时随问卷一起保存） */
      sceneQuestions: [],
      templateKeyword: '',
      templateCategory: '全部',
      templates: [],
      templateLoading: false,
      aiDialogVisible: false,
      aiForm: { topic: '', extra: '' },
      aiLoading: false,
      shareVisible: false,
      shareLink: '',
      keyword: '',
      sortOrder: 'desc',
      statusFilter: 'all',
      // 列表分页：survey.my() 一次性拿回，这里只做前端分页；页码用 sessionStorage 记忆
      pageNum: 1,
      pageSize: 10,

      // 所有在模板里通过 :icon / :prefix-icon 使用的图标，都要在这里 markRaw 暴露
      Plus: markRaw(Plus),
      CopyDocument: markRaw(CopyDocument),
      Edit: markRaw(Edit),
      Promotion: markRaw(Promotion),
      Share: markRaw(Share),
      DataAnalysis: markRaw(DataAnalysis),
      Connection: markRaw(Connection),
      RefreshLeft: markRaw(RefreshLeft),
      MoreFilled: markRaw(MoreFilled),
      EditPen: markRaw(EditPen),
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
        // ★ 不能严格相等：全项目口径是"NULL 视为草稿"（common/SurveyStatus.isDraft），
        // 否则列表里标着草稿、点「草稿」筛选却找不到它。
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
    /** 共享问卷：先按角色 + 标题过滤（分类/排序由共享区自己的控件负责） */
    filteredSharedSurveys() {
      const role = this.sharedRoleFilter
      const kw = (this.sharedKeyword || '').trim().toLowerCase()
      return this.sharedSurveys.filter(item => {
        if (role !== 'all' && item.myCollaboratorRole !== role) return false
        if (kw && !(item.title || '').toLowerCase().includes(kw)) return false
        return true
      })
    },
    /**
     * 共享问卷按作者（userId）分组：一个用户可能被多个作者邀请，平铺会分不清哪份是谁的。
     * 组内按共享时间倒序；作者昵称为空时退回账号，再退回用户 id。
     */
    sharedGroups() {
      const groups = new Map()
      for (const item of this.filteredSharedSurveys) {
        const ownerId = item.userId
        const key = ownerId == null ? 'unknown' : String(ownerId)
        if (!groups.has(key)) {
          groups.set(key, {
            ownerId,
            ownerName: item.ownerNickname || item.ownerUsername || (ownerId == null ? '未知作者' : `用户 #${ownerId}`),
            ownerAccount: item.ownerUsername || '',
            items: []
          })
        }
        groups.get(key).items.push(item)
      }
      const list = [...groups.values()]
      list.forEach(g => g.items.sort((a, b) => {
        const ta = new Date((a.sharedTime || '').replace(/-/g, '/')).getTime() || 0
        const tb = new Date((b.sharedTime || '').replace(/-/g, '/')).getTime() || 0
        return tb - ta || Number(b.id) - Number(a.id)
      }))
      return list.sort((a, b) => b.items.length - a.items.length ||
        a.ownerName.localeCompare(b.ownerName, 'zh-CN'))
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
    this.loadInvitations()
    this.startAutoRefresh()
    // 从看板快捷入口跳过来时直接打开对应弹窗；打开后清掉 query，避免刷新又弹一次
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
     * 静默刷新列表（不碰 loading / 分页 / 弹窗）：后端定时任务每分钟才改 status，
     * 只取一次会出现"截止时间到了、页面还显示已发布"；30 秒刷新一次即可。
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

    /** 待接受的协作邀请（静默失败：没有邀请时接口就是空数组） */
    async loadInvitations() {
      try {
        this.invitations = await collaboratorApi.invitations()
      } catch {
        this.invitations = []
      }
    },

    async acceptInvitation(inv) {
      this.invitationActing = inv.id
      try {
        await collaboratorApi.accept(inv.id)
        ElMessage.success('已接受，问卷已加入「共享给我」')
        await this.refreshCollaboration()
      } finally {
        this.invitationActing = null
      }
    },

    async rejectInvitation(inv) {
      this.invitationActing = inv.id
      try {
        await collaboratorApi.reject(inv.id)
        ElMessage.success('已拒绝邀请')
        await this.loadInvitations()
      } finally {
        this.invitationActing = null
      }
    },

    /** 接受后同时刷新：邀请列表、共享列表、我的问卷（「已共享 N 人」角标） */
    async refreshCollaboration() {
      await Promise.all([this.loadInvitations(), this.loadSharedSurveys(), this.loadSurveys()])
    },

    /** 我在共享问卷里的协作者角色 → 中文标签 */
    sharedRoleLabel(role) {
      const map = { editor: '可编辑', analyst: '仅看数据', viewer: '只读' }
      return map[role] || '协作者'
    },
    sharedRoleTagType(role) {
      const map = { editor: 'success', analyst: 'warning', viewer: 'info' }
      return map[role] || 'info'
    },
    /** 共享问卷的状态：下架优先，其次按 status（NULL 视为草稿） */
    sharedStatusLabel(item) {
      if (item.lifecycle === 'SUSPENDED') return '已下架'
      if (item.status === 1) return '已发布'
      if (item.status === 2) return '已结束'
      return '草稿'
    },
    sharedStatusTagType(item) {
      if (item.lifecycle === 'SUSPENDED') return 'danger'
      if (item.status === 1) return 'success'
      if (item.status === 2) return 'info'
      return 'info'
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

    /** 作者撤销未接受的邀请：撤销后对方再点接受会 400（状态已变 REVOKED） */
    async revokeInvitation(row) {
      try {
        await this.$confirm(
          `撤销发给 ${row.nickname || row.username} 的邀请？撤销后对方无法再接受。`,
          '撤销邀请',
          { type: 'warning' }
        )
      } catch {
        return // 用户取消
      }
      await collaboratorApi.revoke(this.collabSurveyId, row.userId)
      ElMessage.success('已撤销邀请')
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

    /** 新建弹窗的"空白创建 / 从模板创建"切换（点击、键盘左右键、底部文字链共用） */
    switchCreateTab(tab) {
      if (tab !== 'blank' && tab !== 'template') return
      this.createTab = tab
      // 让焦点跟随选中项：键盘用户按左右键后能继续用 Tab 进入对应面板
      this.$nextTick(() => {
        const ref = tab === 'blank' ? this.$refs.createTabBlank : this.$refs.createTabTemplate
        if (ref && ref.focus) ref.focus()
      })
    },

    openCreateDialog() {
      this.createDialogVisible = true
      this.createTab = 'blank'
      this.newForm = { title: '', description: '', scene: 'basic' }
      this.sceneQuestions = []
      this.templateCategory = '全部'
    },

    /** 选场景：预填标题/描述与题目（基础问卷 = 全部清空），并带上该场景需要的开关 */
    pickScene(scene) {
      this.newForm.scene = scene.code
      this.newForm.title = scene.title || ''
      this.newForm.description = scene.description || ''
      // 接龙 / 报名签到场景在创建时就打开对应开关；其余场景归零
      this.newForm.checkinEnabled = scene.checkinEnabled || 0
      this.newForm.showParticipants = scene.showParticipants || 0
      this.sceneQuestions = (scene.questions || []).map((q) => ({
        ...q,
        options: [...(q.options || [])]
      }))
    },

    /** 前端预设题目 → 后端 /question/save 的入参（补 sortOrder，选项补序） */
    toApiQuestions(list) {
      return list.map((q, index) => ({
        type: q.type,
        title: q.title,
        required: q.required,
        sortOrder: index + 1,
        options: (q.options || []).map((content, oi) => ({
          content,
          sortOrder: oi + 1
        }))
      }))
    },

    async handleCreate() {
      if (!this.newForm.title.trim()) {
        ElMessage.warning('请输入问卷标题')
        return
      }
      // ★ 请求体不传 userId：归属由服务端按 JWT 决定，客户端传什么都会被覆盖。
      try {
        // 其余服务端强制字段（status/shareCode/isTemplate/deleted）也不传，避免误以为客户端能指定。
        const data = await api.survey.add({
          title: this.newForm.title,
          description: this.newForm.description,
          // 场景模板自带的开关：接龙开公开名单、报名签到再加签到核销
          checkinEnabled: this.newForm.checkinEnabled,
          showParticipants: this.newForm.showParticipants
        })
        if (data.id) {
          // 场景模板：把预设题目一并落库（失败不阻塞进入编辑器，用户还能手动加题）
          if (this.sceneQuestions.length) {
            try {
              await api.question.save(data.id, this.toApiQuestions(this.sceneQuestions))
            } catch {
              // 题目保存失败由接口层提示；问卷已创建，仍进入编辑器
            }
          }
          ElMessage.success('创建成功，即将进入编辑页')
          this.createDialogVisible = false
          this.newForm = { title: '', description: '', scene: 'basic' }
          this.$router.push(`/my/surveys/${data.id}/edit`)
        }
      } catch {
        // 失败提示由接口层统一弹出；这里不关弹窗，用户可以改完标题再试
      }
    },

    openAiDialog() {
      this.aiDialogVisible = true
      this.aiForm = { topic: '', extra: '' }
    },

    async handleAiGenerate() {
      if (!this.aiForm.topic.trim()) {
        ElMessage.warning('请输入问卷主题')
        return
      }
      // 慢接口：后端 read 超时 120 秒，免费额度排队可能等十几秒 —— 不能加 AbortController / 短超时。
      this.aiLoading = true
      try {
        // api.ai.generate(topic, extra) 与后端契约对齐（后端读 topic / extra 两个字段）
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
          // 必须拿到新问卷 id 才能跳编辑页；拿不到按失败提示（业务字段判断，不比对中文文案）
          ElMessage.error('创建失败')
        }
      } catch {
        // 请求失败提示由接口层统一弹出
      }
    },

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
      // 已结束的问卷要先延长/清空截止时间才能重新发布（后端会校验 endTime），先提示该做什么。
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
          // shareCode 是后端发布后生成的业务字段，拿到它才说明发布成功。
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

    /**
     * 申请恢复上架（仅 SUSPENDED）：只给全部管理员发通知，不会直接恢复；
     * 管理员在 /admin/reviews 点「恢复上架」才生效（后端会再校验 lifecycle）。
     */
    async handleRequestRestore(row) {
      try {
        await ElMessageBox.confirm(
          `《${row.title}》已被举报下架。提交申请后全部管理员会收到通知，并在「举报审核」里决定是否恢复；恢复前它不能接收答卷。`,
          '申请恢复上架',
          { confirmButtonText: '提交申请', cancelButtonText: '取消', type: 'warning' }
        )
      } catch {
        return // 用户取消
      }
      try {
        await api.survey.requestRestore(row.id)
        ElMessage.success('已提交申请，管理员会尽快处理')
      } catch {
        // 失败提示由接口层统一弹出（如"该问卷没有被下架，无需申请"）
      }
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

    /** 回收率 = 答卷数 / 浏览量（浏览量 0 时返回 0；超过 100% 按 100 显示，避免看起来像 bug） */
    recoveryRate(row) {
      const views = Number(row.viewCount) || 0
      if (views <= 0) return 0
      return Math.min(100, Math.round((Number(row.responseCount) || 0) * 100 / views))
    },

    handleMoreCommand(command, row) {
      // popover 不像 dropdown 的菜单项那样点完自动收起，必须手动关
      this.moreOpen[row.id] = false
      if (command === 'copy') this.handleCopy(row)
      else if (command === 'collab') this.openCollaborators(row)
      else if (command === 'delete') this.handleDelete(row)
    },

    handleDelete(row) {
      ElMessageBox.confirm(`确定将「${row.title}」移入回收站吗？可以在回收站里恢复。`, '提示', {
        confirmButtonText: '移入回收站',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          // recycle 是 PUT（见 src/api/survey.js），成败只看 HTTP 状态码，
          // 不要比对后端中文文案 —— 后端措辞改一个字，前端就静默失效。
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

.ai-cost-hint {
  display: inline-block;
  margin-top: 6px;
  color: #b45309;
}
.ai-loading {
  text-align: center;
  padding: 20px 0;
}.ai-loading-icon { margin-bottom: 12px; }
.ai-loading-text {
  font-size: 15px;
  font-weight: 500;
  color: var(--ink);
}
.ai-loading-sub {
  font-size: 12px;
  color: var(--ink-3);
  margin-top: 4px;
}

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

.survey-list {
  background: var(--surface);
  border: 1px solid var(--stroke);
  border-radius: var(--r-lg);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
}

/* 待接受的协作邀请 */
.invite-block {
  padding: 14px 18px;
  border: 1px solid #fde68a;
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #fffbeb, #fff7ed);
}
.invite-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.invite-tip {
  font-size: 12px;
  color: var(--ink-3);
}
.invite-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-top: 1px dashed #fde68a;
  flex-wrap: wrap;
}
.invite-row:first-of-type {
  border-top: none;
}
.invite-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex-wrap: wrap;
}
.invite-survey {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.invite-owner,
.invite-time {
  font-size: 12px;
  color: var(--ink-3);
}
.invite-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
html[data-theme="dark"] .invite-block {
  background: #2a2110;
  border-color: #5b4a1f;
}
html[data-theme="dark"] .invite-row {
  border-top-color: #5b4a1f;
}

.shared-block {
  padding: 14px 18px;
  border: 1px solid var(--stroke);
  border-radius: var(--r-md);
  background: linear-gradient(180deg, #f8fbff, #f2f8ff);
}
.shared-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.shared-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.shared-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.shared-group {
  padding-top: 10px;
  border-top: 1px solid var(--stroke);
  margin-top: 8px;
}
.shared-group:first-of-type {
  border-top: none;
  margin-top: 0;
}
.shared-owner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 0 6px;
}
.owner-avatar {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  background: linear-gradient(135deg, var(--sky-400, #38bdf8), var(--sky-600, #0284c7));
}
.owner-text { min-width: 0; }
.owner-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  line-height: 1.3;
}
.owner-sub {
  font-size: 11px;
  color: var(--ink-3);
  line-height: 1.4;
}
.shared-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-top: 1px dashed var(--stroke);
  flex-wrap: wrap;
}
.shared-group .shared-owner + .shared-row {
  border-top: none;
}
.shared-name {
  flex: 1 1 240px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  color: var(--ink);
}
.shared-name .id-badge { margin-left: 8px; }
.shared-time {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--ink-3);
  font-variant-numeric: tabular-nums;
}
.shared-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.shared-empty {
  padding: 14px 0 6px;
  font-size: 12px;
  color: var(--ink-3);
  text-align: center;
}

.list-header {
  display: grid;
  /* 三件事一起才不重叠：minmax(0,1fr) 让标题列可压缩；操作列给足下限；.col-actions 允许换行 */
  grid-template-columns: minmax(0, 1fr) 96px 170px 172px 296px;
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
  grid-template-columns: minmax(0, 1fr) 96px 170px 172px 296px;
  padding: 18px 24px;
  border-bottom: 1px solid var(--stroke);
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

.list-row:hover { background: var(--surface-2); }
.list-row:hover::before { background: var(--brand-gradient); }

.col-title { min-width: 0; padding-right: 16px; }

.title-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-bottom: 4px;
}

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
  min-width: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-right: 8px;
}

.id-badge {
  font-size: 11px;
  color: var(--ink-3);
  background: var(--surface-2);
  padding: 1px 6px;
  border-radius: 4px;
  flex-shrink: 0;
}

.title-desc {
  font-size: 13px;
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-metrics {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-3);
}
.row-metric {
  white-space: nowrap;
}
.row-metric-dot {
  color: #c0c4cc;
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

.badge-suspended { background: #fef2f2; color: #dc2626; border-color: #fecaca; }
.badge-suspended .dot { background: #dc2626; }

.badge-ended { background: var(--stroke); color: #64748b; border-color: #e2e8f0; }
.badge-ended .dot { background: #64748b; }

.col-time { font-size: 12px; }

.time-line {
  display: flex;
  align-items: center;
  margin-bottom: 4px;
  color: var(--ink-3);
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
  color: var(--ink-3);
  min-width: 0;          /* 允许被压缩，而不是顶到隔壁列 */
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
  /* 必须允许换行：内容超出网格列宽时，justify-content: flex-end 会把溢出部分推到左边，
     压在"创建时间"上。 */
  flex-wrap: wrap;
  row-gap: 6px;
}

/* 行内操作按钮：带文字的胶囊，一种功能一个色 */
.row-btn {
  height: 30px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid transparent;
  background: var(--surface-2);
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

.rb-restore {
  background: #fff1f0;
  color: #c2410c;
  border-color: #ffd9cf;
}
.rb-restore:hover {
  background: #ea580c;
  color: #fff;
  border-color: #ea580c;
  box-shadow: 0 6px 14px rgba(234, 88, 12, 0.3);
}

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

.row-more {
  display: inline-flex;
}
.rb-more {
  background: var(--surface-2);
  color: #64748b;
  border-color: #e2e8f0;
}
.rb-more:hover {
  background: #eef2f7;
  color: #334155;
  border-color: #cbd5e1;
  box-shadow: 0 4px 10px rgba(100, 116, 139, 0.16);
}

.more-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 2px;
}
.more-pill {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: flex-start;
  cursor: pointer;
  font-family: inherit;
  text-align: left;
}
.more-pill:hover {
  transform: translateY(-1px);
}

.tpl-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.tpl-category {
  width: 170px;
}
.tpl-count {
  font-size: 12px;
  color: var(--ink-3);
}
.tpl-search {
  display: flex;
  gap: 8px;
  margin-left: auto;
}
.tpl-search-input {
  width: 240px;
}
.tpl-panel {
  min-height: 300px;
  max-height: 470px;
  overflow-y: auto;
  padding: 2px 4px 4px;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
  gap: 12px;
}

.template-card {
  height: 150px;
  padding: 14px 16px;
  border: 1px solid var(--stroke);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.25s ease;
  background: var(--surface);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.template-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
  border-color: #409eff;
}

.tpl-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.tpl-usage {
  flex-shrink: 0;
  font-size: 11px;
  color: #b45309;
  background: #fff6e8;
  border: 1px solid #ffe6bf;
  border-radius: 999px;
  padding: 1px 8px;
}

.tpl-title {
  margin: 0 0 6px 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tpl-desc {
  margin: 0 0 auto 0;
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.5;
  display: -webkit-box;
  line-clamp: 2;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 36px;
}

.tpl-footer {
  margin-top: auto;
  padding-top: 6px;
  text-align: right;
  opacity: 0.65;
  transition: opacity 0.25s;
}

.template-card:hover .tpl-footer { opacity: 1; }

.tpl-use-link {
  font-size: 12px;
  font-weight: 500;
  color: #2f80ed;
}

/* 宽屏收窄、操作列逐级压缩；窄屏只留图标（按钮仍是 30px） */
@media (max-width: 1500px) {
  .list-header, .list-row {
    grid-template-columns: minmax(0, 1fr) 88px 150px 140px 284px;
    padding: 14px 18px;
  }
  .row-btn {
    padding: 0 10px;
  }
}

@media (max-width: 1280px) {
  .list-header, .list-row {
    grid-template-columns: minmax(0, 1fr) 82px 132px 120px 264px;
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
    grid-template-columns: minmax(0, 1fr) 78px 124px 112px 190px;
  }
  .col-actions {
    gap: 6px;
  }
  .template-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 900px) {
  /* 窄屏：列表自己横向滚动，不要把整页撑出横向滚动条 */
  .survey-list {
    overflow-x: auto;
  }
  .list-header,
  .list-row {
    min-width: 840px;
  }
}

.create-switch {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}
.switch-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  background: var(--surface);
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  transition:
    transform 0.18s var(--ease),
    border-color 0.18s var(--ease),
    background-color 0.18s var(--ease),
    box-shadow 0.18s var(--ease);
}
/* 选中态左侧 4px 渐变条（伪元素，不占布局） */
.switch-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  background: transparent;
}
.switch-card:hover {
  transform: translateY(-1px);
  border-color: #bfdbfe;
  box-shadow: var(--shadow-sm);
}
.switch-card.active {
  background: #eff6ff;
  border-color: #93c5fd;
}
.switch-card.active::before {
  background: var(--brand-gradient);
}
.switch-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: var(--stroke);
  color: #64748b;
  font-size: 18px;
  flex-shrink: 0;
  transition: background-color 0.18s var(--ease), color 0.18s var(--ease);
}
.switch-card.active .switch-icon {
  background: #dbeafe;
  color: #1d4ed8;
}
.switch-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.switch-text b {
  font-size: 14px;
  color: var(--ink);
}
.switch-text i {
  font-style: normal;
  font-size: 12px;
  color: var(--ink-3);
}
.create-hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--ink-3);
}
.create-link {
  padding: 0;
  border: 0;
  background: none;
  color: var(--sky-600, #2563eb);
  font-size: 12px;
  cursor: pointer;
}
.create-link:hover {
  text-decoration: underline;
}

html[data-theme="dark"] .switch-card {
  background: #101d31;
  border-color: #1d2c44;
}
html[data-theme="dark"] .switch-card:hover {
  border-color: #2563eb;
}
html[data-theme="dark"] .switch-card.active {
  background: #16263f;
  border-color: #2563eb;
}
html[data-theme="dark"] .switch-icon {
  background: #16263f;
  color: #94a3b8;
}
html[data-theme="dark"] .switch-card.active .switch-icon {
  background: #1e3a8a;
  color: #bfdbfe;
}
html[data-theme="dark"] .switch-text b {
  color: #e2e8f0;
}
html[data-theme="dark"] .template-card {
  background: #101d31;
  border-color: #1d2c44;
}
html[data-theme="dark"] .tpl-title {
  color: #e2e8f0;
}
html[data-theme="dark"] .tpl-desc {
  color: #94a3b8;
}

/* 窄屏两张卡片改上下排列 */
@media (max-width: 640px) {
  .create-switch {
    grid-template-columns: 1fr;
  }
}

.scene-picker {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}
.scene-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: var(--surface);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.18s ease, background 0.18s ease, box-shadow 0.18s ease;
}
.scene-card:hover {
  border-color: #bfdbfe;
  background: var(--surface-2);
}
.scene-card.active {
  border-color: #2f80ed;
  background: #eef5ff;
  box-shadow: 0 2px 10px rgba(47, 128, 237, 0.12);
}
.scene-emoji {
  font-size: 18px;
  line-height: 1.2;
}
.scene-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.scene-desc {
  font-size: 11px;
  color: var(--ink-3);
}
.scene-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #b45309;
}

/* ===== 深色主题：列表、状态徽标、行内按钮与场景卡统一压暗 ===== */
html[data-theme="dark"] .survey-list {
  background: var(--surface);
  box-shadow: var(--shadow-md);
}
html[data-theme="dark"] .list-header {
  background: #0c1728;
  color: var(--ink-3);
}
html[data-theme="dark"] .list-row {
  border-bottom-color: #1a2942;
}
html[data-theme="dark"] .list-row:hover {
  background: #16263f;
}
html[data-theme="dark"] .title-text {
  color: var(--ink);
}
html[data-theme="dark"] .title-desc,
html[data-theme="dark"] .row-metrics,
html[data-theme="dark"] .time-line,
html[data-theme="dark"] .col-created {
  color: var(--ink-3);
}
html[data-theme="dark"] .row-metric-dot {
  color: #3a4a63;
}
html[data-theme="dark"] .id-badge {
  color: var(--ink-3);
  background: #16263f;
}
html[data-theme="dark"] .shared-badge {
  color: #a5b4fc;
  background: #1e2a4a;
  border-color: #34406b;
}
html[data-theme="dark"] .shared-block {
  background: #101d31;
}
html[data-theme="dark"] .badge .dot {
  box-shadow: 0 0 0 3px rgba(0, 0, 0, 0.28);
}
html[data-theme="dark"] .badge-published {
  background: #12281c;
  color: #4ade80;
  border-color: #1d4430;
}
html[data-theme="dark"] .badge-draft {
  background: #2a2110;
  color: #fbbf24;
  border-color: #4a3a15;
}
html[data-theme="dark"] .badge-suspended {
  background: #2b1718;
  color: #f87171;
  border-color: #4a2425;
}
html[data-theme="dark"] .badge-ended {
  background: #1c2530;
  color: #94a3b8;
  border-color: #2a3d5a;
}
html[data-theme="dark"] .time-empty {
  color: #3a4a63;
}
html[data-theme="dark"] .row-btn {
  background: #16243a;
}
html[data-theme="dark"] .rb-collab {
  background: #1e2a4a;
  color: #a5b4fc;
  border-color: #34406b;
}
html[data-theme="dark"] .rb-restore {
  background: #2b1718;
  color: #fdba74;
  border-color: #4a2425;
}
html[data-theme="dark"] .rb-edit {
  background: #12233f;
  color: #93c5fd;
  border-color: #1e3a5f;
}
html[data-theme="dark"] .rb-publish {
  background: #12281c;
  color: #86efac;
  border-color: #1d4430;
}
html[data-theme="dark"] .rb-share {
  background: #2a2110;
  color: #fcd34d;
  border-color: #4a3a15;
}
html[data-theme="dark"] .rb-stats {
  background: #241a3a;
  color: #c4b5fd;
  border-color: #3b2a5c;
}
html[data-theme="dark"] .rb-copy {
  background: #0e2a33;
  color: #67e8f9;
  border-color: #14434f;
}
html[data-theme="dark"] .rb-delete {
  background: #2b1718;
  color: #fca5a5;
  border-color: #4a2425;
}
html[data-theme="dark"] .rb-more {
  background: #16243a;
  color: #94a3b8;
  border-color: #22314a;
}
html[data-theme="dark"] .tpl-usage {
  background: #2a2110;
  color: #fcd34d;
  border-color: #4a3a15;
}
html[data-theme="dark"] .scene-card {
  background: #101d31;
  border-color: #1d2c44;
}
html[data-theme="dark"] .scene-card:hover {
  background: #16263f;
  border-color: #2563eb;
}
html[data-theme="dark"] .scene-card.active {
  background: #16263f;
  border-color: #2563eb;
}
html[data-theme="dark"] .scene-name {
  color: var(--ink);
}
html[data-theme="dark"] .scene-desc {
  color: var(--ink-3);
}
html[data-theme="dark"] .scene-hint {
  color: #fbbf24;
}
</style>
