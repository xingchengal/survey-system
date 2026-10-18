<template>
  <div class="edit-page">
    <!-- ★ 回收站里的问卷禁止保存/发布：点下去**必然 400**（后端 OwnershipGuard.requireNotInRecycleBin /
          SurveyController.update / QuestionController.save 三处都会拦），所以这里明确告知并给恢复入口。 -->
    <el-alert
      v-if="surveyInfo.deleted === 1"
      class="mb-16"
      type="warning"
      show-icon
      :closable="false"
      title="这份问卷已在回收站中"
    >
      <template #default>
        <div class="mt-8">
          回收站里的问卷<strong>不能</strong>保存或发布（后端会拒绝，返回 400）。请先到
          <el-link type="primary" @click="goRecycle">回收站</el-link>
          把它恢复，再回来编辑。
        </div>
      </template>
    </el-alert>

    <!-- ★ 题目加载失败：既不是"没有题目"，也不能让用户在这里保存（会把题目删光） -->
    <el-alert
      v-if="questionsLoadFailed"
      class="mb-16"
      type="error"
      show-icon
      :closable="false"
      :title="'题目加载失败：' + questionsLoadError"
    >
      <template #default>
        <div class="mt-8">
          已有题目没能读出来，此时保存会把题目全部删掉，所以<strong>已阻止保存</strong>。
          <el-button size="small" type="primary" plain class="ml-10" :loading="reloadingQuestions" @click="retryLoadQuestions">
            重新加载题目
          </el-button>
          <span class="ml-10">若仍失败，请确认后端已重启，并把浏览器控制台里的报错发给我。</span>
        </div>
      </template>
    </el-alert>

    <!-- ★ 被举报下架：内容冻结，只能查看；恢复正常后自动解除 -->
    <el-alert
      v-if="isSuspended"
      class="mb-16"
      type="error"
      show-icon
      :closable="false"
      title="该问卷已被举报下架，内容已冻结：可以查看，不能保存 / 发布 / 回滚。"
    >
      请回到「我的问卷」点「申请恢复」，管理员处理后会解除限制。
    </el-alert>

    <!-- ★ 只读/无发布权（协作者 viewer / analyst）：先把话说清，再把按钮禁掉 -->
    <el-alert
      v-else-if="!canEditSurvey || !canPublishSurvey"
      class="mb-16"
      type="info"
      show-icon
      :closable="false"
      :title="!canEditSurvey
        ? '你在这份问卷上是只读权限：可以查看，不能修改或保存'
        : '你可以编辑这份问卷，但没有发布权限'"
    />

    <!-- 顶部操作栏 -->
    <div class="edit-header">
      <div>
        <h2 class="page-title">问卷编辑器</h2>
        <p class="page-subtitle">
          ID #{{ surveyId }} · 题目结构变化会自动生成历史版本
          <span class="save-state" :class="saveStateClass">{{ saveStateText }}</span>
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="goBack">返回</el-button>
        <!-- ★ 回收站里禁止保存/发布（点了必 400），按钮直接禁用并把原因写在标题里。
             ★ `saving` 门控：保存首次是"预存 + 正式提交"两次往返，期间禁用以免连点触发第二次全量覆盖。 -->
        <!-- ★ 注意：这里**不**因为 questionsLoadFailed 而禁用按钮。
             禁用会让点击"完全没反应"，用户根本不知道发生了什么（实测就是这么被卡住的）。
             现在改成：按钮可点，点下去由 saveQuestions 统一说明"为什么不能保存"。 -->
        <el-button type="primary" :disabled="inRecycleBin || saving || !canEditSurvey" :loading="saving" @click="saveQuestions">保存</el-button>
        <!-- ★ status 为 0 或 2 都要给发布按钮：后端支持"延长（或清空）结束时间后重新发布"。
              已结束但结束时间仍在过去时，按钮变成"重新发布"并禁用（否则用户点了只吃 400），
              并在下面给出"请先把结束时间改到未来"的提示。 -->
        <el-button
          v-if="surveyInfo.status === 0 || surveyInfo.status === 2"
          type="success"
          :disabled="inRecycleBin || saving || !canEditSurvey || !canPublishSurvey || (surveyInfo.status === 2 && !endExtended)"
          @click="publishSurvey"
        >{{ surveyInfo.status === 2 ? '重新发布' : '立即发布' }}</el-button>
        <el-tag v-else-if="surveyInfo.status === 1" type="success" effect="light" round>已发布（可继续修改）</el-tag>
        <!-- 设置：答题密码 / 限收份数 / 提交后展示 / 公开结果 -->
        <el-button :disabled="inRecycleBin || !canEditSurvey" @click="settingsVisible = true">设置</el-button>
        <!-- 预览：按填写者视角看当前编辑态（不保存、不产生答卷） -->
        <el-button :disabled="questions.length === 0" @click="openPreview">预览</el-button>
        <!-- 历史版本：回收站里禁用（回滚走的是"全量覆盖式保存"，在回收站问卷上执行会让历史答卷的 question_id 悬空） -->
        <el-button :disabled="inRecycleBin" @click="openVersions">历史版本</el-button>
      </div>
    </div>

    <!-- ★ 已结束但结束时间还没改到未来：给出可执行的提示，而不是让用户点发布去撞 400 -->
    <el-alert
      v-if="surveyInfo.status === 2 && !endExtended"
      class="mb-16"
      type="info"
      show-icon
      :closable="false"
      title="该问卷已结束，要重新开放请先把「截止时间」改到未来（或清空）再点「重新发布」"
    />

    <!-- 问卷基础信息 -->
    <el-card class="meta-card mb-20">
      <template #header>
        <span class="card-title">🗂 基础信息</span>
      </template>
      <el-form label-width="90px">
        <el-form-item label="问卷标题">
          <!-- ★ maxlength 必须与后端 TextLimits.SURVEY_TITLE(200) / 数据库 varchar(200) 对齐，
               否则用户写超长只有提交后吃 400。 -->
          <el-input v-model="surveyInfo.title" placeholder="请输入问卷标题" maxlength="200" show-word-limit size="large" />
        </el-form-item>
        <el-form-item label="问卷描述">
          <el-input v-model="surveyInfo.description" type="textarea" :rows="2" placeholder="请输入问卷描述" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="定时发布">
          <el-date-picker class="w-full" v-model="surveyInfo.startTime" type="datetime" placeholder="不设置则手动发布" value-format="YYYY-MM-DD HH:mm:ss" />
        </el-form-item>
        <el-form-item label="截止时间">
          <el-date-picker class="w-full" v-model="surveyInfo.endTime" type="datetime" placeholder="不设置则永不过期" value-format="YYYY-MM-DD HH:mm:ss" />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 题目列表 -->
    <div v-if="questions.length === 0" class="empty-questions">
      <div class="empty-emoji">🧩</div>
      <div class="empty-title">还没有题目</div>
      <div class="empty-desc">从下方选择题型，添加第一道题</div>
    </div>

    <el-card
      v-for="(q, qIndex) in questions"
      :key="qIndex"
      class="q-card mb-16"
      :class="{ 'is-dragging': dragQIndex === qIndex, 'is-drag-over': dragOverQIndex === qIndex }"
      draggable="true"
      @dragstart="onQuestionDragStart(qIndex, $event)"
      @dragover.prevent="onQuestionDragOver(qIndex)"
      @dragleave="onQuestionDragLeave(qIndex)"
      @drop.prevent="onQuestionDrop(qIndex)"
      @dragend="onDragEnd"
    >
      <div class="q-head">
        <span class="drag-handle" title="拖动调整题目顺序"><el-icon><Rank /></el-icon></span>
        <div class="q-index">{{ qIndex + 1 }}</div>
        <el-tag :type="qTypeTag(q.type)" effect="light" round size="small">
          {{ qTypeLabel(q.type) }}
        </el-tag>
        <el-checkbox class="ml-10" v-model="q.required" :true-value="1" :false-value="0">必填</el-checkbox>
        <div class="q-spacer"></div>
        <el-button type="danger" link size="small" @click="removeQuestion(qIndex)">删除题目</el-button>
      </div>

      <el-input v-model="q.title" class="q-title-input" placeholder="请输入题目内容" maxlength="500" show-word-limit />

      <!-- 选项区域：单选 / 多选 / 下拉 -->
      <div v-if="qHasOptions(q.type)" class="q-options">
        <div v-for="(opt, oIndex) in q.options" :key="oIndex"
             class="option-row"
             :class="{ 'is-dragging': isDraggingOption(qIndex, oIndex), 'is-drag-over': dragOverOption === qIndex + '-' + oIndex }"
             draggable="true"
             @dragstart.stop="onOptionDragStart(qIndex, oIndex, $event)"
             @dragover.stop.prevent="onOptionDragOver(qIndex, oIndex)"
             @drop.stop.prevent="onOptionDrop(qIndex, oIndex)"
             @dragend.stop="onDragEnd">
          <span class="drag-handle" title="拖动调整选项顺序"><el-icon><Rank /></el-icon></span>
          <span class="option-mark">
            <el-icon><component :is="qOptionIcon(q.type)" /></el-icon>
          </span>
          <el-input v-model="opt.content" class="option-input" placeholder="请输入选项内容" maxlength="500" />

          <!-- 跳转设置：单选和多选都支持 -->
          <!-- ★ `:value` 必须是 question.id：保存时后端会按数组重排 sort_order、并删掉本次没传的题，
               若这里存"第几题"的序号，删题/调序之后已存的规则就会**静默指向错的题**。
               ★ 新建的题在保存前没有数据库 id，所以由前端先发一个**负数的临时 id**（见 data.tempQuestionId），
                 保存成功后替换成真实 id —— 否则新建问卷的每一道题都会因为 `!target.id` 而被禁用，根本设不了跳转。
               ★ 只允许往后跳（ti <= qIndex 的禁用）：反向跳会破坏答题页的"路径推进"模型
                 （那里靠"目标必须在当前题之后"来防环）。 -->
          <el-select
            v-model="opt.nextQuestionId"
            class="option-jump"
            placeholder="跳转：下一题"
            clearable
            size="small"
          >
            <el-option
              v-for="(target, ti) in questions"
              :key="ti"
              :label="`跳到第 ${ti + 1} 题`"
              :value="target.id"
              :disabled="ti <= qIndex"
            />
            <el-option label="结束问卷" :value="-1" />
          </el-select>

          <el-button type="danger" link size="small" @click="removeOption(qIndex, oIndex)">删除</el-button>
        </div>

        <!-- 多选跳转规则提示 -->
        <div v-if="qIsMulti(q.type)" class="option-hint">
          💡 多选跳转规则：勾选的多个选项中，按选项顺序取第一个命中的跳转
        </div>

        <el-button class="add-option" type="primary" plain size="small" @click="addOption(qIndex)">＋ 添加选项</el-button>
      </div>
    </el-card>

    <!-- 底部添加题目 -->
    <div class="add-bar">
      <div class="add-bar-title">添加题目</div>
      <div class="add-actions">
        <button type="button" class="add-tile add-single" @click="addQuestion(1)">
          <span class="add-ico"><el-icon><CircleCheck /></el-icon></span>
          <span class="add-text"><b>单选题</b><i>只能选一个</i></span>
        </button>
        <button type="button" class="add-tile add-multi" @click="addQuestion(2)">
          <span class="add-ico"><el-icon><Select /></el-icon></span>
          <span class="add-text"><b>多选题</b><i>可选多个</i></span>
        </button>
        <button type="button" class="add-tile add-select" @click="addQuestion(5)">
          <span class="add-ico"><el-icon><ArrowDown /></el-icon></span>
          <span class="add-text"><b>下拉选择</b><i>选项多时更清爽</i></span>
        </button>
        <button type="button" class="add-tile add-text-tile" @click="addQuestion(3)">
          <span class="add-ico"><el-icon><EditPen /></el-icon></span>
          <span class="add-text"><b>单行文本</b><i>一句话回答</i></span>
        </button>
        <button type="button" class="add-tile add-textarea" @click="addQuestion(4)">
          <span class="add-ico"><el-icon><Memo /></el-icon></span>
          <span class="add-text"><b>多行文本</b><i>建议与意见</i></span>
        </button>
        <button type="button" class="add-tile add-rating" @click="addQuestion(6)">
          <span class="add-ico"><el-icon><Star /></el-icon></span>
          <span class="add-text"><b>评分题</b><i>1~5 星打分</i></span>
        </button>
        <button type="button" class="add-tile add-date" @click="addQuestion(7)">
          <span class="add-ico"><el-icon><Calendar /></el-icon></span>
          <span class="add-text"><b>日期</b><i>选择年月日</i></span>
        </button>
      </div>
    </div>

    <!-- ===== 历史版本对话框 ===== -->
    <el-dialog v-model="versionVisible" title="历史版本" width="820px">
      <div style="color:#666; font-size:13px; margin-bottom:12px;">
        每次「保存」只要题目结构有变化就会留一个版本（每份问卷最多保留 50 个）。
        ★ 回滚会生成一个新版本，历史不会丢 —— 回滚错了还能再回滚回去。
      </div>

      <el-table class="w-full" :data="versions" stripe border max-height="300"
                empty-text="还没有版本（保存一次题目后才会生成）">
        <el-table-column label="版本" width="80" align="center">
          <template #default="{ row }">v{{ row.versionNo }}</template>
        </el-table-column>
        <el-table-column prop="createdTime" label="生成时间" width="170" />
        <el-table-column prop="questionCount" label="题数" width="70" align="center" />
        <el-table-column prop="changeSummary" label="变更" min-width="240" show-overflow-tooltip />
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small"
                       :loading="rollingBack === row.id"
                       @click="rollbackVersion(row)">回滚</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 版本对比 -->
      <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap; margin-top:16px;">
        <span style="color:#666;">版本对比：</span>
        <el-select v-model="diffFrom" placeholder="旧版本" size="small" style="width:120px;">
          <el-option v-for="v in versions" :key="v.id" :label="'v' + v.versionNo" :value="v.id" />
        </el-select>
        <span>→</span>
        <el-select v-model="diffTo" placeholder="新版本" size="small" style="width:120px;">
          <el-option v-for="v in versions" :key="v.id" :label="'v' + v.versionNo" :value="v.id" />
        </el-select>
        <el-button size="small" type="primary" plain
                   :disabled="!diffFrom || !diffTo || diffFrom === diffTo"
                   @click="doDiff">对比</el-button>
      </div>

      <div v-if="versionDiff" style="margin-top:12px;">
        <el-alert v-if="versionDiff.titleChanged" type="info" :closable="false" style="margin-bottom:8px;"
                  :title="`标题：${versionDiff.titleFrom || '（空）'} → ${versionDiff.titleTo || '（空）'}`" />
        <div v-if="!versionDiff.added.length && !versionDiff.removed.length && !versionDiff.modified.length"
             style="color:#67c23a;">两个版本的题目结构完全一致</div>
        <div v-else>
          <div v-if="versionDiff.added.length" style="margin-bottom:8px;">
            <el-tag type="success" size="small">新增 {{ versionDiff.added.length }}</el-tag>
            <span v-for="x in versionDiff.added" :key="'a' + x.questionId" style="margin-left:8px;">{{ x.title }}</span>
          </div>
          <div v-if="versionDiff.removed.length" style="margin-bottom:8px;">
            <el-tag type="danger" size="small">删除 {{ versionDiff.removed.length }}</el-tag>
            <span v-for="x in versionDiff.removed" :key="'r' + x.questionId" style="margin-left:8px;">{{ x.title }}</span>
          </div>
          <div v-if="versionDiff.modified.length">
            <el-tag type="warning" size="small">修改 {{ versionDiff.modified.length }}</el-tag>
            <div v-for="x in versionDiff.modified" :key="'m' + x.questionId"
                 style="margin-left:8px; margin-top:4px;">
              {{ x.title }}
              <span style="color:#999;">（{{ x.changes.join('；') }}）</span>
            </div>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- ===== 问卷设置（答题密码 / 限收份数 / 提交后展示 / 公开结果） ===== -->
    <el-dialog v-model="settingsVisible" title="问卷设置" width="560px">
      <el-form label-width="110px" class="settings-form">
        <el-form-item label="答题密码">
          <el-input
            v-model="surveyInfo.accessPassword"
            placeholder="留空表示不需要密码"
            maxlength="20"
            show-word-limit
            clearable
          />
          <div class="setting-hint">设置后，填写人必须先输入密码才能看到题目。</div>
        </el-form-item>

        <el-form-item label="限收份数">
          <el-input-number
            v-model="surveyInfo.maxResponses"
            :min="1"
            :max="1000000"
            :step="10"
            controls-position="right"
            placeholder="留空不限"
            style="width: 200px;"
          />
          <span class="setting-inline">留空表示不限；收满后自动拒绝新答卷</span>
        </el-form-item>

        <el-form-item label="提交后感谢语">
          <el-input
            v-model="surveyInfo.thankText"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="留空用默认「提交成功，感谢您的参与」"
          />
        </el-form-item>

        <el-form-item label="提交后跳转">
          <el-input
            v-model="surveyInfo.redirectUrl"
            placeholder="https://…（留空则不跳转）"
            maxlength="500"
            clearable
          />
          <div class="setting-hint">只支持 http / https 链接。</div>
        </el-form-item>

        <el-form-item label="公开结果">
          <div class="setting-switch-row">
            <el-switch v-model="surveyInfo.publicResult" :active-value="1" :inactive-value="0" />
            <el-button
              v-if="surveyInfo.publicResult === 1 && surveyInfo.shareCode"
              link
              type="primary"
              @click="openPublicResult"
            >预览公开结果页 →</el-button>
          </div>
          <div class="setting-hint">
            开启后，任何拿到分享链接的人都能查看汇总图表（**不含**填空原文）。
            需要先点「保存」再预览。
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <div class="settings-footer">
          <span>设置随「保存」一起提交</span>
          <el-button @click="settingsVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- ===== 预览：按填写者视角查看当前编辑态（不保存、不产生答卷） ===== -->
    <el-dialog v-model="previewVisible" width="720px" top="5vh">
      <template #header>
        <div class="pv-header">
          <span class="pv-header-title">问卷预览</span>
          <el-tag size="small" type="info" effect="plain" round>填写者视角</el-tag>
        </div>
      </template>

      <div class="pv-body">
        <h2 class="pv-title">{{ surveyInfo.title || '（还没有填写标题）' }}</h2>
        <p v-if="surveyInfo.description" class="pv-desc">{{ surveyInfo.description }}</p>
        <p v-else class="pv-desc pv-desc-empty">（未填写问卷描述）</p>

        <div v-if="questions.length === 0" class="pv-empty">还没有题目，先在编辑器里添加几道</div>

        <div v-for="(q, i) in questions" :key="i" class="pv-question">
          <div class="pv-q-title">
            <span class="pv-index">{{ i + 1 }}.</span>
            <span class="pv-q-text">{{ q.title || '（还没有填写题目内容）' }}</span>
            <span v-if="q.required" class="pv-required">*</span>
          </div>

          <!-- 单选 -->
          <div v-if="q.type === 1" class="pv-options">
            <label v-for="(opt, oi) in q.options" :key="oi" class="pv-option">
              <input
                v-model="previewAnswers[q.id]"
                type="radio"
                :name="'pv-' + i"
                :value="opt.content"
              />
              <span class="pv-option-text">{{ opt.content || `选项 ${oi + 1}` }}</span>
            </label>
          </div>

          <!-- 多选 -->
          <div v-else-if="q.type === 2" class="pv-options">
            <label v-for="(opt, oi) in q.options" :key="oi" class="pv-option">
              <input
                v-model="previewAnswers[q.id]"
                type="checkbox"
                :value="opt.content"
              />
              <span class="pv-option-text">{{ opt.content || `选项 ${oi + 1}` }}</span>
            </label>
          </div>

          <!-- 下拉选择 -->
          <el-select
            v-else-if="q.type === 5"
            v-model="previewAnswers[q.id]"
            class="pv-select"
            placeholder="请选择…"
            clearable
          >
            <el-option
              v-for="(opt, oi) in q.options"
              :key="oi"
              :label="opt.content || `选项 ${oi + 1}`"
              :value="opt.content"
            />
          </el-select>

          <!-- 评分 -->
          <div v-else-if="q.type === 6" class="pv-rating">
            <span
              v-for="score in 5"
              :key="score"
              class="pv-star"
              :class="{ on: Number(previewAnswers[q.id]) >= score }"
              @click="previewAnswers[q.id] = String(score)"
            >★</span>
            <span class="pv-rating-text">
              {{ previewAnswers[q.id] ? `${previewAnswers[q.id]} 分 · ${ratingLabels[Number(previewAnswers[q.id]) - 1]}` : '点击星星评分' }}
            </span>
          </div>

          <!-- 日期 -->
          <el-date-picker
            v-else-if="q.type === 7"
            v-model="previewAnswers[q.id]"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            class="pv-date"
          />

          <!-- 多行文本 -->
          <el-input
            v-else-if="q.type === 4"
            v-model="previewTexts[q.id]"
            type="textarea"
            :rows="3"
            placeholder="填写者会在这里输入…"
            maxlength="1000"
          />

          <!-- 单行文本 -->
          <el-input
            v-else
            v-model="previewTexts[q.id]"
            class="pv-input"
            placeholder="填写者会在这里输入…"
            maxlength="1000"
          />
        </div>

        <p class="pv-footer-tip">预览只展示题面与选项；正式填写时的跳转、必填校验以发布后的页面为准。</p>
      </div>
    </el-dialog>

    <!-- ===== 发布成功分享卡（替代单条 toast：链接 + 二维码 + 复制） ===== -->
    <el-dialog v-model="shareVisible" title="🎉 发布成功" width="560px">
      <p class="text-secondary">把下面的链接或二维码发给填写人，他们无需登录即可作答：</p>
      <el-input v-model="shareLink" readonly class="share-link">
        <template #append>
          <el-button @click="copyShareLink">复制链接</el-button>
        </template>
      </el-input>
      <div class="share-qr">
        <img v-if="qrDataUrl" :src="qrDataUrl" alt="问卷二维码" />
        <div v-else class="sk-block share-qr-sk"></div>
        <p class="text-secondary">手机扫一扫直接填写</p>
      </div>
      <template #footer>
        <el-button @click="shareVisible = false">关闭</el-button>
        <el-button type="primary" @click="openSharePage">打开填写页</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
