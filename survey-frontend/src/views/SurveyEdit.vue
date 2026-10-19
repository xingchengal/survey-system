<template>
  <div class="edit-page">
    <div class="editor-shell">
      <!-- 左侧题型面板：按类别摆放，窄屏自动落到底部 -->
      <aside class="add-sidebar">
        <div class="add-bar-title">添加题目</div>
        <div class="add-group">
          <div class="add-group-title">选择题</div>
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
          </div>
        </div>

        <div class="add-group">
          <div class="add-group-title">填空题</div>
          <div class="add-actions">
            <button type="button" class="add-tile add-text-tile" @click="addQuestion(3)">
              <span class="add-ico"><el-icon><EditPen /></el-icon></span>
              <span class="add-text"><b>单行文本</b><i>一句话回答</i></span>
            </button>
            <button type="button" class="add-tile add-textarea" @click="addQuestion(4)">
              <span class="add-ico"><el-icon><Memo /></el-icon></span>
              <span class="add-text"><b>多行文本</b><i>建议与意见</i></span>
            </button>
          </div>
        </div>

        <div class="add-group">
          <div class="add-group-title">评分与日期</div>
          <div class="add-actions">
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

        <div class="add-group">
          <div class="add-group-title">
            进阶题型
            <!-- 价格不写死：唯一出处是后端 WalletFeature（GET /api/wallet/pricing） -->
            <span class="add-group-badge">
              {{ advancedTypeCost == null ? '按份计费' : `${advancedTypeCost} 点 / 份` }}
            </span>
          </div>
          <div class="add-actions">
            <button type="button" class="add-tile add-matrix" @click="addQuestion(8)">
              <span class="add-ico"><el-icon><Grid /></el-icon></span>
              <span class="add-text"><b>矩阵题</b><i>行 × 列打分</i></span>
            </button>
            <button type="button" class="add-tile add-ranking" @click="addQuestion(9)">
              <span class="add-ico"><el-icon><Sort /></el-icon></span>
              <span class="add-text"><b>排序题</b><i>拖成 1~N 名</i></span>
            </button>
            <button type="button" class="add-tile add-nps" @click="addQuestion(10)">
              <span class="add-ico"><el-icon><DataLine /></el-icon></span>
              <span class="add-text"><b>NPS</b><i>0~10 推荐分</i></span>
            </button>
          </div>
        </div>

        <p class="add-sidebar-tip">
          进阶题型按「进阶题型包」计费：每份问卷首次保存矩阵 / 排序 / NPS 时扣一次，之后加题不再扣。
          具体价格见「我的钱包 → 计费规则」。
        </p>
      </aside>

      <div class="editor-main">
    <!-- 回收站里的问卷禁止保存/发布：后端三处都会拦（必 400），这里先告知并给恢复入口 -->
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

    <!-- ★ 题目加载失败 ≠ 没有题目：此时保存是全量覆盖，会把已有题目删光 -->
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

    <!-- 被举报下架：内容冻结，只能查看；管理员处理后自动解除 -->
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

    <!-- 只读/无发布权（viewer / analyst）：先说明原因，再禁用按钮 -->
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
        <h2 class="page-title">{{ isTemplateMode ? '模板编辑器' : '问卷编辑器' }}</h2>
        <p class="page-subtitle">
          ID #{{ surveyId }} · {{ isTemplateMode ? '模板内容会直接展示在模板广场' : '题目结构变化会自动生成历史版本' }}
          <span class="save-state" :class="saveStateClass">{{ saveStateText }}</span>
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="goBack">返回</el-button>
        <!-- 回收站里禁止保存/发布（点了必 400），按钮禁用并把原因写进标题；saving 门控防连点触发第二次全量覆盖 -->
        <!-- 不因题目加载失败而禁用保存：禁用后点击"毫无反应"，改为可点并由 saveQuestions 说明原因 -->
        <el-button type="primary" :disabled="inRecycleBin || saving || !canEditSurvey" :loading="saving" @click="saveQuestions">保存</el-button>
        <!-- 模板模式不出现发布/设置：这些设置不会被复制到副本，摆出来只会误导 -->
        <el-tag v-if="isTemplateMode" type="info" effect="light" round>模板（无需发布）</el-tag>
        <template v-else>
          <!-- status 0/2 都给发布按钮；已结束且截止时间未改到未来时禁用（否则点下去必 400） -->
          <el-button
            v-if="surveyInfo.status === 0 || surveyInfo.status === 2"
            type="success"
            :disabled="inRecycleBin || saving || !canEditSurvey || !canPublishSurvey || (surveyInfo.status === 2 && !endExtended)"
            @click="publishSurvey"
          >{{ surveyInfo.status === 2 ? '重新发布' : '立即发布' }}</el-button>
          <el-tag v-else-if="surveyInfo.status === 1" type="success" effect="light" round>已发布（可继续修改）</el-tag>
          <!-- 考试总分常驻在操作栏：不用打开设置也能看到当前分值合计 -->
          <el-tag v-if="surveyInfo.gradeEnabled === 1" type="warning" effect="light" round>
            考试总分 {{ examTotalScore }} 分
          </el-tag>
          <!-- 设置：答题密码 / 限收份数 / 提交后展示 / 公开结果 -->
          <el-button :disabled="inRecycleBin || !canEditSurvey" @click="settingsVisible = true">设置</el-button>
        </template>
        <!-- 预览：按填写者视角查看当前编辑态，不保存、不落库 -->
        <el-button :disabled="questions.length === 0" @click="openPreview">预览</el-button>
        <!-- 历史版本：回收站里禁用（回滚是覆盖式保存，会让历史答卷的 question_id 悬空） -->
        <el-button :disabled="inRecycleBin" @click="openVersions">历史版本</el-button>
      </div>
    </div>

    <!-- 已结束但截止时间还没改到未来：给出可执行提示，别让用户点发布去撞 400 -->
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
          <!-- maxlength 与后端 TextLimits.SURVEY_TITLE(200) / 数据库 varchar(200) 对齐 -->
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

    <!-- 题目列表（右侧主区） -->
    <div class="editor-body">

    <div class="editor-questions">
    <div v-if="questions.length === 0" class="empty-questions">
      <div class="empty-emoji">🧩</div>
      <div class="empty-title">还没有题目</div>
      <div class="empty-desc">从左侧选择题型，添加第一道题</div>
    </div>

    <el-card
      v-for="(q, qIndex) in questions"
      :key="qIndex"
      class="q-card mb-16"
      :class="{ 'is-dragging': dragQIndex === qIndex, 'is-drag-over': dragOverQIndex === qIndex }"
      @dragover.prevent="onQuestionDragOver(qIndex)"
      @dragleave="onQuestionDragLeave(qIndex)"
      @drop.prevent="onQuestionDrop(qIndex)"
    >
      <div class="q-head">
        <!-- ★ 只有把手可拖（整卡 draggable 会让选中文字/点空白处都误触发拖拽） -->
        <span
          class="drag-handle"
          title="拖动调整题目顺序"
          draggable="true"
          @dragstart="onQuestionDragStart(qIndex, $event)"
          @dragend="onDragEnd"
        ><el-icon><Rank /></el-icon></span>
        <div class="q-index">{{ qIndex + 1 }}</div>
        <el-tag :type="qTypeTag(q.type)" effect="light" round size="small">
          {{ qTypeLabel(q.type) }}
        </el-tag>
        <el-checkbox class="ml-10" v-model="q.required" :true-value="1" :false-value="0">必填</el-checkbox>
        <!-- 分值：只在开启考试模式时出现；0 = 不参与判分 -->
        <div v-if="qIsGradable(q.type) && surveyInfo.gradeEnabled === 1" class="q-score">
          <span class="q-score-label">分值</span>
          <el-input-number
            v-model="q.score"
            :min="0"
            :max="100"
            :step="1"
            size="small"
            controls-position="right"
            style="width: 92px;"
          />
        </div>
        <div class="q-spacer"></div>
        <el-button type="danger" link size="small" @click="removeQuestion(qIndex)">删除题目</el-button>
      </div>

      <el-input v-model="q.title" class="q-title-input" placeholder="请输入题目内容" maxlength="500" show-word-limit />

      <!-- 矩阵题：行列标题在编辑器里是数组，保存时转 JSON 文本 -->
      <div v-if="qIsMatrix(q.type)" class="q-options matrix-editor">
        <div class="matrix-axis">
          <div class="matrix-axis-head">
            <span>行标题（被评价的对象）</span>
            <span class="matrix-axis-count">{{ (q.matrixRows || []).length }} / 20</span>
          </div>
          <div v-for="(row, rIndex) in q.matrixRows" :key="'mr' + rIndex" class="axis-row">
            <el-input
              v-model="q.matrixRows[rIndex]"
              maxlength="50"
              placeholder="如：服务态度"
              @input="markDirty"
            />
            <el-button
              type="danger"
              link
              size="small"
              :disabled="(q.matrixRows || []).length <= 1"
              @click="removeMatrixAxis(qIndex, 'rows', rIndex)"
            >删除</el-button>
          </div>
          <el-button
            class="add-option"
            type="primary"
            plain
            size="small"
            :disabled="(q.matrixRows || []).length >= 20"
            @click="addMatrixAxis(qIndex, 'rows')"
          >＋ 添加行</el-button>
        </div>

        <div class="matrix-axis">
          <div class="matrix-axis-head">
            <span>列标题（每一行的评分/选项）</span>
            <span class="matrix-axis-count">{{ (q.matrixCols || []).length }} / 10</span>
          </div>
          <div v-for="(col, cIndex) in q.matrixCols" :key="'mc' + cIndex" class="axis-row">
            <el-input
              v-model="q.matrixCols[cIndex]"
              maxlength="50"
              placeholder="如：很满意"
              @input="markDirty"
            />
            <el-button
              type="danger"
              link
              size="small"
              :disabled="(q.matrixCols || []).length <= 2"
              @click="removeMatrixAxis(qIndex, 'cols', cIndex)"
            >删除</el-button>
          </div>
          <el-button
            class="add-option"
            type="primary"
            plain
            size="small"
            :disabled="(q.matrixCols || []).length >= 10"
            @click="addMatrixAxis(qIndex, 'cols')"
          >＋ 添加列</el-button>
        </div>

        <div class="option-hint">💡 填写者会看到一张「行 × 列」的表，每行选一个列。行列标题不能为空、不能重复。</div>
      </div>

      <!-- 选项区域：单选 / 多选 / 下拉 / 排序 -->
      <div v-else-if="qHasOptions(q.type)" class="q-options">
        <div v-for="(opt, oIndex) in q.options" :key="oIndex"
             class="option-row"
             :class="{ 'is-dragging': isDraggingOption(qIndex, oIndex), 'is-drag-over': dragOverOption === qIndex + '-' + oIndex }"
             @dragover.stop.prevent="onOptionDragOver(qIndex, oIndex)"
             @drop.stop.prevent="onOptionDrop(qIndex, oIndex)">
          <span
            class="drag-handle"
            title="拖动调整选项顺序"
            draggable="true"
            @dragstart.stop="onOptionDragStart(qIndex, oIndex, $event)"
            @dragend.stop="onDragEnd"
          ><el-icon><Rank /></el-icon></span>
          <span class="option-mark">
            <el-icon><component :is="qOptionIcon(q.type)" /></el-icon>
          </span>
          <el-input v-model="opt.content" class="option-input" placeholder="请输入选项内容" maxlength="500" />

          <!-- 正确答案：只有单选/多选/下拉可判分；单选/下拉只能有一个，由 onCorrectChange 互斥 -->
          <el-checkbox
            v-if="qIsGradable(q.type)"
            v-model="opt.isCorrect"
            class="option-correct"
            :true-value="1"
            :false-value="0"
            @change="onCorrectChange(q, oIndex)"
          >正确答案</el-checkbox>

          <!-- 跳转设置：`:value` 必须是 question.id（存序号会在删题/调序后静默指错）；新题没有真 id，
               先发临时负数 id，保存后按回显换成真 id；只允许往后跳，反向跳会破坏答题页的路径推进模型。 -->
          <el-select
            v-if="!qIsRanking(q.type)"
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
        <!-- 排序题没有跳转（逐项跳转没有意义） -->
        <div v-if="qIsRanking(q.type)" class="option-hint">
          💡 填写者会把全部选项拖成 1~N 名；统计页按**平均名次**排序（越小越靠前）。
        </div>

        <el-button class="add-option" type="primary" plain size="small" @click="addOption(qIndex)">＋ 添加选项</el-button>
      </div>

      <!-- NPS：0~10 分，没有选项可配 -->
      <div v-else-if="qIsNps(q.type)" class="q-nps-hint">
        <span class="q-nps-scale">0 ───────── 10</span>
        <p>填写者在 0~10 分里选一个。统计页自动计算 NPS（推荐者 9~10 减贬损者 0~6 的占比差），并展示分值分布。</p>
      </div>
    </el-card>

    </div><!-- /editor-questions -->

    </div><!-- /editor-body -->

    <!-- 历史版本对话框 -->
    <el-dialog v-model="versionVisible" title="历史版本" width="820px">
      <div style="color: var(--ink-2); font-size:13px; margin-bottom:12px;">
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
        <span style="color: var(--ink-2);">版本对比：</span>
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
              <span style="color: var(--ink-3);">（{{ x.changes.join('；') }}）</span>
            </div>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- 问卷设置：字段与后端 update / SurveyDTO 一一对应，随「保存」一起提交 -->
    <el-dialog v-model="settingsVisible" title="问卷设置" width="560px">
      <el-alert
        class="mb-16"
        type="warning"
        :closable="false"
        show-icon
        title="答题密码 / 限收份数 / 公开结果 / IP 限制 属「高级设置包」：首次启用扣 1 点，之后开关不再扣费。"
      />
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
            开启后，任何拿到分享链接的人都能查看汇总图表（不含填空原文）。
            需要先点「保存」再预览。
          </div>
        </el-form-item>

        <el-form-item label="IP 限制">
          <div class="setting-switch-row">
            <el-switch v-model="surveyInfo.ipLimit" :active-value="1" :inactive-value="0" />
            <el-tag v-if="surveyInfo.ipLimit === 1" type="warning" size="small">同一 IP 只能提交一次</el-tag>
          </div>
          <div class="setting-hint">
            防刷用：开启后同一网络出口只能提交一份答卷。
            ★ 同一公司/校园网/NAT 后的多个真实用户会被当成同一个人，请确认场景后再开启。
          </div>
        </el-form-item>

        <el-divider content-position="left">填写身份</el-divider>

        <el-form-item label="仅限登录用户">
          <div class="setting-switch-row">
            <el-switch v-model="surveyInfo.requireLogin" :active-value="1" :inactive-value="0" />
            <el-tag v-if="surveyInfo.requireLogin === 1" type="warning" size="small">答题人必须先登录</el-tag>
          </div>
          <div class="setting-hint">
            关闭（默认）= 匿名可填，与现在完全一致；开启后答题人必须先登录/注册才能看到题目，
            答卷会记录账号、**同一账号只能提交一份**（适合考试、实名问卷）。
          </div>
        </el-form-item>

        <el-divider content-position="left">现场与名单</el-divider>

        <el-form-item label="启用签到">
          <div class="setting-switch-row">
            <el-switch v-model="surveyInfo.checkinEnabled" :active-value="1" :inactive-value="0" />
            <el-tag v-if="surveyInfo.checkinEnabled === 1" type="success" size="small">可在答卷列表里签到</el-tag>
          </div>
          <div class="setting-hint">
            开启后，「问卷统计 → 所有答卷」每行会出现「签到」按钮，用于活动现场核销。
            数据只记"是否签到 + 签到时间"，不影响答卷内容与分数。
          </div>
        </el-form-item>

        <el-form-item label="公开报名名单">
          <div class="setting-switch-row">
            <el-switch v-model="surveyInfo.showParticipants" :active-value="1" :inactive-value="0" />
            <el-tag v-if="surveyInfo.showParticipants === 1" type="warning" size="small">填写页可见前 50 位</el-tag>
          </div>
          <div class="setting-hint">
            开启后，填写页会显示"第 N 位 · 称呼 · 报名时间"（接龙场景）。
            称呼取第一道单行文本题的回答；请勿在报名问卷里收集敏感信息后再公开名单。
          </div>
        </el-form-item>

        <el-divider content-position="left">考试判分</el-divider>

        <el-form-item label="考试模式">
          <div class="setting-switch-row">
            <el-switch v-model="surveyInfo.gradeEnabled" :active-value="1" :inactive-value="0" />
            <el-tag v-if="surveyInfo.gradeEnabled === 1" type="success" size="small">
              当前总分 {{ examTotalScore }} 分
            </el-tag>
          </div>
          <div class="setting-hint">
            开启后提交答卷时自动判分（单选 / 多选 / 下拉才有分值）。
            ★ 选择题要标出「正确答案」、并给题目填分值，否则保存会被后端拒绝。
          </div>
        </el-form-item>

        <el-form-item v-if="surveyInfo.gradeEnabled === 1" label="及格分">
          <el-input-number
            v-model="surveyInfo.passScore"
            :min="0"
            :max="examTotalScore"
            :step="1"
            controls-position="right"
            placeholder="留空不判及格"
            style="width: 200px;"
          />
          <span class="setting-inline">留空表示只出分；{{ examTotalScore }} 分满分</span>
        </el-form-item>

        <el-form-item label="答题限时">
          <el-input-number
            v-model="surveyInfo.timeLimitMinutes"
            :min="1"
            :max="1440"
            :step="5"
            controls-position="right"
            placeholder="留空不限时"
            style="width: 200px;"
          />
          <span class="setting-inline">分钟；到点自动交卷，超时提交会被服务端拒收</span>
          <div class="setting-hint">
            ★ 倒计时从**打开答题页**开始算。它挡的是"倒计时结束还继续答"的正常超时，
            不是防作弊（耗时由浏览器上报，可伪造）。
          </div>
        </el-form-item>

        <el-form-item v-if="surveyInfo.gradeEnabled === 1" label="提交后显示答案">
          <div class="setting-switch-row">
            <el-switch v-model="surveyInfo.showAnswerAfterSubmit" :active-value="1" :inactive-value="0" />
            <el-tag v-if="surveyInfo.showAnswerAfterSubmit === 1" type="success" size="small">
              答题人会看到逐题对错与正确答案
            </el-tag>
          </div>
          <div class="setting-hint">
            关闭时答题人只能看到分数（默认，避免泄题）；开启后成绩卡会列出每题对错与正确答案。
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

    <!-- 预览：按填写者视角查看当前编辑态，不保存、不产生答卷 -->
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

          <!-- 矩阵：行 × 列的表，每行单选一个列 -->
          <div v-else-if="q.type === 8" class="pv-matrix-wrap">
            <table class="pv-matrix">
              <thead>
                <tr>
                  <th></th>
                  <th v-for="(col, ci) in q.matrixCols" :key="'pc' + ci">{{ col || '（列）' }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, ri) in q.matrixRows" :key="'pr' + ri">
                  <th>{{ row || '（行）' }}</th>
                  <td v-for="(col, ci) in q.matrixCols" :key="'pd' + ci">
                    <input
                      v-model="previewAnswers[q.id][row]"
                      type="radio"
                      :name="'pv-m-' + i + '-' + ri"
                      :value="col"
                    />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 排序：上下移动调整名次 -->
          <div v-else-if="q.type === 9" class="pv-ranking">
            <div v-for="(item, ri) in previewAnswers[q.id]" :key="'rk' + ri" class="pv-rank-row">
              <span class="pv-rank-no">{{ ri + 1 }}</span>
              <span class="pv-rank-text">{{ item || '（选项 ' + (ri + 1) + '）' }}</span>
              <button type="button" class="pv-rank-btn" :disabled="ri === 0"
                      @click="movePreviewRank(q.id, ri, -1)">↑</button>
              <button type="button" class="pv-rank-btn" :disabled="ri === previewAnswers[q.id].length - 1"
                      @click="movePreviewRank(q.id, ri, 1)">↓</button>
            </div>
          </div>

          <!-- NPS：0~10 -->
          <div v-else-if="q.type === 10" class="pv-nps">
            <button
              v-for="score in npsScores"
              :key="'nps' + score"
              type="button"
              class="pv-nps-btn"
              :class="{ on: previewAnswers[q.id] === score }"
              @click="previewAnswers[q.id] = score"
            >{{ score }}</button>
          </div>

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

    <!-- 发布成功分享卡：链接 + 二维码 + 复制 -->
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
      </div><!-- /editor-main -->
    </div><!-- /editor-shell -->
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import QRCode from 'qrcode'
// 请求统一走 src/api/http.js：鉴权、错误提示、成败判断都收口在那里
import api from '../api'
// 价格唯一出处是后端 WalletFeature，编辑器只读不写死
import walletApi from '../api/wallet'
// 角色/用户信息统一走 utils/auth，不要裸读 localStorage
import { getUser } from '../utils/auth'
// 题型数字必须与后端 common/QuestionType 一致（只追加、不复用）
import {
  NPS_SCORES, RATING_LABELS, typeLabel, typeMeta, hasOptions, isMulti, isSingle,
  parseMatrixAxis, stringifyMatrixAxis, isMatrix, isRanking, isNps
} from '../utils/questionTypes'

