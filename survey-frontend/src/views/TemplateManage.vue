<!--
  模板管理（管理员）。

  ── 为什么需要这个页面 ───────────────────────────────
  之前"设为模板"只有一个入口（「全部问卷」里的开关 + 按钮），但没有**集中维护**的地方：
    · 模板内容写错了，找不到"改模板"的入口（只能去全部问卷里翻）；
    · 分类想调整，只能取消模板再重设；
    · 模板多了以后，根本不知道模板广场里到底有哪些、分别是什么分类。
  本页把 is_template = 1 的问卷集中列出，并给出四个动作：
    编辑内容 / 改分类 / 取消模板 / 删除（移入回收站）。

  ★ 复用现有端点，没有新增接口：
    · 列表  GET  /api/survey/templates
    · 分类  PUT  /api/survey/set-template/{id}?isTemplate=1&category=…
    · 取消  PUT  /api/survey/set-template/{id}?isTemplate=0
    · 删除  PUT  /api/survey/recycle/{id}
    · 编辑  前端路由 /my/surveys/{id}/edit（meta.roles 含 admin）
-->
<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="page-title">模板管理</h2>
        <p class="page-subtitle">
          模板广场共 {{ templates.length }} 个模板 · 累计被使用 <b>{{ totalUsage }}</b> 次 · 可改分类、编辑内容、取消模板或删除
        </p>
      </div>
      <div class="header-actions">
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建模板</el-button>
      </div>
    </div>

    <el-alert
      class="mb-16"
      type="info"
      :closable="false"
      show-icon
      title="模板内容会直接展示给所有登录用户；从模板创建出来的问卷是独立副本，不会随模板更新。模板不需要发布，新建或编辑后保存即可；要修正错误内容，点「编辑内容」改完保存。"
    />

    <div class="toolbar-card">
      <el-select v-model="categoryFilter" placeholder="全部分类" clearable style="width: 190px;">
        <el-option v-for="c in categories" :key="c" :label="`${c}（${categoryCount(c)}）`" :value="c" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="搜索模板标题或描述..."
        clearable
        :prefix-icon="Search"
        style="width: 260px;"
        @keyup.enter="load"
        @clear="load"
      />
      <el-button type="primary" @click="load">搜索</el-button>
      <span class="toolbar-tip">也可以把现有问卷「设为模板」（在「全部问卷」里）</span>
    </div>

    <!-- 分类概览：点一下即筛选，避免只靠下拉框"猜"每个分类有多少模板 -->
    <div v-if="templates.length" class="category-chips">
      <button
        type="button"
        class="cat-chip"
        :class="{ active: !categoryFilter }"
        @click="categoryFilter = ''"
      >
        全部 <b>{{ templates.length }}</b>
      </button>
      <button
        v-for="item in categoryStats"
        :key="item.name"
        type="button"
        class="cat-chip"
        :class="{ active: categoryFilter === item.name }"
        @click="categoryFilter = item.name"
      >
        {{ item.name }} <b>{{ item.count }}</b>
      </button>
    </div>

    <div class="table-card">
      <el-table
        v-loading="loading"
        :data="filtered"
        empty-text="还没有模板"
        :default-sort="{ prop: 'templateUseCount', order: 'descending' }"
      >
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column label="模板标题" min-width="200">
          <template #default="{ row }">
            <el-link type="primary" :underline="false" @click="editContent(row)">{{ row.title }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <el-table-column label="分类" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="success">{{ row.templateCategory || '通用' }}</el-tag>
          </template>
        </el-table-column>
        <!-- 热度：模板被「从模板创建」使用的次数；后端已按它降序返回，这里再给可排序 -->
        <el-table-column prop="templateUseCount" label="使用次数" width="110" align="center" sortable>
          <template #default="{ row }">
            <span v-if="row.templateUseCount > 0" class="usage-badge">🔥 {{ row.templateUseCount }} 次</span>
            <span v-else class="usage-empty">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdTime" label="创建时间" width="165" />
        <!-- ★ 不要 fixed="right"：整表宽度超过容器时，固定列会盖住「创建时间」，
             而这一页 7 列的总宽并不需要固定操作列（见用户截图）。让表格整体滚动即可。 -->
        <el-table-column label="操作" width="330" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="editContent(row)">编辑内容</el-button>
            <el-button type="warning" link size="small" @click="openCategory(row)">改分类</el-button>
            <el-button type="info" link size="small" @click="removeTemplate(row)">取消模板</el-button>
            <el-button type="danger" link size="small" @click="deleteTemplate(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 改分类：allow-create 允许输入新分类（与「设为模板」弹窗一致，分类是模板广场的聚合维度） -->
    <el-dialog v-model="categoryVisible" title="修改模板分类" width="440px">
      <p class="text-secondary" style="margin-top: 0;">模板：{{ categoryForm.title }}</p>
      <el-select
        v-model="categoryForm.category"
        placeholder="选择或输入分类"
        allow-create
        filterable
        class="w-full"
      >
        <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
      </el-select>
      <p class="dialog-tip">分类决定模板广场里的筛选分组；改完立即生效。</p>
      <template #footer>
        <el-button @click="categoryVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCategory">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新建模板：先建一份草稿问卷，再打上模板身份，然后直接进模板编辑器加题目。
         ★ 不走「我的问卷」的新建流程 —— 管理员只维护模板，不碰用户侧页面。 -->
    <el-dialog v-model="createVisible" title="新建模板" width="520px">
      <el-form label-width="80px">
        <el-form-item label="模板标题" required>
          <el-input v-model="createForm.title" maxlength="200" show-word-limit placeholder="例如：员工满意度调查" />
        </el-form-item>
        <el-form-item label="模板描述">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="一句话说明这个模板适合什么场景"
          />
        </el-form-item>
        <el-form-item label="模板分类">
          <el-select v-model="createForm.category" placeholder="选择或输入分类" allow-create filterable class="w-full">
            <el-option v-for="c in categoryOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="dialog-tip">创建后直接进入模板编辑器添加题目；模板不需要发布，保存后即出现在模板广场。</p>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createTemplate">创建并编辑</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import api from '../api'

export default {
  name: 'TemplateManage',
  data() {
    return {
      templates: [],
      loading: false,
      keyword: '',
      /** 分类筛选（空 = 全部） */
      categoryFilter: '',
      categoryVisible: false,
      categoryForm: { id: null, title: '', category: '' },
      saving: false,
      // 新建模板弹窗
      createVisible: false,
      createForm: { title: '', description: '', category: '通用' },
      creating: false,
      // ★ :prefix-icon 用的是**变量**，不是 <Search /> 标签；
      //   直接放进 data（markRaw 避免被 Vue 变成响应式代理）
      Search: markRaw(Search)
    }
  },
  computed: {
    /** 现有模板里出现过的分类（筛选下拉用） */
    categories() {
      return [...new Set(this.templates.map((t) => t.templateCategory || '通用'))].sort()
    },
    /** 分类分布：按模板数降序（分类概览 chips 用） */
    categoryStats() {
      const map = new Map()
      this.templates.forEach((t) => {
        const category = t.templateCategory || '通用'
        map.set(category, (map.get(category) || 0) + 1)
      })
      return [...map.entries()]
        .map(([name, count]) => ({ name, count }))
        .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name, 'zh-CN'))
    },
    /** 全部模板累计被使用次数（顶部副标题） */
    totalUsage() {
      return this.templates.reduce((sum, t) => sum + (Number(t.templateUseCount) || 0), 0)
    },
    /** 改分类时可选的候选：常见分类 + 现有分类（allow-create 仍可输入新值） */
    categoryOptions() {
      const base = ['通用', '满意度调查', '员工调研', '课程评价', '活动反馈', '产品反馈']
      return [...new Set([...base, ...this.categories])]
    },
    /** 分类筛选在前端做：模板总量很小，不值得为它加一个后端查询参数 */
    filtered() {
      if (!this.categoryFilter) return this.templates
      return this.templates.filter((t) => (t.templateCategory || '通用') === this.categoryFilter)
    }
  },
  mounted() {
    this.load()
  },
  methods: {
    /** 某个分类下有多少模板（筛选下拉的文案用） */
    categoryCount(category) {
      return this.templates.filter((t) => (t.templateCategory || '通用') === category).length
    },

    async load() {
      this.loading = true
      try {
        // ★ 复用模板广场接口（is_template = 1 且 deleted = 0）；keyword 由后端做 LIKE 转义
        this.templates = await api.survey.templates({ keyword: this.keyword.trim() })
      } catch {
        // 失败提示由接口层统一弹出
        this.templates = []
      } finally {
        this.loading = false
      }
    },

    /** 编辑模板内容：走管理员专属的模板编辑路由（隐藏发布/设置，返回回模板管理） */
    editContent(row) {
      this.$router.push(`/admin/templates/${row.id}/edit`)
    },

    openCreate() {
      this.createForm = { title: '', description: '', category: '通用' }
      this.createVisible = true
    },

    /**
     * 新建模板：POST /api/survey/add（草稿）→ PUT /api/survey/set-template（打上模板身份）
     * → 跳模板编辑器加题目。两步都在前端完成，后端不需要新端点。
     * ★ 建失败就停；设为模板失败时问卷已成草稿，可到「全部问卷」里手动设模板（不会丢）。
     */
    async createTemplate() {
      const title = (this.createForm.title || '').trim()
      if (!title) {
        ElMessage.warning('请输入模板标题')
        return
      }
      this.creating = true
      try {
        const data = await api.survey.add({
          title,
          description: (this.createForm.description || '').trim()
        })
        if (!data || !data.id) {
          ElMessage.error('创建失败')
          return
        }
        await api.survey.setTemplate(data.id, 1, (this.createForm.category || '通用').trim())
        ElMessage.success('模板已创建，接着添加题目')
        this.createVisible = false
        this.$router.push(`/admin/templates/${data.id}/edit`)
      } catch {
        // 失败提示由接口层统一弹出
      } finally {
        this.creating = false
      }
    },

    openCategory(row) {
      this.categoryForm = {
        id: row.id,
        title: row.title,
        category: row.templateCategory || '通用'
      }
      this.categoryVisible = true
    },

    async saveCategory() {
      const category = (this.categoryForm.category || '').trim()
      if (!category) {
        ElMessage.warning('请选择或输入分类')
        return
      }
      this.saving = true
      try {
        // isTemplate 保持 1：这只是"改分类"，不是取消模板
        await api.survey.setTemplate(this.categoryForm.id, 1, category)
        ElMessage.success('分类已更新')
        this.categoryVisible = false
        this.load()
      } catch {
        // 失败提示由接口层统一弹出
      } finally {
        this.saving = false
      }
    },

    async removeTemplate(row) {
      try {
        await ElMessageBox.confirm(
          `取消「${row.title}」的模板身份？取消后它会从模板广场消失，回到「全部问卷」，分类也会清空。`,
          '取消模板',
          { confirmButtonText: '确定取消', cancelButtonText: '返回', type: 'warning' }
        )
      } catch {
        return
      }
      try {
        await api.survey.setTemplate(row.id, 0, null)
        ElMessage.success('已取消模板')
        this.load()
      } catch {
        // 失败提示由接口层统一弹出
      }
    },

    async deleteTemplate(row) {
      try {
        await ElMessageBox.confirm(
          `把「${row.title}」移入回收站？模板广场会立刻看不到它，之后可在「回收站」恢复或彻底删除。`,
          '删除模板',
          { confirmButtonText: '移入回收站', cancelButtonText: '返回', type: 'warning' }
        )
      } catch {
        return
      }
      try {
        await api.survey.recycle(row.id)
        ElMessage.success('已移入回收站')
        this.load()
      } catch {
        // 失败提示由接口层统一弹出
      }
    }
  }
}
</script>

<style scoped>
.toolbar-tip {
  margin-left: auto;
  font-size: 12px;
  color: var(--ink-3);
}
.dialog-tip {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--ink-3);
}

/* 分类概览：一行可点的 chips，比只看下拉框更直观 */
.category-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.cat-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--stroke);
  border-radius: 999px;
  background: #fff;
  color: var(--ink-2);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.16s var(--ease);
}
.cat-chip b {
  font-weight: 600;
  color: var(--ink-3);
}
.cat-chip:hover {
  border-color: var(--sky-300, #93c5fd);
  color: var(--sky-600, #2563eb);
}
.cat-chip.active {
  background: var(--sky-50, #eff6ff);
  border-color: var(--sky-300, #93c5fd);
  color: var(--sky-700, #1d4ed8);
}
.cat-chip.active b {
  color: var(--sky-600, #2563eb);
}

/* 使用次数：>0 用暖色小胶囊，0 用灰色破折号 */
.usage-badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 999px;
  background: #fff6e8;
  border: 1px solid #ffe6bf;
  color: #b45309;
  font-size: 12px;
  font-weight: 500;
}
.usage-empty {
  color: #cbd5e1;
}
</style>