// 发布成功后的分享卡用二维码（qrcode 已在依赖里，与 MySurveys 分享弹窗同一套用法）
import QRCode from 'qrcode'
// ★ 统一接口层：本页请求全部走 src/api/ ——
//   接口路径、Authorization、成败判断、错误提示都收敛在 src/api/http.js，
//   页面只关心"调哪个接口、拿到什么数据"。
import api from '../api'
// ★ 判断角色/读用户统一走 utils/auth（不要裸 JSON.parse localStorage）
import { getUser } from '../utils/auth'
// 题型元数据：数字必须与后端 common/QuestionType 一致（只追加、不复用）
import {
  RATING_LABELS, typeLabel, typeMeta, hasOptions, isMulti, isSingle
} from '../utils/questionTypes'

/** 自动草稿在 localStorage 里的键前缀（键 = 前缀 + 问卷 id） */
const DRAFT_PREFIX = 'survey_draft_'
/** 自动草稿的防抖间隔（毫秒）：连续输入时只在停下来后写一次 */
const DRAFT_DEBOUNCE = 2000

export default {
  // ★ 必须用组件内的 beforeRouteLeave（Options API 支持）：watch '$route' 拿不到"即将离开"的时机。
  //   dirty 只在"保存成功"后清掉，所以这里判断它是安全的。
  //   （eslint 的 vue/order-in-components 要求路由守卫写在 data 之前）
  beforeRouteLeave(to, from, next) {
    if (!this.dirty) {
      next()
      return
    }
    this.$confirm('当前修改还没有保存，确定离开吗？', '有未保存的修改', {
      confirmButtonText: '离开',
      cancelButtonText: '留下',
      type: 'warning'
    }).then(() => next()).catch(() => next(false))
  },
  data() {
    return {
      surveyId: null,
      surveyInfo: {
        title: '',
        description: '',
        status: 0,
        shareCode: null,
        startTime: null,
        endTime: null,
        // ★ 后端 findById **不过滤 deleted**，所以详情里带得回来；
        //   用来判断"这份问卷是不是在回收站里"（回收站里禁止编辑/保存/发布）
        deleted: 0,
        // 服务端最后更新时间：用来判断自动草稿是否比服务端版本更新
        updatedTime: null,
        // ===== 问卷设置（设置弹窗里编辑，随「保存」一起提交）=====
        accessPassword: '',
        maxResponses: null,
        thankText: '',
        redirectUrl: '',
        publicResult: 0
      },
      questions: [],

      // ===== 设置弹窗 =====
      settingsVisible: false,
      /** 评分题的 5 档文案，模板里做「N 分 · 满意」的提示 */
      ratingLabels: RATING_LABELS,

      // ===== 未保存提示 / 保存状态 =====
      /** 是否有未保存修改（标题、描述、题目、选项任意变化都会置 true，保存成功后清 false） */
      dirty: false,
      /** 正在用接口数据回填编辑器：期间不把变化算成"用户修改"，避免一进页面就显示未保存 */
      hydrating: false,
      /** 最近一次成功保存（或服务端更新时间）的时间戳 */
      lastSavedAt: null,
      /** 30 秒一跳的当前时间：让"已保存 · N 分钟前"能自己走 */
      nowTick: Date.now(),
      nowTimer: null,
      /** 自动草稿定时器 / 自动草稿最后写入时间 */
      draftTimer: null,

      // ===== 拖拽排序 =====
      /** 正在拖的题目下标 / 悬停到的题目下标 */
      dragQIndex: null,
      dragOverQIndex: null,
      /** 正在拖的选项（'题下标-选项下标'）与悬停到的选项 */
      dragOption: null,
      dragOverOption: null,

      // ===== 预览（填写者视角） =====
      previewVisible: false,
      /** 预览里的作答状态：只存在于本页内存，不会提交、也不会污染编辑态 */
      previewAnswers: {},
      previewTexts: {},

      // ===== 发布成功分享卡 =====
      shareVisible: false,
      shareLink: '',
      qrDataUrl: '',
      /**
       * 是否正在保存（门控）。
       * 保存首次是"预存 + 正式提交"两次往返，期间按钮若还可点，用户很容易连点出第二次保存 ——
       * 两次全量覆盖并发执行，轻则重复插入题目、重则把对方的更新覆盖掉。
       * 所以点击后立刻置 true，**直到两次请求全部结束**（含失败）才放开。
       */
      saving: false,
      /**
       * 草稿阶段的**临时题目 id**（从 -1000 递减）。
       * 新建的题在保存前没有数据库 id，而"跳转目标"这个下拉框必须有一个值可用，
       * 否则新建的问卷完全设不了跳转（AI 创建与模板创建之所以正常，是因为它们的题目自带宽 id）。
       * 前端自己发号，保存成功后会用真实 id 覆盖掉（见 saveQuestions）。
       *
       * ★★ **不能从 -1 开始**：`-1` 在跳转语义里是"结束问卷"这个保留值
       *   （见下拉框的"结束问卷"选项与 SurveyFillByCode 的 `targetId === -1`）。
       *   若某道新题拿到 -1，用户配的"结束问卷"就会在映射时被替换成那道题的 id ——
       *   一句话都不报，症状却是"选了结束却跳到某道题"。
       *   这里从 -1000 起留出安全间隔；真实 id 全是正数，负数区只属于前端。
       */
      tempQuestionId: -1000,

      // ===== 历史版本（编辑历史 / 对比 / 回滚）=====
      versionVisible: false,
      versions: [],
      /** 对比用的两个版本 id（旧 → 新） */
      diffFrom: null,
      diffTo: null,
      /** 对比结果（后端 SurveyVersionDiff） */
      versionDiff: null,
      /** 正在回滚的版本 id（用于按钮 loading，避免连点两次回滚） */
      rollingBack: null,
      /**
       * 题目是否**加载失败**（不是"这份问卷没有题目"）。
       * ★★ 两者必须分开：题目数组在加载失败时也是 []，而保存接口是**全量覆盖**语义 ——
       *   分不清就会在"没读到题目"的情况下点保存，把库里已有题目全部删掉。
       *   实测事故（2026-10-18）：作者给自己的问卷设了答题密码，编辑器读题目时被 400 拦下，
       *   页面显示"还没有题目"，一保存就留下"删除 7 题"的历史版本。
       */
      questionsLoadFailed: false,
      /** 题目加载失败的具体原因（直接显示给用户，别再让他去猜） */
      questionsLoadError: '',
      /** "重新加载题目"按钮的 loading */
      reloadingQuestions: false
    }
  },
  computed: {
    /** 当前登录用户是不是管理员（只用于决定"去回收站"跳哪个路由） */
    isAdmin() {
      const user = getUser()
      return Boolean(user && user.role === 'admin')
    },
    /**
     * 我对这份问卷能否编辑 / 发布（H2）。
     * ★ myPermissions 为 null 表示后端没给（老版本/异常），此时**不拦**，避免把老用户挡在门外；
     *   真正的拦截在后端，前端这里只是"别让按钮点了必 400"。
     */
    canEditSurvey() {
      const perms = this.surveyInfo.myPermissions
      return !perms || perms.includes('survey:edit')
    },
    canPublishSurvey() {
      const perms = this.surveyInfo.myPermissions
      return !perms || perms.includes('survey:publish')
    },
    /** 是否在回收站里（后端口径：deleted != 0；NULL 视为未删除） */
    inRecycleBin() {
      return this.surveyInfo.deleted !== null && this.surveyInfo.deleted !== 0
    },
    /**
     * 是否被举报下架（lifecycle=SUSPENDED）。
     * ★ 下架期间内容冻结：后端会拦保存/发布/回滚/加协作者，
     *   前端把提示写成"申请恢复"而不是泛泛的"只读权限"。
     */
    isSuspended() {
      return this.surveyInfo.lifecycle === 'SUSPENDED'
    },
    /**
     * 「已结束」的问卷是否已经把截止时间改到了未来（或清空）。
     * ★ 与后端 SurveyService.publish 的判定保持一致：endTime 为 null 或晚于当前时间 → 允许重新发布。
     */
    endExtended() {
      if (!this.surveyInfo.endTime) return true
      return new Date(String(this.surveyInfo.endTime).replace(/-/g, '/')).getTime() > Date.now()
    },
    /** 标题下方那行保存状态：保存中 / 有未保存修改 / 已保存 · 相对时间 */
    saveStateText() {
      if (this.saving) return '保存中…'
      if (this.dirty) return '有未保存修改'
      if (!this.lastSavedAt) return '尚未修改'
      const diff = Math.max(0, this.nowTick - this.lastSavedAt)
      if (diff < 60 * 1000) return '已保存 · 刚刚'
      if (diff < 60 * 60 * 1000) return `已保存 · ${Math.floor(diff / 60000)} 分钟前`
      if (diff < 24 * 60 * 60 * 1000) return `已保存 · ${Math.floor(diff / 3600000)} 小时前`
      return `已保存 · ${new Date(this.lastSavedAt).toLocaleDateString('zh-CN')}`
    },
    saveStateClass() {
      return {
        'is-dirty': this.dirty && !this.saving,
        'is-saving': this.saving
      }
    }
  },
  watch: {
    // ★ 深度监听整份编辑态：标题 / 描述 / 时间 / 题目 / 选项任意一处变化都算"有未保存修改"，
    //   同时触发自动草稿（2 秒防抖）。hydrating 期间（接口回填）不算修改。
    surveyInfo: {
      deep: true,
      handler() {
        this.markDirty()
      }
    },
    questions: {
      deep: true,
      handler() {
        this.markDirty()
      }
    }
  },
  mounted() {
    this.surveyId = this.$route.params.id
    // 刷新/关标签页前拦一下（浏览器只认返回字符串，提示文案由浏览器自己出）
    window.addEventListener('beforeunload', this.handleBeforeUnload)
    // 每 30 秒让"已保存 · N 分钟前"自己走字
    this.nowTimer = setInterval(() => {
      this.nowTick = Date.now()
    }, 30000)
    if (this.surveyId) {
      this.init()
    }
  },
  beforeUnmount() {
    window.removeEventListener('beforeunload', this.handleBeforeUnload)
    if (this.nowTimer) clearInterval(this.nowTimer)
    if (this.draftTimer) clearTimeout(this.draftTimer)
  },
  methods: {
    /** 首次进入：并行拉问卷信息与题目 → 再问"要不要恢复草稿" */
    async init() {
      this.hydrating = true
      await Promise.all([this.loadSurveyInfo(), this.loadQuestions()])
      // 等 watch 的 flush 跑完再解除 hydrating，否则回填会被当成用户修改
      await this.$nextTick()
      this.hydrating = false
      // ★ 首次加载失败时**自动重试一次**：后端刚重启/网络抖一下是常见场景，
      //   让人手点 F5 重来一遍很冤；真失败的话顶部会给红条并带上原因。
      if (this.questionsLoadFailed) {
        await new Promise(resolve => setTimeout(resolve, 800))
        await this.loadQuestions()
      }
      this.offerDraftRestore()
    },

    async loadSurveyInfo() {
      try {
        const data = await api.survey.detail(this.surveyId)
        this.surveyInfo = {
          title: data.title || '',
          description: data.description || '',
          status: data.status || 0,
          shareCode: data.shareCode,
          startTime: data.startTime,
          endTime: data.endTime,
          // ★ 带上 deleted —— 回收站里的问卷禁止保存/发布，页面要能判断并给出提示
          deleted: data.deleted == null ? 0 : data.deleted,
          // ★ 带上 lifecycle —— 被举报下架（SUSPENDED）的问卷内容冻结，
          //   页面要显示"申请恢复"的提示，而不是泛泛的"只读权限"。
          lifecycle: data.lifecycle || null,
          updatedTime: data.updatedTime || null,
          // ===== 问卷设置（GET /api/survey/{id} 是作者接口，会带回真实密码）=====
          accessPassword: data.accessPassword || '',
          maxResponses: data.maxResponses == null ? null : data.maxResponses,
          thankText: data.thankText || '',
          redirectUrl: data.redirectUrl || '',
          publicResult: data.publicResult === 1 ? 1 : 0,
          // ★ H2：后端算好的"我对这份问卷能做什么"（null = 老后端，不拦）
          myPermissions: Array.isArray(data.myPermissions) ? data.myPermissions : null
        }
        // 服务端最后更新时间就是"上次保存时间"，进页面即可显示"已保存 · X"
        this.lastSavedAt = this.toTimestamp(data.updatedTime) || null
      } catch {
        // ★ 失败提示已由 src/api/http.js 统一弹出（ElMessage.error），
        //   catch 只保留流程控制的位置（本场景无需额外处理，保持页面不变）。
      }
    },

    async loadQuestions() {
      try {
        // ★ 带 X-Survey-Editor：声明"这是编辑器在读题目"。
        //   后端只在校验来源是编辑器时才允许"作者/管理员免答题密码"；
        //   填写页不带这个头，所以作者自己打开分享链接仍然要输对密码。
        const data = await api.question.list(this.surveyId, {
          headers: { 'X-Survey-Editor': '1' }
        })
        this.questions = this.normalizeQuestions(data)
        this.questionsLoadFailed = false
        this.questionsLoadError = ''
        return true
      } catch (e) {
        // 失败提示由 src/api/http.js 统一弹出（这里只做流程控制）
        // ★★ 必须留下标记：此时 questions 仍是 []，而"保存"是全量覆盖 ——
        //   不拦的话用户点一下保存就把库里已有题目全删了（实测发生过）。
        this.questionsLoadFailed = true
        this.questionsLoadError = (e && e.message) || '未知错误（请查看浏览器控制台）'
        return false
      }
    },

    /** 顶部提示条上的"重新加载题目"：后端重启/网络恢复后不用整页刷新 */
    async retryLoadQuestions() {
      this.reloadingQuestions = true
      try {
        const ok = await this.loadQuestions()
        if (ok) ElMessage.success('题目已重新加载')
        else ElMessage.error('仍然加载失败：' + this.questionsLoadError)
      } finally {
        this.reloadingQuestions = false
      }
    },

    /** 把后端返回的题目数组归一化成编辑器内部结构（保存后重新同步也走它，保证两处口径一致）。 */
    normalizeQuestions(data) {
      return (data || []).map(q => ({
        ...q,
        required: q.required || 0,
        options: q.options || []
      }))
    },

    addQuestion(type) {
      // ★ 草稿阶段的题没有数据库 id，先发一个临时 id（负数），否则"跳转目标"下拉框无值可用。
      const newQ = { id: this.tempQuestionId--, type, title: '', required: 0, options: [] }
      // 单选 / 多选 / 下拉：都给两个空选项起步；评分 / 日期 / 文本类不需要选项
      if (hasOptions(type)) {
        newQ.options = [{ content: '' }, { content: '' }]
      }
      this.questions.push(newQ)
    },

    removeQuestion(index) {
      this.questions.splice(index, 1)
    },

    addOption(qIndex) {
      this.questions[qIndex].options.push({ content: '' })
    },

    removeOption(qIndex, oIndex) {
      this.questions[qIndex].options.splice(oIndex, 1)
    },

    // ==================== 未保存状态 / 自动草稿 ====================

    /** 任何编辑动作都会走到这里：标记 dirty + 排一次自动草稿 */
    markDirty() {
      if (this.hydrating) return
      this.dirty = true
      this.scheduleDraftSave()
    },

    /** 自动草稿：2 秒防抖（连续输入只在停下来后写一次，避免每敲一个字读一次 localStorage） */
    scheduleDraftSave() {
      if (!this.surveyId) return
      if (this.draftTimer) clearTimeout(this.draftTimer)
      this.draftTimer = setTimeout(this.saveDraft, DRAFT_DEBOUNCE)
    },

    saveDraft() {
      this.draftTimer = null
      // ★ 题目没加载出来时**不写草稿**：否则会存下一份 questions=[] 的草稿，
      //   下次进页面选"恢复草稿"就等于把已有题目清空（保存是全量覆盖语义）。
      if (this.questionsLoadFailed) return
      if (!this.surveyId || !this.dirty) return
      try {
        // ★ 答题密码**不写进本地草稿**：localStorage 是明文、且可能多人共用同一台机器。
        //   草稿恢复时本来就只回填标题/描述/起止时间（见 offerDraftRestore），
        //   即使不存密码也不会丢"设置"（设置随保存走服务端）。
        const info = { ...this.surveyInfo }
        delete info.accessPassword
        const payload = {
          savedAt: Date.now(),
          surveyInfo: info,
          questions: this.questions
        }
        localStorage.setItem(DRAFT_PREFIX + this.surveyId, JSON.stringify(payload))
      } catch {
        // 本地存储被禁用/写满：自动草稿失效，但编辑与保存不受影响（不打扰用户）
      }
    },

    /**
     * 进页面时问一次"要不要恢复上次没保存的草稿"。
     * 只有草稿比服务端版本更新才问 —— 否则在别的设备上保存过之后，
     * 旧草稿会把新内容覆盖回去。
     */
    async offerDraftRestore() {
      const key = DRAFT_PREFIX + this.surveyId
      let draft
      try {
        const raw = localStorage.getItem(key)
        draft = raw ? JSON.parse(raw) : null
      } catch {
        localStorage.removeItem(key)
        return
      }
      if (!draft || !draft.savedAt || !Array.isArray(draft.questions)) return
      // ★ 草稿里 0 题、而服务端这次读到了题目：这份草稿多半是"上次读题目失败"时留下的，
      //   恢复它等于把已有题目清空 —— 直接丢弃，不拿它去覆盖真实数据。
      if (draft.questions.length === 0 && this.questions.length > 0) {
        this.clearDraft()
        return
      }
      const serverTime = this.toTimestamp(this.surveyInfo.updatedTime)
      if (serverTime && draft.savedAt <= serverTime) {
        // 服务端已经是更新的版本：草稿过期，直接丢弃
        this.clearDraft()
        return
      }
      try {
        await this.$confirm(
          `检测到上次未保存的草稿（${this.formatDraftTime(draft.savedAt)}），要恢复吗？` +
          '恢复后请记得点「保存」；选择"丢弃草稿"则会删掉它。',
          '恢复草稿',
          { confirmButtonText: '恢复草稿', cancelButtonText: '丢弃草稿', type: 'info' }
        )
      } catch {
        this.clearDraft()
        return
      }
      // 只恢复可编辑字段：status / shareCode / deleted 必须以后端为准
      const info = draft.surveyInfo || {}
      this.hydrating = true
      this.surveyInfo = {
        ...this.surveyInfo,
        title: info.title || '',
        description: info.description || '',
        startTime: info.startTime || null,
        endTime: info.endTime || null
      }
      this.questions = this.normalizeQuestions(draft.questions)
      // ★ 题目来自本地草稿（比服务端新），此时保存是安全的 —— 解除"加载失败"标记，
      //   否则用户会一直卡在"点了保存没反应/被禁用"的状态里。
      this.questionsLoadFailed = false
      this.questionsLoadError = ''
      await this.$nextTick()
      this.hydrating = false
      this.dirty = true
      ElMessage.success('草稿已恢复，记得点「保存」')
    },

    clearDraft() {
      try {
        localStorage.removeItem(DRAFT_PREFIX + this.surveyId)
      } catch {
        // 忽略：清不掉也不影响功能
      }
    },

    /** 保存成功后：清 dirty、记时间、丢掉本地草稿 */
    markSaved() {
      this.dirty = false
      this.lastSavedAt = Date.now()
      this.nowTick = this.lastSavedAt
      // 本次提交会触发 watch（id/sortOrder 变化），用 hydrating 挡掉，避免又变成"有未保存修改"
      this.hydrating = true
      this.$nextTick(() => {
        this.hydrating = false
      })
      this.clearDraft()
    },

    /** "YYYY-MM-DD HH:mm:ss" → 时间戳（Safari 不认带 '-' 的格式，统一换成 '/'） */
    toTimestamp(value) {
      if (!value) return 0
      if (typeof value === 'number') return value
      const t = new Date(String(value).replace(/-/g, '/')).getTime()
      return Number.isNaN(t) ? 0 : t
    },

    formatDraftTime(ts) {
      return new Date(ts).toLocaleString('zh-CN', { hour12: false })
    },

    /** 刷新/关标签页前的兜底提示（浏览器不允许自定义文案） */
    handleBeforeUnload(e) {
      if (!this.dirty) return
      e.preventDefault()
      e.returnValue = ''
    },

    // ==================== 拖拽排序（题目 / 选项） ====================

    /**
     * 从输入框里按下鼠标时不要开始拖拽，否则没法正常选中文字/拖选内容。
     * （卡片整体 draggable="true"，这一步是必须的护栏）
     */
    isTextEditing(e) {
      const el = e && e.target
      return Boolean(el && el.closest && el.closest('input, textarea, .el-select, .el-input'))
    },

    onQuestionDragStart(index, e) {
      if (this.isTextEditing(e)) {
        e.preventDefault()
        return
      }
      this.dragQIndex = index
      if (e.dataTransfer) {
        e.dataTransfer.effectAllowed = 'move'
        // Firefox 必须 setData 才会真正开始拖拽
        e.dataTransfer.setData('text/plain', String(index))
      }
    },

    onQuestionDragOver(index) {
      if (this.dragQIndex === null) return
      this.dragOverQIndex = index
    },

    onQuestionDragLeave(index) {
      if (this.dragOverQIndex === index) this.dragOverQIndex = null
    },

    onQuestionDrop(index) {
      const from = this.dragQIndex
      this.resetDragState()
      if (from === null || from === index) return
      const moved = this.questions.splice(from, 1)[0]
      this.questions.splice(index, 0, moved)
      // 保存时 saveQuestions 按数组顺序写 sortOrder，所以这里不需要碰后端
    },

    onOptionDragStart(qIndex, oIndex, e) {
      if (this.isTextEditing(e)) {
        e.preventDefault()
        return
      }
      this.dragOption = `${qIndex}-${oIndex}`
      if (e.dataTransfer) {
        e.dataTransfer.effectAllowed = 'move'
        e.dataTransfer.setData('text/plain', `${qIndex}-${oIndex}`)
      }
    },

    onOptionDragOver(qIndex, oIndex) {
      if (!this.dragOption) return
      this.dragOverOption = `${qIndex}-${oIndex}`
    },

    onOptionDrop(qIndex, oIndex) {
      const from = this.dragOption
      this.resetDragState()
      if (!from) return
      const [fromQ, fromO] = from.split('-').map(Number)
      // 只允许同一题内部排序：跨题拖动会把选项"搬"到另一题，语义不明确
      if (fromQ !== qIndex || fromO === oIndex) return
      const opts = this.questions[qIndex].options
      const moved = opts.splice(fromO, 1)[0]
      opts.splice(oIndex, 0, moved)
    },

    onDragEnd() {
      this.resetDragState()
    },

    resetDragState() {
      this.dragQIndex = null
      this.dragOverQIndex = null
      this.dragOption = null
      this.dragOverOption = null
    },

    isDraggingOption(qIndex, oIndex) {
      return this.dragOption === `${qIndex}-${oIndex}`
    },

    /**
     * 保存问卷信息 + 题目。
     *
     * @returns {Promise<boolean>} true = 保存成功；false = 校验失败或后端拒绝（提示已弹出）
     * ★ 返回值是给 publishSurvey() 用的：必须先确认"真的保存成功"再去发布，
     *   否则会出现"发布成功但标题/题目改动没保存"（本方法校验失败时只 `return`、
     *   后端失败时 `catch` 吞掉，调用方无从得知）。
     */
    async saveQuestions() {
      // ★★ 题目没加载出来时**禁止保存**：此时 questions=[]，而保存是全量覆盖语义，
      //   提交空数组 = 把这份问卷的题目全部删掉（用户实测踩到过，历史版本里留下"删除 7 题"）。
      //   要恢复：刷新页面重新加载题目（或先排查后端为什么拒绝返回题目）。
      // ★ 只读协作者：按钮已禁用，这里再兜一层（键盘回车/脚本触发都能挡住，并给出原因）。
      if (!this.canEditSurvey) {
        ElMessage.error('你在这份问卷上没有编辑权限')
        return false
      }
      if (this.questionsLoadFailed) {
        // ★ 必须把**原因**说出来：用户看到的是"点了没反应"，实际是题目根本没读到。
        ElMessage.error(
          '题目没加载出来（' + (this.questionsLoadError || '原因未知') +
          '），保存会把题目全部删掉，已阻止。请点页面上方的「重新加载题目」，或刷新页面重试。'
        )
        return false
      }
      if (!this.surveyInfo.title.trim()) {
        ElMessage.warning('请输入问卷标题')
        return false
      }
      for (let i = 0; i < this.questions.length; i++) {
        if (!this.questions[i].title.trim()) {
          ElMessage.warning(`第 ${i + 1} 题的题目内容不能为空`)
          return false
        }
      }
      // ★★ "有选项"的题型（单选/多选/下拉）不允许保存空选项。
      //   为什么必须拦在这里：答题页拿 opt.content 当 el-radio / el-checkbox 的 value，
      //   多个空选项的 value 全是 "" —— 点其中一个，整组会**同时显示为选中**（"一点全点"）；
      //   下拉则渲染成空白面板、选不出任何东西，一旦必填就把访客永久卡在该题。
      //   这类问卷发布出去就是废卷，所以保存这一步直接拒绝（后端 doSaveQuestions 还有同口径兜底）。
      for (let i = 0; i < this.questions.length; i++) {
        const q = this.questions[i]
        if (!this.qHasOptions(q.type)) continue
        const opts = q.options || []
        if (opts.length === 0) {
          ElMessage.warning(`第 ${i + 1} 题的选项不能为空，请至少添加一个选项`)
          return false
        }
        for (let j = 0; j < opts.length; j++) {
          const text = typeof opts[j].content === 'string' ? opts[j].content.trim() : ''
          if (!text) {
            ElMessage.warning(`第 ${i + 1} 题的选项 ${j + 1} 内容不能为空`)
            return false
          }
          // 顺手去首尾空白：避免 "A" 与 "A " 在统计里被算成两个不同选项
          opts[j].content = text
        }
      }
      // ★ 门控不能静默：上一次保存还卡着（网络挂了/请求没返回）时，
      //   用户再点会像"按钮坏了"——这里必须说清楚。
      if (this.saving) {
        ElMessage.warning('上一次保存还没结束，请稍等；若一直转圈，请刷新页面后重试')
        return false
      }

      this.saving = true
      try {
        // 0. ★ **保存前的跳转预检查**：任何选项的跳转目标都必须指向"编辑器里现在存在的某道题"。
        //    典型触发场景：用户删掉了被别的题跳转指向的那道题 ——
        //    这时规则已经失效，必须**拦住保存并说清是哪一题**，
        //    而不是让后端拒绝（提示不够具体）或静默把规则丢掉（用户以为还在）。
        if (!this.validateJumpTargets()) return false

        // 1. 更新问卷信息（含时间字段）
        // ★ 这里【不要】再传 shareCode：
        //   分享码由服务端独占管理（SurveyController.update() 会无条件沿用数据库里的旧值），
        //   传了也会被忽略，属于冗余参数，而且容易让人误以为前端能改分享码。
        // ★ 也【不要】再传 status：SurveyMapper.update 的 SET 列表里根本没有 status
        //   （改状态收口在 publish / updateStatusAndRecycle 等专用入口），
        //   传了不生效，只会让人误以为"保存时能顺手改状态"。
        await api.survey.update({
          id: this.surveyId,
          title: this.surveyInfo.title,
          description: this.surveyInfo.description,
          startTime: this.surveyInfo.startTime,
          endTime: this.surveyInfo.endTime,
          // ★ 问卷设置：后端 update 是**静态 SQL**，没传的字段会被写成 NULL。
          //   所以这里必须每次带上全量设置（留空 = 关闭该功能，是后端的既定语义）。
          accessPassword: this.surveyInfo.accessPassword || null,
          maxResponses: this.surveyInfo.maxResponses ?? null,
          thankText: this.surveyInfo.thankText || null,
          redirectUrl: this.surveyInfo.redirectUrl || null,
          publicResult: this.surveyInfo.publicResult === 1 ? 1 : 0
        })

        // 2. 保存题目
        //   api.question.save(surveyId, questions) 的第二个参数就是**本次完整题目数组**
        //   （后端是全量覆盖语义，见 src/api/question.js 的注释），
        //   返回值是**按下标一一对应**的题目 id 列表（后端保存后回显）。
        // ★ 失败时后端返回 400 + message，由 http.js 弹红字并 throw。
        //
        // ★★ 草稿阶段新加的题还没有数据库 id，而"跳转目标"必须存真 id，所以：
        //    ① 先**预存**一次（提交完整数组，去掉"指向临时题"的跳转）拿到真实 id；
        //    ② 用真实 id 替换掉题目 id 与所有跳转目标；
        //    ③ 再正式保存一次（此时跳转目标全是真 id）。
        //    首次保存因此是两次往返，之后题目都有真 id，永远只有一次。
        const pending = await this.ensureRealQuestionIds()
        if (!pending) return false
        // ★ 换完真 id 之后再查一次：预存可能被后端重建了题目（id 变了），
        //   这时"原来解析得通的目标"有可能解析不通了 —— 必须拦在正式提交之前。
        if (!this.validateJumpTargets()) return false
        // 第二次提交做**有限重发**：第一次已经成功、只是它的响应丢了（网络抖动）时，
        // 重发一次就能救回来 —— 否则用户以为没保存，再点一次反而要走完整流程。
        const idItems = await this.saveWithRetry()
        this.applyServerIdOrder(idItems)
        // ★ 保存成功：清 dirty（离开页面不再提示）、更新"已保存 · 刚刚"、丢掉本地自动草稿
        this.markSaved()
        ElMessage.success('保存成功')
        return true
      } catch (e) {
        // ★ HTTP 错误（400/401/429/5xx）已由 src/api/http.js 弹过红字，这里不重复；
        //   但**非 HTTP 异常**（TypeError / 代码 bug）以前会被这一行静默吞掉 ——
        //   表现就是"点保存毫无反应"。这类错误必须说出来，否则根本没法排查。
        if (!e || typeof e.status !== 'number') {
          console.error('[保存失败]', e)
          ElMessage.error('保存失败：' + ((e && e.message) || '未知错误') + '（先按 F5 刷新页面再试）')
        }
        return false
      } finally {
        this.saving = false
      }
    },

    /**
     * 保存前的跳转预检查。
     *
     * 规则：每个选项的 `nextQuestionId` 只能是
     *   · `null`（按顺序）/ `undefined`（没设）/ `-1`（结束问卷）；或
     *   · 某道**当前编辑器里存在**的题的 id（含还没保存的新题的临时 id —— 它们随后会被换成真 id）。
     *
     * 只报告**第一条**问题并定位到题号，让用户能直接去改。
     *
     * @returns {boolean} true = 全部合法，可以保存；false = 已弹提示，必须中止保存
     */
    validateJumpTargets() {
      const existingIds = new Set(this.questions.map(q => q.id))
      for (let i = 0; i < this.questions.length; i++) {
        const q = this.questions[i]
        for (const opt of q.options || []) {
          const target = opt.nextQuestionId
          if (target === null || target === undefined || target === -1) continue
          if (!existingIds.has(target)) {
            ElMessage.error(
              `第 ${i + 1} 题的选项「${opt.content || '(未填内容)'}」要跳转的那道题已被删除，` +
              `请重新设置该选项的跳转，或清空这项跳转后再保存`
            )
            return false
          }
        }
      }
      return true
    },

    /**
     * 提交题目，失败时**有限重发**（最多 2 次，间隔 400ms）。
     *
     * ★ 只重发**可恢复的失败**：网络中断 / 超时 / 5xx。
     *   4xx（尤其是 400）是**业务拒绝**，重发不会变好，反而会重复打扰用户 —— 直接抛出。
     * ★ 为什么值得重发：走到这里说明"预存"已经成功、临时 id 也已经换成真 id，
     *   只差最后一次落库；网络抖一下就让用户重头再点一遍，代价远大于重发一次。
     */
    async saveWithRetry() {
      const MAX_ATTEMPTS = 2
      let lastError = null
      for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
          return await api.question.save(this.surveyId, this.questions)
        } catch (e) {
          lastError = e
          // 有状态码且小于 500 → 业务拒绝，不重发
          if (typeof e?.status === 'number' && e.status < 500) throw e
          if (attempt < MAX_ATTEMPTS) {
            await new Promise(r => setTimeout(r, 400))
          }
        }
      }
      throw lastError
    },

    /**
     * 让**所有题目**都拥有真实的数据库 id（草稿阶段只有前端发的临时 id，从 -1000 递减）。
     *
     * ★★ **预存必须提交"完整的题目数组"**：后端这个接口是**全量覆盖**语义
     *   （没传的题会被删掉），所以只提交新题会把问卷里**已有的题目全删了**。
     *   这里提交完整数组，只把**指向临时 id 的跳转目标去掉**
     *   —— 它们此刻还没有对应的真实题，留着只会被后端置空并把这几条规则悄悄丢掉。
     *
     * @returns {Promise<boolean>} true = 现在所有题都有真 id（可以正式保存）；
     *                             false = 预存失败（提示已弹出，调用方应中止本次保存）
     */
    async ensureRealQuestionIds() {
      if (!this.questions.some(q => !this.isRealId(q.id))) {
        return true   // 常见路径：题目都已在库里，一次提交即可
      }
      // ① 完整数组 + 去掉"指向临时题"的跳转（完整是硬要求，见方法注释）
      const payload = this.questions.map(q => ({
        id: this.isRealId(q.id) ? q.id : undefined,   // 已有题带上真 id，避免被当成新题重建
        type: q.type,
        title: q.title,
        required: q.required,
        options: (q.options || []).map(o => ({
          content: o.content,
          nextQuestionId: this.isRealId(o.nextQuestionId) || o.nextQuestionId === -1
            ? o.nextQuestionId
            : null
        }))
      }))
      const idItems = await api.question.save(this.surveyId, payload)
      if (!Array.isArray(idItems) || idItems.length !== payload.length) {
        ElMessage.error('保存题目失败（后端未返回题目 id，请重试）')
        return false
      }
      // ② 临时 id → 真实 id（题目自身的 id，以及跳转目标里指向临时 id 的）
      const idMap = new Map()
      this.questions.forEach((q, i) => {
        if (!this.isRealId(q.id)) idMap.set(q.id, idItems[i].id)
      })
      this.questions.forEach(q => {
        if (idMap.has(q.id)) q.id = idMap.get(q.id)
        ;(q.options || []).forEach(opt => {
          if (idMap.has(opt.nextQuestionId)) opt.nextQuestionId = idMap.get(opt.nextQuestionId)
        })
      })
      return true
    },

    /** 真实 id 是**正数**；前端临时 id 是负数（-1000 起），`-1` 是"结束问卷"这个保留值。 */
    isRealId(id) {
      return typeof id === 'number' && id > 0
    },

    /**
     * 保存成功后，把编辑态的题目顺序/真实 id 与后端回显对齐。
     *
     * 后端按数组顺序重排 sortOrder，所以回显的第 i 项就对应本次提交的第 i 项。
     * 这里只做**校验 + 兜底**：正常情况下编辑态已经是正确的（正式提交前就换好了真 id）。
     */
    applyServerIdOrder(idItems) {
      if (!Array.isArray(idItems) || idItems.length !== this.questions.length) return
      let mismatch = 0
      idItems.forEach((item, i) => {
        const q = this.questions[i]
        if (q && this.isRealId(item.id) && q.id !== item.id) {
          q.id = item.id      // 兜底：理论上不该发生，发生即说明上面的映射有漏
          mismatch++
        }
        if (q) q.sortOrder = item.sortOrder
      })
      if (mismatch > 0) {
        ElMessage.error(`有 ${mismatch} 道题的 id 与后端不一致，跳转规则可能不准确，请重新检查后再保存`)
      }
    },

    // ===== 预览（填写者视角） =====

    /**
     * 打开预览：把当前编辑态初始化成一份可点选的"假答卷"。
     * ★ 每次打开都重置：题号/题目会变，残留上次的选择会让人以为"数据串了"。
     * ★ 预览只是把题面渲染一遍，跳转逻辑不模拟 —— 那要跑真实的答题路径，
     *   弹窗里做既容易和填写页逻辑不一致，也会误导用户"预览里这样，发布后就一定这样"。
     */
    openPreview() {
      const answers = {}
      const texts = {}
      this.questions.forEach(q => {
        if (q.type === 2) answers[q.id] = []
        else if (q.type === 1 || q.type === 5 || q.type === 6 || q.type === 7) answers[q.id] = ''
        else texts[q.id] = ''
      })
      this.previewAnswers = answers
      this.previewTexts = texts
      this.previewVisible = true
    },

    // ===== 题型展示（模板里不写 type === 1 这类魔数）=====
    qTypeLabel(type) {
      return typeLabel(type)
    },
    qTypeTag(type) {
      return typeMeta(type).tag
    },
    /** 选项行左侧的小图标：单选✓、多选▣、下拉⌄ */
    qOptionIcon(type) {
      if (type === 2) return 'Select'
      if (type === 5) return 'ArrowDown'
      return 'CircleCheck'
    },
    qHasOptions(type) {
      return hasOptions(type)
    },
    qIsMulti(type) {
      return isMulti(type)
    },
    qIsSingle(type) {
      return isSingle(type)
    },
    openPublicResult() {
      if (!this.surveyInfo.shareCode) return
      window.open(`/r/${this.surveyInfo.shareCode}`, '_blank')
    },

    // ===== 历史版本 =====

    async openVersions() {
      this.versionVisible = true
      await this.loadVersions()
    },

    async loadVersions() {
      this.versions = await api.survey.versions(this.surveyId)
      // 默认预选"最新两版"做对比，省得用户每次手选
      if (this.versions.length >= 2 && !this.diffFrom && !this.diffTo) {
        this.diffTo = this.versions[0].id
        this.diffFrom = this.versions[1].id
      }
      this.versionDiff = null
    },

    async rollbackVersion(v) {
      try {
        await this.$confirm(
          `把题目恢复到 v${v.versionNo}？\n\n会生成一个新版本，历史不会丢（回滚错了还能再回滚回来）。`,
          '回滚版本',
          { type: 'warning', confirmButtonText: '确定回滚', cancelButtonText: '取消' }
        )
      } catch {
        return // 用户取消
      }
      this.rollingBack = v.id
      try {
        const created = await api.survey.rollbackVersion(v.id)
        ElMessage.success(`已回滚到 v${v.versionNo}，生成新版本 v${created.versionNo}`)
        await this.loadVersions()
        // ★ 必须重新加载题目：编辑器里还是回滚前的数组，此时直接点保存会把回滚结果覆盖掉
        await this.loadQuestions()
        if (this.questionsLoadFailed) {
          // 回滚其实成功了（后端已生成新版本），只是刷新题目失败 —— 别让用户以为白点了
          ElMessage.warning('版本回滚已完成，但题目没能重新加载，请刷新页面查看结果')
        }
      } finally {
        this.rollingBack = null
      }
    },

    async doDiff() {
      this.versionDiff = await api.survey.versionDiff(this.diffFrom, this.diffTo)
    },

    async publishSurvey() {
      // ★★ **先确认保存成功，再发布**：否则会出现"发布成功但改动没保存"。
      const wasEnded = this.surveyInfo.status === 2
      const saved = await this.saveQuestions()
      if (!saved) {
        ElMessage.warning('问卷尚未保存成功，已取消发布；请先按上面的提示修正')
        return
      }
      try {
        //   返回后端响应体（后端会按需生成分享码 shareCode）。
        const data = await api.survey.publish(this.surveyId)
        if (data && data.shareCode) {
          this.surveyInfo.status = 1
          this.surveyInfo.shareCode = data.shareCode
          // ★ 发布前已保存成功：状态是干净的（不弹"未保存"提示），并显示分享卡而不是单条 toast
          this.markSaved()
          this.showShareCard()
          ElMessage.success(wasEnded ? '已重新发布' : '发布成功')
        } else {
          ElMessage.error('发布失败')
        }
      } catch {
        // 失败提示由 src/api/http.js 统一弹出
      }
    },

    /** 发布成功后的分享卡：链接 + 二维码（生成失败只少一张图，不影响复制链接） */
    async showShareCard() {
      this.shareLink = `${window.location.origin}/s/${this.surveyInfo.shareCode}`
      this.qrDataUrl = ''
      this.shareVisible = true
      try {
        this.qrDataUrl = await QRCode.toDataURL(this.shareLink, { width: 360, margin: 1 })
      } catch {
        this.qrDataUrl = ''
      }
    },

    copyShareLink() {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(this.shareLink)
          .then(() => ElMessage.success('链接已复制'))
          .catch(() => ElMessage.warning('复制失败，请手动选择复制'))
      } else {
        ElMessage.warning('复制失败，请手动选择复制')
      }
    },

    openSharePage() {
      window.open(this.shareLink, '_blank')
    },

    goBack() {
      this.$router.push('/my/surveys')
    },

    /** ★ 回收站提示里的"去回收站"入口（管理员与普通用户看到的都是自己的回收站页） */
    goRecycle() {
      this.$router.push(this.isAdmin ? '/admin/recycle' : '/my/recycle')
    }
  }
}
</script>