/** 自动草稿在 localStorage 里的键前缀（键 = 前缀 + 问卷 id） */
const DRAFT_PREFIX = 'survey_draft_'
/** 自动草稿的防抖间隔（毫秒）：连续输入时只在停下来后写一次 */
const DRAFT_DEBOUNCE = 2000

export default {
  // 必须用组件内路由守卫：watch '$route' 拿不到"即将离开"的时机；dirty 只在保存成功后清
  // （vue/order-in-components 要求路由守卫写在 data 之前）
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
        // 后端 findById 不过滤 deleted，所以详情带得回来；回收站里的问卷禁止编辑/保存/发布
        deleted: 0,
        // 服务端最后更新时间：用来判断自动草稿是否比服务端版本更新
        updatedTime: null,
        accessPassword: '',
        maxResponses: null,
        thankText: '',
        redirectUrl: '',
        publicResult: 0,
        // 考试判分：0 = 普通问卷，1 = 判分；passScore 为 null 表示只出分不判及格
        gradeEnabled: 0,
        passScore: null,
        // 限时（用分钟编辑，保存时换算成秒；null = 不限时）
        timeLimitMinutes: null,
        // 提交后是否展示正确答案（只有考试模式有意义）
        showAnswerAfterSubmit: 0,
        // 是否仅限登录用户填写（0=匿名可填，默认；1=必须先登录）
        requireLogin: 0,
        // 签到核销 / 公开报名名单（0=关闭，默认）
        checkinEnabled: 0,
        showParticipants: 0
      },
      questions: [],

      settingsVisible: false,
      /** 评分题的 5 档文案，模板里做「N 分 · 满意」的提示 */
      ratingLabels: RATING_LABELS,
      /** NPS 的 0~10 分（预览与填写页共用同一份常量） */
      npsScores: NPS_SCORES,
      /** 「进阶题型包」价格（/api/wallet/pricing；null = 未取到，不显示数字） */
      advancedTypeCost: null,

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

      /** 正在拖的题目下标 / 悬停到的题目下标 */
      dragQIndex: null,
      dragOverQIndex: null,
      /** 正在拖的选项（'题下标-选项下标'）与悬停到的选项 */
      dragOption: null,
      dragOverOption: null,

      previewVisible: false,
      /** 预览里的作答状态：只存在于本页内存，不会提交、也不会污染编辑态 */
      previewAnswers: {},
      previewTexts: {},

      shareVisible: false,
      shareLink: '',
      qrDataUrl: '',
      /** 保存门控：保存是「预存 + 正式提交」两次往返，期间放开按钮会连点出第二次全量覆盖 */
      saving: false,
      /**
       * 草稿阶段的临时题目 id（从 -1000 递减）：新题没有数据库 id，跳转下拉框又必须有值。
       * ★ 不能从 -1 起：-1 是"结束问卷"的保留值，被新题占用后会静默把跳转改成那道题。
       * 保存成功后按后端回显 [{id, sortOrder}] 覆盖临时 id（见 applyServerIdOrder）。
       */
      tempQuestionId: -1000,

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
       * 题目是否加载失败（不是"这份问卷没有题目"）：加载失败时 questions 也是 []，
       * 而保存是全量覆盖，分不清就会把库里已有题目全部删掉。
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
     * 我对这份问卷能否编辑 / 发布（H2）。myPermissions 为 null = 后端没给，此时不拦；
     * 真正的权限防线在后端，前端只是避免按钮点了必 400。
     */
    canEditSurvey() {
      const perms = this.surveyInfo.myPermissions
      return !perms || perms.includes('survey:edit')
    },
    canPublishSurvey() {
      const perms = this.surveyInfo.myPermissions
      return !perms || perms.includes('survey:publish')
    },
    /** 考试模式：当前可判分题目的分值合计（设置弹窗展示，同时是及格分的上限） */
    examTotalScore() {
      return (this.questions || []).reduce((sum, q) => {
        if (!this.qIsGradable(q.type)) return sum
        const score = Number(q.score) || 0
        return sum + (score > 0 ? score : 0)
      }, 0)
    },
    /** 是否在回收站里（后端口径：deleted != 0；NULL 视为未删除） */
    inRecycleBin() {
      return this.surveyInfo.deleted !== null && this.surveyInfo.deleted !== 0
    },
    /**
     * 是否被举报下架（lifecycle=SUSPENDED）：下架期间后端会拦保存/发布/回滚，前端提示走"申请恢复"。
     */
    isSuspended() {
      return this.surveyInfo.lifecycle === 'SUSPENDED'
    },
    /**
     * 模板编辑模式（路由 meta.fromTemplate）：不显示发布/设置，返回回模板管理而不是「我的问卷」。
     */
    isTemplateMode() {
      return this.$route.meta.fromTemplate === true
    },
    /**
     * 已结束的问卷是否已把截止时间改到未来（或清空）：与后端 SurveyService.publish 判定一致。
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
    // 深度监听整份编辑态：任意变化都算"有未保存修改"并触发自动草稿；hydrating 期间（接口回填）不算。
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
    this.loadAdvancedTypeCost()
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
    /** 取「进阶题型包」价格（失败静默：只是提示，取不到不影响编辑/保存） */
    async loadAdvancedTypeCost() {
      try {
        const list = await walletApi.pricing()
        const item = (list || []).find(x => x.code === 'advanced-types')
        this.advancedTypeCost = item ? item.cost : null
      } catch {
        this.advancedTypeCost = null
      }
    },

    /** 首次进入：并行拉问卷信息与题目 → 再问"要不要恢复草稿" */
    async init() {
      this.hydrating = true
      await Promise.all([this.loadSurveyInfo(), this.loadQuestions()])
      // 等 watch 的 flush 跑完再解除 hydrating，否则回填会被当成用户修改
      await this.$nextTick()
      this.hydrating = false
      // 首次加载失败自动重试一次（后端刚重启/网络抖动很常见）；仍失败由顶部红条给出原因。
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
          // 带上 deleted —— 回收站里的问卷禁止保存/发布，页面要能判断并给出提示
          deleted: data.deleted == null ? 0 : data.deleted,
          // 带上 lifecycle：下架（SUSPENDED）问卷内容冻结，页面要显示"申请恢复"而不是泛泛的只读
          lifecycle: data.lifecycle || null,
          updatedTime: data.updatedTime || null,
          // 问卷设置（作者接口，会带回真实密码）
          accessPassword: data.accessPassword || '',
          maxResponses: data.maxResponses == null ? null : data.maxResponses,
          thankText: data.thankText || '',
          redirectUrl: data.redirectUrl || '',
          publicResult: data.publicResult === 1 ? 1 : 0,
          // IP 限制（高级设置包）：默认 0，老后端不返回该字段时也不会误开
          ipLimit: data.ipLimit === 1 ? 1 : 0,
          // 考试判分：老后端不返回时按"普通问卷"处理（不误开），及格分保持 null
          gradeEnabled: data.gradeEnabled === 1 ? 1 : 0,
          passScore: data.passScore == null ? null : data.passScore,
          // 限时：库里是秒，编辑器按分钟展示（空/0 都视为不限）
          timeLimitMinutes: data.timeLimitSeconds ? Math.round(data.timeLimitSeconds / 60) : null,
          showAnswerAfterSubmit: data.showAnswerAfterSubmit === 1 ? 1 : 0,
          // 仅限登录用户填写：默认 0（老后端不返回时也不会误开）
          requireLogin: data.requireLogin === 1 ? 1 : 0,
          // 签到核销 / 公开报名名单：默认都关
          checkinEnabled: data.checkinEnabled === 1 ? 1 : 0,
          showParticipants: data.showParticipants === 1 ? 1 : 0,
          // H2：后端算好的"我对这份问卷能做什么"（null = 老后端，不拦）
          myPermissions: Array.isArray(data.myPermissions) ? data.myPermissions : null
        }
        // 服务端最后更新时间就是"上次保存时间"，进页面即可显示"已保存 · X"
        this.lastSavedAt = this.toTimestamp(data.updatedTime) || null
      } catch {
        // 失败提示已由 src/api/http.js 统一弹出，这里只做流程控制
      }
    },

    async loadQuestions() {
      try {
        // 带 X-Survey-Editor 声明"编辑器在读题目"：后端只对编辑器放行作者/管理员免答题密码，
        // 填写页不带这个头，作者自己打开分享链接仍要输密码。
        const data = await api.question.list(this.surveyId, {
          headers: { 'X-Survey-Editor': '1' }
        })
        this.questions = this.normalizeQuestions(data)
        this.questionsLoadFailed = false
        this.questionsLoadError = ''
        return true
      } catch (e) {
        // 必须留下加载失败标记：此时 questions 仍是 []，而保存是全量覆盖 ——
        // 不拦的话用户一点保存就把库里已有题目全删了。
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
        // 分值：老数据可能没有这一列，统一归一成 0（不参与判分），否则保存时会被当成没传（未提交）
        score: q.score == null ? 0 : q.score,
        options: (q.options || []).map(o => ({
          ...o,
          // 正确答案标记：只认 0/1，避免历史脏值（true / 2）让勾选框显示异常
          isCorrect: o.isCorrect === 1 ? 1 : 0
        })),
        // 后端矩阵行列是 JSON 文本，编辑器内部用数组；保存前用 stringifyMatrixAxis 转回
        matrixRows: isMatrix(q.type) ? parseMatrixAxis(q.matrixRows) : [],
        matrixCols: isMatrix(q.type) ? parseMatrixAxis(q.matrixCols) : []
      }))
    },

    addQuestion(type) {
      // 草稿阶段的题没有数据库 id，先发一个临时 id（负数），否则"跳转目标"下拉框无值可用。
      const newQ = { id: this.tempQuestionId--, type, title: '', required: 0, options: [], score: 0 }
      // 单选 / 多选 / 下拉 / 排序：都给两个空选项起步；评分 / 日期 / 文本类不需要选项
      if (hasOptions(type)) {
        newQ.options = [{ content: '', isCorrect: 0 }, { content: '', isCorrect: 0 }]
      }
      // 矩阵：2 行 × 3 列起步（最少 1 行 2 列，留一点余量让作者一进页面就能改）
      if (isMatrix(type)) {
        newQ.matrixRows = ['', '']
        newQ.matrixCols = ['', '', '']
      }
      this.questions.push(newQ)
      this.markDirty()
    },

    /** 矩阵题：加/删一行或一列（限制与后端 MatrixConfig 一致：行 ≤20、列 ≤10、至少 1 行 2 列） */
    addMatrixAxis(qIndex, axis) {
      const q = this.questions[qIndex]
      if (!q) return
      const list = axis === 'rows' ? q.matrixRows : q.matrixCols
      if (!Array.isArray(list)) return
      const max = axis === 'rows' ? 20 : 10
      if (list.length >= max) return
      list.push('')
      this.markDirty()
    },

    removeMatrixAxis(qIndex, axis, index) {
      const q = this.questions[qIndex]
      if (!q) return
      const list = axis === 'rows' ? q.matrixRows : q.matrixCols
      if (!Array.isArray(list)) return
      if (list.length <= (axis === 'rows' ? 1 : 2)) return
      list.splice(index, 1)
      this.markDirty()
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
      // 题目没加载出来时不写草稿：否则会存下 questions=[] 的草稿，恢复时等于把已有题目清空。
      if (this.questionsLoadFailed) return
      if (!this.surveyId || !this.dirty) return
      try {
        // 答题密码不写进本地草稿：localStorage 是明文且可能多人共用一台机器；
        // 恢复草稿只回填标题/描述/起止时间，设置仍随保存走服务端。
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
     * 进页面时问一次"要不要恢复上次未保存的草稿"：只有草稿比服务端版本新才问，
     * 否则在别的设备上保存过之后，旧草稿会把新内容覆盖回去。
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
      // 草稿 0 题而服务端读到了题目：多半是上次读题失败留下的，恢复它等于清空题目，直接丢弃。
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
      // 题目来自比服务端新的草稿，保存是安全的：解除加载失败标记，否则保存会一直被拦。
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


    /**
     * 只有拖拽把手（.drag-handle）可拖：整卡 draggable 会让选中文字、点空白处都误触发拖拽；
     * 把手本身不是输入控件，因此也不需要"是否点在输入框上"的护栏。
     */
    onQuestionDragStart(index, e) {
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
     * 返回值供 publishSurvey() 判断：必须真的保存成功才能发布，否则会"发布成功但改动没保存"。
     */
    async saveQuestions() {
      // 题目没加载出来时禁止保存：questions=[] 提交上去 = 把这份问卷的题目全部删掉。
      // 只读协作者：按钮已禁用，这里再兜一层（键盘/脚本触发也能挡住并给出原因）。
      if (!this.canEditSurvey) {
        ElMessage.error('你在这份问卷上没有编辑权限')
        return false
      }
      if (this.questionsLoadFailed) {
        // 必须把原因说出来：否则用户看到的是"点了没反应"，实际是题目根本没读到。
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
      // 矩阵题行列标题必须非空、不重复、够数（与后端 MatrixConfig 同口径）；
      // 前端先拦是为了把"哪一行错了"说清楚，后端 400 只有一句笼统文案。
      for (let i = 0; i < this.questions.length; i++) {
        const q = this.questions[i]
        if (!isMatrix(q.type)) continue
        const rows = (q.matrixRows || []).map(v => String(v == null ? '' : v).trim())
        const cols = (q.matrixCols || []).map(v => String(v == null ? '' : v).trim())
        if (rows.length < 1 || cols.length < 2) {
          ElMessage.warning(`第 ${i + 1} 题（矩阵题）至少需要 1 行、2 列`)
          return false
        }
        const rowError = this.matrixAxisError(rows, '行')
        if (rowError) {
          ElMessage.warning(`第 ${i + 1} 题：${rowError}`)
          return false
        }
        const colError = this.matrixAxisError(cols, '列')
        if (colError) {
          ElMessage.warning(`第 ${i + 1} 题：${colError}`)
          return false
        }
        q.matrixRows = rows
        q.matrixCols = cols
      }
      // 有选项的题型不允许空选项：答题页拿 opt.content 当 radio/checkbox 的值，
      // 空值会让整组同时显示为选中，下拉则渲染成空白面板（后端 doSaveQuestions 同口径兜底）。
      for (let i = 0; i < this.questions.length; i++) {
        const q = this.questions[i]
        if (!this.qHasOptions(q.type)) continue
        const opts = q.options || []
        if (opts.length === 0) {
          ElMessage.warning(`第 ${i + 1} 题的选项不能为空，请至少添加一个选项`)
          return false
        }
        if (isRanking(q.type) && opts.length < 2) {
          ElMessage.warning(`第 ${i + 1} 题（排序题）至少需要 2 个选项才能排序`)
          return false
        }
        // 选项内容必须互不相同：同名选项的 value 相同，点一个会同时选中两个，统计也无法区分。
        const seenOptions = new Set()
        for (let j = 0; j < opts.length; j++) {
          const text = typeof opts[j].content === 'string' ? opts[j].content.trim() : ''
          if (!text) {
            ElMessage.warning(`第 ${i + 1} 题的选项 ${j + 1} 内容不能为空`)
            return false
          }
          if (seenOptions.has(text)) {
            ElMessage.warning(`第 ${i + 1} 题有两个相同的选项「${text}」，请改成不同内容`)
            return false
          }
          seenOptions.add(text)
          // 顺手去首尾空白：避免 "A" 与 "A " 在统计里被算成两个不同选项
          opts[j].content = text
        }
      }
      // 考试配置前端预检（后端同口径）：开启考试至少要有一道题有分值；
      // 设了分值的题必须标正确答案，单选/下拉只能有一个。这里拦是为了说清"第几题错"。
      if (this.surveyInfo.gradeEnabled === 1) {
        if (this.examTotalScore <= 0) {
          ElMessage.warning('已开启考试模式，请先给至少一道单选/多选/下拉题设置分值')
          return false
        }
        for (let i = 0; i < this.questions.length; i++) {
          const q = this.questions[i]
          if (!this.qIsGradable(q.type) || !(Number(q.score) > 0)) continue
          const correct = (q.options || []).filter(o => o.isCorrect === 1).length
          if (correct === 0) {
            ElMessage.warning(`第 ${i + 1} 题设置了 ${q.score} 分，但没有勾选「正确答案」`)
            return false
          }
          if ((q.type === 1 || q.type === 5) && correct !== 1) {
            ElMessage.warning(`第 ${i + 1} 题是单选/下拉，只能有一个正确答案`)
            return false
          }
        }
      }
      // 门控不能静默：上一次保存还卡着时再点会像"按钮坏了"，必须说清楚。
      if (this.saving) {
        ElMessage.warning('上一次保存还没结束，请稍等；若一直转圈，请刷新页面后重试')
        return false
      }

      this.saving = true
      try {
        // 保存前跳转预检查：每个选项的跳转目标都必须是编辑器里现存的某道题。
        // 用户删掉被指向的题后规则已失效，必须拦住保存并说清是哪一题，而不是静默丢掉规则。
        if (!this.validateJumpTargets()) return false

        // 1. 保存题目（api.question.save 第二个参数是本次完整题目数组，后端全量覆盖）。
        // 新题没有真 id，而跳转目标必须存真 id：先预存一次拿到真实 id，
        // 替换题目 id 与所有跳转目标后再正式保存一次；之后题目都有真 id，只需一次。
        // 题目必须排在"更新问卷信息"之前：后端按库里题目分值校验考试总分与及格分。
        const pending = await this.ensureRealQuestionIds()
        if (!pending) return false
        // 换完真 id 后再查一次：预存可能重建题目，导致跳转目标失效。
        if (!this.validateJumpTargets()) return false
        // 第二次提交做有限重发：第一次已成功但响应丢了时，重发一次即可救回。
        const idItems = await this.saveWithRetry()
        this.applyServerIdOrder(idItems)

        // 2. 更新问卷信息（含时间字段与考试设置）。
        // 不要传 shareCode / status：两者由服务端独占管理，传了也不生效。
        await api.survey.update({
          id: this.surveyId,
          title: this.surveyInfo.title,
          description: this.surveyInfo.description,
          startTime: this.surveyInfo.startTime,
          endTime: this.surveyInfo.endTime,
          // 后端 update 是静态 SQL，没传的字段会被写成 NULL：设置必须每次带全量（留空 = 关闭）。
          accessPassword: this.surveyInfo.accessPassword || null,
          maxResponses: this.surveyInfo.maxResponses ?? null,
          thankText: this.surveyInfo.thankText || null,
          redirectUrl: this.surveyInfo.redirectUrl || null,
          publicResult: this.surveyInfo.publicResult === 1 ? 1 : 0,
          // 考试判分也必须每次带上（静态 SQL 不传会被写成 0/NULL），关闭时及格分一并清空。
          gradeEnabled: this.surveyInfo.gradeEnabled === 1 ? 1 : 0,
          passScore: this.surveyInfo.gradeEnabled === 1 ? (this.surveyInfo.passScore ?? null) : null,
          // 限时：分钟 → 秒；"提交后显示答案"只在考试模式下有意义，关闭时传 0。
          timeLimitSeconds: this.surveyInfo.timeLimitMinutes
            ? Math.round(this.surveyInfo.timeLimitMinutes * 60) : null,
          showAnswerAfterSubmit: this.surveyInfo.gradeEnabled === 1
            && this.surveyInfo.showAnswerAfterSubmit === 1 ? 1 : 0,
          // 仅限登录用户填写：静态 SQL 不传会被写成默认值，关闭时也显式传 0
          requireLogin: this.surveyInfo.requireLogin === 1 ? 1 : 0,
          // 签到核销 / 公开报名名单
          checkinEnabled: this.surveyInfo.checkinEnabled === 1 ? 1 : 0,
          showParticipants: this.surveyInfo.showParticipants === 1 ? 1 : 0
        })

        // 保存成功：清 dirty（离开页面不再提示）、更新"已保存 · 刚刚"、丢掉本地自动草稿
        this.markSaved()
        ElMessage.success('保存成功')
        return true
      } catch (e) {
        // HTTP 错误已由 src/api/http.js 弹过红字；非 HTTP 异常（TypeError / 代码 bug）
        // 必须自己报出来，否则表现就是"点保存毫无反应"，无法排查。
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
     * 保存前跳转预检查：nextQuestionId 只能是 null / undefined / -1（结束）或编辑器现存的题目 id。
     * 只报告第一条问题并定位到题号。
     *
     * @returns {boolean} true = 全部合法；false = 已弹提示，必须中止保存
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
     * 矩阵题行/列标题本地校验：非空、不重复、不超长。
     * @returns {string} 空串 = 合法；否则是给用户看的错误文案（不含题号，由调用方补）
     */
    matrixAxisError(list, label) {
      const seen = new Set()
      for (let i = 0; i < list.length; i++) {
        const text = String(list[i] == null ? '' : list[i]).trim()
        if (!text) return `${label}标题第 ${i + 1} 项不能为空`
        if (text.length > 50) return `${label}标题「${text}」不能超过 50 个字符`
        if (seen.has(text)) return `${label}标题不能重复：「${text}」出现了两次`
        seen.add(text)
      }
      return ''
    },

    /**
     * 生成给后端的题目数组：矩阵行列在编辑器里是数组，后端要 JSON 文本，
     * 不转换会 400（错误文案还和矩阵无关）；非矩阵题显式传 null。
     */
    toSavePayload(list) {
      return (list || []).map(q => ({
        ...q,
        // 分值：只有可判分题型保留；其它题型强制 0（后端也会归一，这里让请求体自解释）
        score: this.qIsGradable(q.type) ? (Number(q.score) || 0) : 0,
        matrixRows: isMatrix(q.type) ? stringifyMatrixAxis(q.matrixRows) : null,
        matrixCols: isMatrix(q.type) ? stringifyMatrixAxis(q.matrixCols) : null,
        options: (q.options || []).map(opt => ({
          content: opt.content,
          nextQuestionId: opt.nextQuestionId,
          // 正确答案标记：只有可判分题型保留（排序题也有 options，但正确答案没有意义）
          isCorrect: this.qIsGradable(q.type) && opt.isCorrect === 1 ? 1 : 0
        }))
      }))
    },

    /**
     * 提交题目，失败时有限重发（最多 2 次，间隔 400ms）。
     * 只重发可恢复的失败（网络/超时/5xx）；4xx 是业务拒绝，重发不会变好，直接抛。
     */
    async saveWithRetry() {
      const MAX_ATTEMPTS = 2
      let lastError = null
      for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
          return await api.question.save(this.surveyId, this.toSavePayload(this.questions))
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
     * 让所有题目都拥有真实数据库 id（草稿阶段只有前端发的临时 id）。
     * 预存必须提交完整题目数组：后端是全量覆盖，只提交新题会把已有题目全删；
     * 只去掉指向临时 id 的跳转目标，随后用回显的真 id 替换。
     * @returns {Promise<boolean>} true = 所有题都有真 id；false = 预存失败
     */
    async ensureRealQuestionIds() {
      if (!this.questions.some(q => !this.isRealId(q.id))) {
        return true   // 常见路径：题目都已在库里，一次提交即可
      }
      // ① 完整数组 + 去掉"指向临时题"的跳转（完整是硬要求）；走 toSavePayload 让矩阵先转 JSON
      const payload = this.toSavePayload(this.questions).map(q => ({
        id: this.isRealId(q.id) ? q.id : undefined,   // 已有题带真 id，避免被当成新题重建
        type: q.type,
        title: q.title,
        required: q.required,
        // 预存也要带分值与正确答案：否则这次预存会把考试配置清空（后端全量覆盖）
        score: q.score,
        matrixRows: q.matrixRows,
        matrixCols: q.matrixCols,
        options: (q.options || []).map(o => ({
          content: o.content,
          isCorrect: o.isCorrect,
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
      // ② 临时 id → 真实 id（题目自身 id 与跳转目标）
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
     * 保存成功后按后端回显 [{id, sortOrder}] 对齐编辑态（回显第 i 项对应本次提交第 i 项）。
     * 正常路径不会命中，这里只是校验 + 兜底。
     */
    applyServerIdOrder(idItems) {
      if (!Array.isArray(idItems) || idItems.length !== this.questions.length) return
      let mismatch = 0
      idItems.forEach((item, i) => {
        const q = this.questions[i]
        if (q && this.isRealId(item.id) && q.id !== item.id) {
          q.id = item.id      // 兜底：命中即说明上面的映射有漏
          mismatch++
        }
        if (q) q.sortOrder = item.sortOrder
      })
      if (mismatch > 0) {
        ElMessage.error(`有 ${mismatch} 道题的 id 与后端不一致，跳转规则可能不准确，请重新检查后再保存`)
      }
    },


    /**
     * 打开预览：按当前编辑态初始化一份可点选的"假答卷"，每次打开都重置。
     * 预览只渲染题面，不模拟跳转 —— 与填写页逻辑不一致会误导用户。
     */
    openPreview() {
      const answers = {}
      const texts = {}
      this.questions.forEach(q => {
        if (isMatrix(q.type)) answers[q.id] = {}
        else if (isRanking(q.type)) answers[q.id] = (q.options || []).map(o => o.content)
        else if (q.type === 2) answers[q.id] = []
        else if ([1, 5, 6, 7, 10].includes(q.type)) answers[q.id] = ''
        else texts[q.id] = ''
      })
      this.previewAnswers = answers
      this.previewTexts = texts
      this.previewVisible = true
    },

    /** 预览里的排序：上移/下移一位（正式填写页用的是同一套"数组顺序 = 名次"模型） */
    movePreviewRank(questionId, index, delta) {
      const list = this.previewAnswers[questionId]
      if (!Array.isArray(list)) return
      const target = index + delta
      if (target < 0 || target >= list.length) return
      const [item] = list.splice(index, 1)
      list.splice(target, 0, item)
    },

    qTypeLabel(type) {
      return typeLabel(type)
    },
    qTypeTag(type) {
      return typeMeta(type).tag
    },
    /** 选项行左侧的小图标：单选✓、多选▣、下拉⌄、排序↕ */
    qOptionIcon(type) {
      if (type === 2) return 'Select'
      if (type === 5) return 'ArrowDown'
      if (type === 9) return 'Sort'
      return 'CircleCheck'
    },
    qHasOptions(type) {
      return hasOptions(type)
    },
    qIsMatrix(type) {
      return isMatrix(type)
    },
    qIsRanking(type) {
      return isRanking(type)
    },
    qIsNps(type) {
      return isNps(type)
    },
    qIsMulti(type) {
      return isMulti(type)
    },
    qIsSingle(type) {
      return isSingle(type)
    },
    /** 该题型能否判分（与后端 common/Grader.isGradable 同口径：单选 1 / 多选 2 / 下拉 5） */
    qIsGradable(type) {
      return type === 1 || type === 2 || type === 5
    },
    /**
     * 勾选「正确答案」后的互斥处理：单选/下拉只能有一个正确答案。
     * 统一用 checkbox + 这里互斥，避免单选/多选两套控件导致选项行布局不一致。
     */
    onCorrectChange(q, changedIndex) {
      if (!this.qIsGradable(q.type)) return
      const single = q.type === 1 || q.type === 5
      if (single && q.options[changedIndex] && q.options[changedIndex].isCorrect === 1) {
        q.options.forEach((o, i) => {
          if (i !== changedIndex) o.isCorrect = 0
        })
      }
      this.markDirty()
    },
    openPublicResult() {
      if (!this.surveyInfo.shareCode) return
      window.open(`/r/${this.surveyInfo.shareCode}`, '_blank')
    },


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
        // 必须重新加载题目：否则编辑态还是旧数组，直接保存会把回滚结果覆盖掉
        await this.loadQuestions()
        if (this.questionsLoadFailed) {
          // 回滚其实成功了，只是刷新题目失败 —— 别让用户以为白点了
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
      // 必须先确认保存成功再发布，否则会出现「发布成功但改动没保存」
      const wasEnded = this.surveyInfo.status === 2
      const saved = await this.saveQuestions()
      if (!saved) {
        ElMessage.warning('问卷尚未保存成功，已取消发布；请先按上面的提示修正')
        return
      }
      try {
        const data = await api.survey.publish(this.surveyId)
        if (data && data.shareCode) {
          this.surveyInfo.status = 1
          this.surveyInfo.shareCode = data.shareCode
          // 发布前已保存成功：清掉未保存态，并显示分享卡（而不是单条 toast）
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
      // 模板编辑模式回模板管理：用户侧「我的问卷」对管理员是 0 份空态
      this.$router.push(this.isTemplateMode ? '/admin/templates' : '/my/surveys')
    },

    /** 回收站提示里的"去回收站"入口：管理员与普通用户各去自己的回收站页 */
    goRecycle() {
      this.$router.push(this.isAdmin ? '/admin/recycle' : '/my/recycle')
    }
  }
}
</script>

<style scoped>
.edit-page {
  /* 编辑器是独立全屏页面（不套 AdminLayout）：左侧题型面板占满页面高度 */
  min-height: 100vh;
  box-sizing: border-box;
  padding: 18px 22px 32px;
  background: var(--surface-2);
}
.editor-shell {
  display: flex;
  gap: 18px;
  align-items: flex-start;
  max-width: 1520px;
  margin: 0 auto;
}
.editor-main {
  flex: 1;
  min-width: 0;
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
  /* 按钮区不许换行：换行会把操作按钮挤到下一行 */
  flex-wrap: nowrap;
}

/* 左侧标题区可收缩（min-width:0 + 省略号）把宽度让给按钮区；否则长状态文案会把按钮挤到下一行 */
.edit-header > div:first-child {
  min-width: 0;
  flex: 1 1 auto;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 0 0 auto;      /* 不参与伸缩：副标题长短不影响按钮排布 */
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

.empty-questions {
  text-align: center;
  padding: 48px 20px;
  background: var(--surface);
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
  background: var(--surface-2);
  border: 1px solid #eef4fd;
  border-radius: 12px;
  box-shadow: none;
}
.q-title-input :deep(.el-input__inner) {
  height: 44px;
  font-size: 15px;
  font-weight: 600;
}

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
  background: var(--surface-2);
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
/* 考试判分：选项行「正确答案」勾选 + 题头「分值」 */
.option-correct {
  flex-shrink: 0;
  margin-right: 2px;
}
.q-score {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.q-score-label {
  font-size: 12px;
  color: var(--ink-3);
}
.option-hint {
  margin: 6px 0 0 36px;
  font-size: 12px;
  color: var(--ink-3);
}
.add-option {
  margin: 12px 0 0 36px;
}

.matrix-editor {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
}
.matrix-axis {
  flex: 1 1 260px;
  min-width: 240px;
}
.matrix-axis-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--ink-3);
}
.matrix-axis-count {
  flex-shrink: 0;
  color: var(--ink-3);
}
.axis-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.axis-row .el-input {
  flex: 1;
}

.q-nps-hint {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--sky-50);
  font-size: 13px;
}
.q-nps-scale {
  letter-spacing: 2px;
  font-weight: 600;
  color: var(--sky-600);
}
.q-nps-hint p {
  margin: 8px 0 0;
  line-height: 1.6;
  color: var(--ink-3);
}

/* 布局：左侧 sticky 题型栏固定宽 + 右侧题目列表可伸缩；窄屏时题型栏落到底部 */
.editor-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}
.editor-questions {
  flex: 1;
  min-width: 0;
}
.add-sidebar {
  width: 300px;
  flex-shrink: 0;
  position: sticky;
  top: 16px;
  padding: 14px;
  background: var(--surface);
  border: 1px solid var(--stroke);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-sm);
}
.add-bar-title {
  flex-shrink: 0;
  font-weight: 600;
  color: var(--ink-2);
}
.add-group {
  margin-top: 12px;
}
.add-group-title {
  display: flex;
  align-items: center;
  font-size: 12px;
  color: var(--ink-3);
  margin-bottom: 6px;
}
.add-group-badge {
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 999px;
  background: #fef3c7;
  color: #b45309;
  font-size: 10px;
  line-height: 1.5;
}
.add-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.add-sidebar-tip {
  margin: 14px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-3);
}
@media (max-width: 1150px) {
  /* 窄屏：题目在前、题型面板在后（先看内容，再往下加题） */
  .editor-shell {
    flex-direction: column;
  }
  .editor-main {
    order: 1;
    width: 100%;
  }
  .add-sidebar {
    order: 2;
    width: 100%;
    position: static;
    margin-top: 16px;
  }
  .add-actions {
    flex-direction: row;
    flex-wrap: wrap;
  }
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
.add-select {
  background: #eaf3ff;
  border-color: #d5e7ff;
  color: #1d4ed8;
}
.add-textarea {
  background: #e9f9ef;
  border-color: #cdf2dc;
  color: #15803d;
}
.add-rating {
  background: #fff6e8;
  border-color: #ffe6bf;
  color: #b45309;
}
.add-date {
  background: #e7fbff;
  border-color: #cdf0fa;
  color: #0e7490;
}
.add-matrix {
  background: #fdeef0;
  border-color: #f8d4da;
  color: #b91c1c;
}
.add-ranking {
  background: #fff6e8;
  border-color: #ffe6bf;
  color: #b45309;
}
.add-nps {
  background: #eef2ff;
  border-color: #dbe3ff;
  color: #4338ca;
}
.add-ico {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--surface);
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

.save-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: 10px;
  padding: 1px 9px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--ink-3);
  background: var(--surface-2);
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
  background: var(--surface);
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
  background: var(--surface);
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
  background: var(--surface-2);
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

.pv-matrix-wrap {
  overflow-x: auto;
}
.pv-matrix {
  min-width: 100%;
  border-collapse: collapse;
}
.pv-matrix th,
.pv-matrix td {
  padding: 8px 10px;
  border: 1px solid var(--stroke);
  font-size: 13px;
  text-align: center;
}
.pv-matrix thead th {
  background: var(--surface-2);
  font-weight: 600;
  color: var(--ink-2);
}
.pv-matrix tbody th {
  text-align: left;
  font-weight: 500;
  white-space: nowrap;
  color: var(--ink-2);
}
.pv-matrix input {
  accent-color: var(--sky-500);
  cursor: pointer;
}
.pv-ranking {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.pv-rank-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--stroke);
  border-radius: 10px;
}
.pv-rank-no {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--sky-50);
  color: var(--sky-600);
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}
.pv-rank-text {
  flex: 1;
  font-size: 14px;
  color: var(--ink-2);
}
.pv-rank-btn {
  width: 28px;
  height: 28px;
  border: 1px solid var(--stroke);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink-2);
  cursor: pointer;
}
.pv-rank-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.pv-nps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.pv-nps-btn {
  width: 38px;
  height: 38px;
  border: 1px solid var(--stroke);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink-2);
  font-size: 14px;
  cursor: pointer;
}
.pv-nps-btn.on {
  background: var(--sky-500);
  border-color: var(--sky-500);
  color: #fff;
  font-weight: 600;
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
html[data-theme="dark"] .pv-matrix thead th,
html[data-theme="dark"] .q-nps-hint {
  background: #16263f;
}
html[data-theme="dark"] .pv-rank-btn,
html[data-theme="dark"] .pv-nps-btn {
  background: #0e1b2e;
  border-color: #1d2c44;
  color: #cbd5e1;
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