<style scoped>
.edit-page {
  max-width: 960px;
  margin: 0 auto;
}
.page-subtitle {
  margin: 6px 0 0 15px;
  font-size: 13px;
  color: var(--ink-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 窄屏才允许换行，否则按钮会被压出视口 */
@media (max-width: 1100px) {
  .edit-header {
    flex-wrap: wrap;
  }
  .header-actions {
    flex-wrap: wrap;
  }
  .page-subtitle {
    white-space: normal;
  }
}
.edit-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  /* ★ 按钮区不许被挤到第二行：它的位置必须稳定（两次截图的按钮分布不一致就是这里换行导致的） */
  flex-wrap: nowrap;
}

/* ★ 左侧标题区允许收缩（min-width:0 + 省略号），把宽度让给右侧按钮区：
   "已保存 · 45 分钟前" 比 "已保存 · 刚刚" 宽，以前会把按钮挤到下一行。 */
.edit-header > div:first-child {
  min-width: 0;
  flex: 1 1 auto;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;      /* 不参与伸缩：副标题长短不再影响按钮排布 */
  flex-wrap: nowrap;
}
.card-title {
  font-weight: 600;
}
.meta-card :deep(.el-form-item) {
  margin-bottom: 18px;
}
.meta-card :deep(.el-form-item:last-child) {
  margin-bottom: 4px;
}

/* ==================== 空态 ==================== */
.empty-questions {
  text-align: center;
  padding: 48px 20px;
  background: #fff;
  border: 1px dashed var(--stroke-strong);
  border-radius: var(--r-lg);
}
.empty-emoji {
  font-size: 40px;
  line-height: 1;
}
.empty-title {
  margin-top: 10px;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.empty-desc {
  margin-top: 4px;
  font-size: 13px;
  color: var(--ink-3);
}

/* ==================== 题目卡 ==================== */
.q-card {
  border-radius: var(--r-lg);
}
.q-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.q-index {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
  color: #fff;
  background: var(--brand-gradient);
  box-shadow: 0 6px 14px rgba(47, 128, 237, 0.25);
  flex-shrink: 0;
}
.q-spacer {
  flex: 1;
}
.q-title-input :deep(.el-input__wrapper) {
  background: #f8fbff;
  border: 1px solid #eef4fd;
  border-radius: 12px;
  box-shadow: none;
}
.q-title-input :deep(.el-input__inner) {
  height: 44px;
  font-size: 15px;
  font-weight: 600;
}

/* ==================== 选项 ==================== */
.q-options {
  margin-top: 16px;
}
.option-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 12px;
  transition: background 0.18s var(--ease);
}
.option-row:hover {
  background: #f7fbff;
}
.option-mark {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--sky-50);
  color: var(--sky-600);
  flex-shrink: 0;
}
.option-input {
  flex: 1;
  max-width: 420px;
}
.option-jump {
  width: 158px;
  flex-shrink: 0;
}
.option-hint {
  margin: 6px 0 0 36px;
  font-size: 12px;
  color: var(--ink-3);
}
.add-option {
  margin: 12px 0 0 36px;
}

/* ==================== 底部添加题目 ==================== */
.add-bar {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 12px;
  margin-top: 20px;
  padding: 18px 20px;
  background: #fff;
  border: 1px solid var(--stroke);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-sm);
}
.add-bar-title {
  flex-shrink: 0;
  font-weight: 600;
  color: var(--ink-2);
}
/* 7 种题型：网格自动换行，不再挤成一行小方块 */
.add-actions {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 10px;
}
.add-tile {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 14px;
  border: 1px solid transparent;
  cursor: pointer;
  text-align: left;
  transition:
    transform 0.2s var(--ease),
    box-shadow 0.2s var(--ease);
}
.add-tile:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-sm);
}
.add-single {
  background: #eaf3ff;
  border-color: #d5e7ff;
  color: #1d4ed8;
}
.add-multi {
  background: #fff6e8;
  border-color: #ffe6bf;
  color: #b45309;
}
.add-text-tile {
  background: #e9f9ef;
  border-color: #cdf2dc;
  color: #15803d;
}
/* 下拉：与单选同色系，靠图标区分 */
.add-select {
  background: #eaf3ff;
  border-color: #d5e7ff;
  color: #1d4ed8;
}
/* 多行文本：文本类绿色 */
.add-textarea {
  background: #e9f9ef;
  border-color: #cdf2dc;
  color: #15803d;
}
/* 评分：琥珀色（与"多选"同族，都是需要打分的题型） */
.add-rating {
  background: #fff6e8;
  border-color: #ffe6bf;
  color: #b45309;
}
/* 日期：中性青灰，避免和上面几类撞色 */
.add-date {
  background: #e7fbff;
  border-color: #cdf0fa;
  color: #0e7490;
}
.add-ico {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  font-size: 17px;
  flex-shrink: 0;
}
.add-text b {
  display: block;
  font-size: 14px;
}
.add-text i {
  font-style: normal;
  font-size: 11px;
  opacity: 0.75;
}

/* ==================== 保存状态 / 拖拽 ==================== */
.save-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: 10px;
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--ink-3);
  background: #f1f5fb;
}
.save-state::before {
  content: "";
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #16a34a;
}
.save-state.is-dirty {
  color: #b45309;
  background: #fff6e8;
}
.save-state.is-dirty::before {
  background: #f59e0b;
}
.save-state.is-saving {
  color: var(--sky-600);
  background: var(--sky-50);
}
.save-state.is-saving::before {
  background: var(--sky-500);
  animation: state-pulse 1s ease infinite;
}
@keyframes state-pulse {
  50% {
    opacity: 0.3;
  }
}

.drag-handle {
  display: inline-flex;
  align-items: center;
  padding: 4px;
  margin-left: -4px;
  border-radius: 8px;
  color: var(--ink-3);
  cursor: grab;
  transition:
    color 0.18s var(--ease),
    background 0.18s var(--ease);
}
.drag-handle:hover {
  color: var(--sky-600);
  background: var(--sky-50);
}
.drag-handle:active {
  cursor: grabbing;
}
.q-card.is-dragging,
.option-row.is-dragging {
  opacity: 0.5;
}
.q-card.is-drag-over {
  border-color: var(--sky-400);
  box-shadow: 0 0 0 2px var(--sky-100);
}
.option-row.is-drag-over {
  background: var(--sky-50);
  box-shadow: inset 0 2px 0 var(--sky-400);
}

html[data-theme="dark"] .save-state {
  background: #16243a;
  color: #8fa0b8;
}
html[data-theme="dark"] .save-state.is-dirty {
  background: #2a2113;
  color: #fbbf24;
}
html[data-theme="dark"] .save-state.is-saving {
  background: #12233c;
  color: #8ab8f7;
}
html[data-theme="dark"] .drag-handle:hover {
  background: #16243a;
}
html[data-theme="dark"] .option-row.is-drag-over {
  background: #16263f;
}

/* ==================== 发布成功分享卡 ==================== */
.share-link {
  margin-top: 10px;
}
.share-qr {
  margin-top: 18px;
  text-align: center;
}
.share-qr img {
  width: 180px;
  height: 180px;
  padding: 8px;
  border: 1px solid var(--stroke);
  border-radius: 12px;
  background: #fff;
}
.share-qr-sk {
  width: 180px;
  height: 180px;
  margin: 0 auto;
}
.share-qr p {
  margin: 8px 0 0;
  font-size: 13px;
}

/* ==================== 问卷设置 ==================== */
.settings-form :deep(.el-form-item__content) {
  flex-wrap: wrap;
}
.setting-hint {
  width: 100%;
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink-3);
}
.setting-inline {
  margin-left: 10px;
  font-size: 12px;
  color: var(--ink-3);
}
.setting-switch-row {
  display: flex;
  align-items: center;
  gap: 14px;
}
.settings-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: var(--ink-3);
}

/* ==================== 预览（填写者视角） ==================== */
.pv-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.pv-header-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.pv-body {
  max-height: 62vh;
  overflow-y: auto;
  padding: 2px 4px 6px;
}
.pv-title {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: var(--ink);
  letter-spacing: -0.2px;
}
.pv-desc {
  margin: 0 0 20px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink-3);
}
.pv-desc-empty {
  opacity: 0.7;
}
.pv-empty {
  padding: 40px 0;
  text-align: center;
  color: var(--ink-3);
}
.pv-question {
  padding: 16px;
  margin-bottom: 12px;
  border: 1px solid var(--stroke);
  border-radius: 14px;
  background: #fff;
}
.pv-q-title {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--ink);
}
.pv-index {
  color: var(--sky-600);
  flex-shrink: 0;
}
.pv-required {
  color: #ef4444;
}
.pv-options {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.pv-option {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.16s var(--ease);
}
.pv-option:hover {
  background: #f6faff;
}
.pv-option input {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  accent-color: var(--sky-500);
  cursor: pointer;
}
.pv-option-text {
  font-size: 14px;
  color: var(--ink-2);
}
.pv-input {
  max-width: 420px;
}
.pv-select {
  max-width: 320px;
}
.pv-date {
  max-width: 220px;
}
.pv-rating {
  display: flex;
  align-items: center;
  gap: 6px;
}
.pv-star {
  font-size: 26px;
  line-height: 1;
  color: #d8e3f3;
  cursor: pointer;
  transition:
    color 0.16s var(--ease),
    transform 0.16s var(--ease);
}
.pv-star:hover {
  transform: scale(1.12);
}
.pv-star.on {
  color: #f59e0b;
}
.pv-rating-text {
  margin-left: 8px;
  font-size: 13px;
  color: var(--ink-3);
}
.pv-footer-tip {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-3);
}
html[data-theme="dark"] .pv-question {
  background: #101d31;
  border-color: #1d2c44;
}
html[data-theme="dark"] .pv-option:hover {
  background: #16263f;
}

@media (max-width: 900px) {
  .edit-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .add-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .add-actions {
    flex-wrap: wrap;
  }
  .option-row {
    flex-wrap: wrap;
  }
}
</style>
