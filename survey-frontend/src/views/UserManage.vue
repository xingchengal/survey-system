<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-subtitle">共 {{ total }} 位用户 · 支持角色筛选、多选批量禁用/解禁/删除、导出 Excel</p>
      </div>
    </div>

    <div class="toolbar-card">
      <!-- ★ 账号注销（软删除）：两个视图。
           "在用用户"里看不到已注销的人（后端按设计过滤了 deleted_at），
           所以恢复入口必须有一个能**看见**他们的列表，否则 restore 就是"有端点没入口"。
           ★ 两个视图的行集合、可执行的动作都不同，切换时必须清空选中。 -->
      <el-radio-group v-model="view" @change="handleViewChange">
        <el-radio-button value="active">在用用户</el-radio-button>
        <el-radio-button value="deleted">已注销</el-radio-button>
      </el-radio-group>
      <!-- 角色筛选（G3）：后端 /user/page 已改为**含管理员**（原来写死 role='user'），
           所以这一项才真的能筛出东西；不选 = 全部角色。 -->
      <el-select v-model="roleFilter" placeholder="全部角色" clearable class="role-select" @change="handleSearch">
        <el-option label="全部角色" value="" />
        <el-option label="管理员" value="admin" />
        <el-option label="普通用户" value="user" />
      </el-select>
      <!-- 禁用状态筛选（G3）：只在"在用"视图有意义（已注销的人不参与禁用）。
           ★ 默认是"全部"，**不是**"只看正常" —— 否则被禁用的人会从列表消失、再也解不了禁。 -->
      <el-select
        v-if="view === 'active'"
        v-model="statusFilter"
        placeholder="全部状态"
        clearable
        class="status-select"
        @change="handleSearch"
      >
        <el-option label="全部状态" value="" />
        <el-option label="正常" :value="0" />
        <el-option label="已禁用" :value="1" />
      </el-select>
      <el-input
        v-model="keyword"
        placeholder="搜索用户名或昵称..."
        clearable
        :prefix-icon="Search"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-button type="primary" @click="handleSearch">搜索</el-button>
      <el-button @click="handleReset">重置</el-button>
      <el-button :icon="Refresh" @click="loadUsers">刷新</el-button>
      <el-button type="success" plain :icon="Download" :loading="exporting" @click="handleExport">导出 Excel</el-button>
      <TableDensity />
    </div>

    <!-- 批量操作条：选中 ≥1 项才出现。
         ★ 所有批量动作都是**逐条调单条端点**（Promise.allSettled），后端没有批量端点 ——
           逐条调用能精确报告"哪几个成功、哪几个为什么失败"。
         ★ 批量删除是**物理删除 + 级联**（该用户名下问卷与答卷一起删），所以文案比单个删除更重。 -->
    <div v-if="selectedRows.length > 0" class="batch-bar">
      <span class="batch-count">已选 <strong>{{ selectedRows.length }}</strong> 项</span>
      <template v-if="view === 'active'">
        <el-button type="warning" size="small" :icon="Lock" :loading="batchWorking" @click="handleBatchDisable">批量禁用</el-button>
        <el-button type="success" size="small" :icon="Unlock" :loading="batchWorking" @click="handleBatchEnable">批量解禁</el-button>
        <el-button type="danger" size="small" :icon="Delete" :loading="batchWorking" @click="handleBatchDelete">批量删除</el-button>
      </template>
      <template v-else>
        <el-button type="primary" size="small" :icon="RefreshLeft" :loading="batchWorking" @click="handleBatchRestore">批量恢复</el-button>
      </template>
      <el-button size="small" link @click="clearSelection">取消选择</el-button>
      <span class="batch-hint">
        {{ view === 'active' ? '禁用后对方会被立即踢下线（数据保留，可解禁）' : '恢复后对方需用原密码重新登录' }}
      </span>
    </div>

    <div class="table-card">
    <PageState
      :loading="loading"
      :error="error"
      :empty="users.length === 0"
      :empty-text="view === 'deleted' ? '没有被注销的账号' : '暂无用户'"
      @retry="loadUsers"
    >
    <el-table
      ref="tableRef"
      class="w-full"
      :data="users"
      stripe
      border
      max-height="560"
      row-key="id"
      @selection-change="handleSelectionChange"
    >
      <!-- 自己不可选：后端明确拒绝"禁用/删除自己"，与其让管理员点出一个 400，不如直接不给选 -->
      <el-table-column type="selection" width="46" align="center" :selectable="rowSelectable" />
      <el-table-column prop="id" label="ID" width="80" align="center" />
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column prop="nickname" label="昵称" min-width="140" />
      <el-table-column prop="role" label="角色" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.role === 'admin' ? 'danger' : 'info'">
            {{ row.role === 'admin' ? '管理员' : '普通用户' }}
          </el-tag>
        </template>
      </el-table-column>
      <!-- 在用视图：禁用状态（G3）。已注销视图：注销时间（管理员要找的通常是刚注销的那个） -->
      <el-table-column v-if="view === 'active'" label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.disabled === 1" type="danger" size="small" effect="dark">已禁用</el-tag>
          <el-tag v-else type="success" size="small" effect="plain">正常</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="view === 'deleted'" prop="deletedAt" label="注销时间" width="180" />
      <el-table-column v-else prop="createdTime" label="注册时间" width="180" />
      <el-table-column label="操作" width="180" align="center">
        <template #default="{ row }">
          <template v-if="view === 'deleted'">
            <el-button type="primary" link size="small" @click="handleRestore(row)">
              恢复账号
            </el-button>
          </template>
          <template v-else>
            <el-button
              v-if="row.disabled === 1"
              type="success"
              link
              size="small"
              @click="handleToggleDisabled(row)"
            >解禁</el-button>
            <el-button
              v-else
              type="warning"
              link
              size="small"
              :disabled="row.id === currentUserId"
              @click="handleToggleDisabled(row)"
            >禁用</el-button>
            <el-button
              type="danger"
              link
              size="small"
              :disabled="row.id === currentUserId"
              @click="handleDelete(row)"
            >删除</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager-row">
      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :page-sizes="[5, 10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>
    </PageState>
    </div>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// Refresh / Search / Download 用于属性变量形式（:icon="Refresh"、:prefix-icon="Search"），必须 import
import { Refresh, Search, Download, Delete, Lock, Unlock, RefreshLeft } from '@element-plus/icons-vue'
// ★ 统一接口层（src/api）：用户分页 / 删除 / 恢复 / 禁用 / 导出都走它，
//   分页参数对象直接传，query 拼接与错误提示都由接口层负责
import api from '../api'
// 三态容器：骨架 / 空 / 错误（带重试）。列表页统一用它，别再各写各的
import PageState from '../components/PageState.vue'
// 行密度切换（默认 / 紧凑）
import TableDensity from '../components/TableDensity.vue'
// 页码记忆：翻到第 N 页 → 进详情 → 返回，仍停在第 N 页（sessionStorage）
import { readPageNum, savePageNum } from '../utils/pageMemory'
// 当前登录用户：用它判断"这一行是不是自己"（自己不可禁用/删除，后端也会拒绝）
import { getUser } from '../utils/auth'

/** 本页在 sessionStorage 里的页码键（每个列表页唯一） */
const PAGE_KEY = 'UserManage'

// 图标已在 main.js 里全局注册，这里不再重复注册
// （模板标签走全局注册；:prefix-icon="Search" 这种属性变量走本文件顶部的 import，两者都不受影响）
export default {
  components: { PageState, TableDensity },
  data() {
    return {
      users: [],
      loading: false,
      // 接口失败标记：由 PageState 渲染「加载失败 + 重试」，不再只弹一条 toast
      error: false,
      keyword: '',
      // 角色筛选（G3）：'' = 全部；后端只认 admin/user
      roleFilter: '',
      // 禁用状态筛选（G3）：'' = 全部、0 = 正常、1 = 已禁用。
      // ★ 只在"在用"视图用；切到已注销时清空（那个视图的行都是"已注销"，与禁用无关）
      statusFilter: '',
      // 列表视图：在用用户 / 已注销。★ 必须声明在 data 里才是响应式
      // （漏声明时 v-model 只写到实例 ctx 上，切视图后模板不会重渲染）
      view: 'active',
      pageNum: 1,
      pageSize: 10,
      total: 0,
      // ===== G3：多选 / 批量 / 导出 =====
      selectedRows: [],
      batchWorking: false,
      exporting: false,
      // 自己的 id：禁用/删除自己会被后端拒绝，前端先把按钮置灰
      currentUserId: (getUser() && getUser().id) || null,
      Search: markRaw(Search),
      Refresh: markRaw(Refresh),
      Download: markRaw(Download),
      Delete: markRaw(Delete),
      Lock: markRaw(Lock),
      Unlock: markRaw(Unlock),
      RefreshLeft: markRaw(RefreshLeft)
    }
  },
  mounted() {
    // ★ 顶栏全局搜索跳过来时带 ?keyword=用户名：直接按它搜一次（页码回 1，避免落在第 3 页看不到人）
    const kw = this.$route && this.$route.query ? this.$route.query.keyword : ''
    if (typeof kw === 'string' && kw.trim()) {
      this.keyword = kw.trim()
      this.pageNum = 1
    } else {
      // 恢复上次停留的页码（从详情页返回时不会被弹回第 1 页）
      this.pageNum = readPageNum(PAGE_KEY)
    }
    this.loadUsers()
  },
  methods: {
    async loadUsers() {
      this.loading = true
      this.error = false
      try {
        // 参数对象直接给接口层（空关键字由 toQuery 自动跳过）
        const params = {
          pageNum: this.pageNum,
          pageSize: this.pageSize,
          keyword: this.keyword.trim(),
          role: this.roleFilter
        }
        // ★ 按视图选接口 —— 已注销的人走 /page-deleted
        //   （/page 按设计过滤了 deleted_at，用它永远看不到已注销账号，也就无从恢复）
        if (this.view === 'deleted') {
          const data = await api.user.pageDeleted(params)
          this.users = data.list || []
          this.total = data.total || 0
        } else {
          // disabled 只在"在用"视图传；'' 会被 toQuery 跳过（= 不筛选）
          const data = await api.user.page({ ...params, disabled: this.statusFilter })
          this.users = data.list || []
          this.total = data.total || 0
        }
      } catch {
        // ★ 接口层已统一提示，这里只保留流程控制；error 交给 PageState 显示重试入口
        this.error = true
      } finally {
        this.loading = false
      }
    },

    /**
     * 切换「在用用户 / 已注销」视图。
     * ★ 先清空列表再拉：否则切换瞬间会看到上一个视图的数据（像是新数据），
     *   而两边的"ID/角色"长得很像，很容易让人误判。
     * ★ 同时清空选中与禁用筛选：两个视图可执行的动作不同，留着上一批选中会误操作。
     */
    handleViewChange() {
      this.users = []
      this.total = 0
      this.selectedRows = []
      this.statusFilter = ''
      this.pageNum = 1
      savePageNum(PAGE_KEY, 1)
      this.loadUsers()
    },

    handleSearch() {
      this.pageNum = 1
      savePageNum(PAGE_KEY, 1)
      this.loadUsers()
    },

    /** 重置筛选条件（G3）：关键词 / 角色 / 禁用状态一起清 */
    handleReset() {
      this.keyword = ''
      this.roleFilter = ''
      this.statusFilter = ''
      this.handleSearch()
    },

    handleSizeChange(size) {
      this.pageSize = size
      this.pageNum = 1
      savePageNum(PAGE_KEY, 1)
      this.loadUsers()
    },

    handlePageChange(page) {
      this.pageNum = page
      savePageNum(PAGE_KEY, page)
      this.loadUsers()
    },

    // ===== G3：多选与批量 =====
    handleSelectionChange(rows) {
      this.selectedRows = rows || []
    },

    /** 自己那一行不可勾选（后端会拒绝"禁用/删除自己"，见 UserService.setDisabled / deleteUser） */
    rowSelectable(row) {
      return row.id !== this.currentUserId
    },

    clearSelection() {
      this.selectedRows = []
      if (this.$refs.tableRef) {
        this.$refs.tableRef.clearSelection()
      }
    },

    /**
     * 批量动作的统一执行器。
     * ★ 逐条调用 + allSettled：一条失败不影响其它条（后端每条本来就是独立事务），
     *   最后用一句汇总告诉管理员"成功几个、失败几个、失败的前三条为什么"。
     * ★ 每个请求都传 `{ silent: true }`：否则 10 条里 3 条失败会弹 3 次红字，比汇总更难读。
     *
     * @param rows      选中的行
     * @param requestFn (row) => Promise
     * @param verb      成功文案里的动词，如"禁用"
     */
    async applyBatch(rows, requestFn, verb) {
      if (rows.length === 0) return
      this.batchWorking = true
      try {
        const results = await Promise.allSettled(rows.map(requestFn))
        let ok = 0
        const failures = []
        results.forEach((r, i) => {
          if (r.status === 'fulfilled') {
            ok += 1
          } else {
            failures.push({ name: rows[i].username, message: (r.reason && r.reason.message) || '未知原因' })
          }
        })
        if (failures.length === 0) {
          ElMessage.success(`已${verb} ${ok} 个账号`)
        } else {
          const detail = failures.slice(0, 3).map((f) => `「${f.name}」${f.message}`).join('；')
          ElMessage.warning(`${ok} 个已${verb}，${failures.length} 个失败：${detail}${failures.length > 3 ? ' 等' : ''}`)
        }
        this.clearSelection()
        this.loadUsers()
      } finally {
        this.batchWorking = false
      }
    },

    handleBatchDisable() {
      const rows = [...this.selectedRows]
      ElMessageBox.confirm(
        `确定禁用选中的 ${rows.length} 个账号吗？禁用后他们会「立即被踢下线」，` +
        '在解禁之前无法登录（问卷、答卷与个人资料都保留，不会删除）。',
        '批量禁用',
        { confirmButtonText: '确定禁用', cancelButtonText: '取消', type: 'warning' }
      ).then(() => this.applyBatch(rows, (r) => api.user.setDisabled(r.id, 1, { silent: true }), '禁用'))
        .catch(() => {})
    },

    handleBatchEnable() {
      const rows = [...this.selectedRows]
      ElMessageBox.confirm(
        `确定解禁选中的 ${rows.length} 个账号吗？解禁后他们可以重新登录（此前签发的登录凭证仍然无效，需要重新登录）。`,
        '批量解禁',
        { confirmButtonText: '确定解禁', cancelButtonText: '取消', type: 'info' }
      ).then(() => this.applyBatch(rows, (r) => api.user.setDisabled(r.id, 0, { silent: true }), '解禁'))
        .catch(() => {})
    },

    /**
     * 批量**物理删除**（不可恢复）。
     * ★ 文案必须比单个删除更重：后端会级联删掉该用户名下的全部问卷、答卷与通知。
     * ★ 用 type: 'error' 的确认框，让"这一个按钮很危险"在视觉上就成立。
     */
    handleBatchDelete() {
      const rows = [...this.selectedRows]
      ElMessageBox.confirm(
        `⚠️ 确定永久删除选中的 ${rows.length} 个账号吗？此操作「不可恢复」 —— ` +
        '他们名下的全部问卷与答卷会被一起删除，用户名也不能再被注册使用。',
        '批量删除账号',
        { confirmButtonText: '确定永久删除', cancelButtonText: '取消', type: 'error' }
      ).then(() => this.applyBatch(rows, (r) => api.user.remove(r.id, { silent: true }), '删除'))
        .catch(() => {})
    },

    handleBatchRestore() {
      const rows = [...this.selectedRows]
      ElMessageBox.confirm(
        `确定恢复选中的 ${rows.length} 个账号吗？恢复后他们需要用原密码「重新登录」` +
        '（注销前签发的登录凭证已全部作废）；他们的问卷、答卷与个人资料都保持原样。',
        '批量恢复账号',
        { confirmButtonText: '确定恢复', cancelButtonText: '取消', type: 'info' }
      ).then(() => this.applyBatch(rows, (r) => api.user.restore(r.id, { silent: true }), '恢复'))
        .catch(() => {})
    },

    // ===== G3：导出（当前视图 + 当前筛选）=====
    async handleExport() {
      this.exporting = true
      try {
        // ★ deleted=true 时后端走「已注销」口径；disabled 只在"在用"视图有意义
        const params = {
          keyword: this.keyword.trim(),
          role: this.roleFilter,
          deleted: this.view === 'deleted' ? 'true' : 'false'
        }
        if (this.view === 'active' && this.statusFilter !== '') {
          params.disabled = this.statusFilter
        }
        // ★ 走 http.download()：解析后端 Content-Disposition 里的文件名并触发浏览器下载。
        //   失败（如超过 10000 行）由 http.js 统一弹红字并 throw，这里不用再提示。
        await api.user.exportExcel(params)
        ElMessage.success('导出已开始，请查看浏览器下载')
      } catch {
        // 失败提示已由 http.js 统一弹出
      } finally {
        this.exporting = false
      }
    },

    // ===== 单行操作 =====
    /**
     * 禁用 / 解禁（G3）。按钮文案由行的 disabled 决定，动作就是它取反。
     * ★ 后端会立刻作废该账号的 token（本人被踢下线），所以解禁后对方也要重新登录。
     */
    handleToggleDisabled(row) {
      const disabling = row.disabled !== 1
      if (disabling && row.id === this.currentUserId) {
        ElMessage.warning('不能禁用自己的账号')
        return
      }
      const verb = disabling ? '禁用' : '解禁'
      const tip = disabling
        ? '禁用后他会立即被踢下线、在解禁前无法登录；问卷、答卷与资料都保留。'
        : '解禁后他可以重新登录（仍需要重新登录一次，此前签发的凭证已作废）。'
      ElMessageBox.confirm(`确定${verb}账号「${row.username}」吗？${tip}`, `${verb}账号`, {
        confirmButtonText: `确定${verb}`,
        cancelButtonText: '取消',
        type: disabling ? 'warning' : 'info'
      }).then(async () => {
        try {
          await api.user.setDisabled(row.id, disabling ? 1 : 0)
          ElMessage.success(`已${verb}`)
          this.loadUsers()
        } catch {
          // 失败提示（如"不能禁用最后一个可用管理员"）由接口层统一弹出
        }
      }).catch(() => {})
    },

    handleDelete(row) {
      ElMessageBox.confirm(
        `确定「永久删除」用户「${row.username}」吗？该操作不可恢复！` +
        '他名下的全部问卷与答卷会被一起删除。',
        '警告',
        { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'error' }
      ).then(async () => {
        try {
          // 删除是 DELETE，非 2xx 抛错，await 通过即删除成功
          await api.user.remove(row.id)
          ElMessage.success('删除成功')
          this.loadUsers()
        } catch {
          // 失败提示由接口层统一弹出，这里不再重复提示
        }
      }).catch(() => {})
    },

    /**
     * 恢复一个已注销的账号。
     * ★ 提示里的措辞与后端行为一致：恢复时后端会把"令牌生效起点"推到此刻，
     *   **注销前签发的 access token 一律作废**，加上注销时已吊销的 refresh token 不会复活
     *   ⇒ **恢复后必须重新登录**。
     * ★ 也要说明**数据一直都在**（软删除只标记了 deleted_at，没删任何东西），
     *   否则管理员会以为"恢复"要重建账号。
     */
    handleRestore(row) {
      ElMessageBox.confirm(
        `确定恢复账号「${row.username}」吗？恢复后他需要用原密码「重新登录」` +
        '（注销前签发的登录凭证已全部作废）；他的问卷、答卷与个人资料都保持原样（软删除只标记、没删数据）。',
        '恢复账号',
        { confirmButtonText: '确定恢复', cancelButtonText: '取消', type: 'info' }
      ).then(async () => {
        try {
          await api.user.restore(row.id)
          ElMessage.success('账号已恢复')
          this.loadUsers()
        } catch {
          // 失败提示（如"该账号并未注销，无需恢复"）由接口层统一弹出
        }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.role-select,
.status-select {
  width: 130px;
  flex: 0 0 auto;
}

/* 批量操作条（G3）：选中才出现，粘在工具栏与表格之间 */
.batch-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 16px;
  border-radius: var(--r-md, 10px);
  border: 1px solid var(--sky-200, #bae0ff);
  background: var(--sky-50, #f0f9ff);
  animation: batch-bar-in 0.18s ease-out;
}

.batch-count {
  font-size: 14px;
  color: var(--text-regular, #606266);
}

.batch-count strong {
  color: var(--sky-600, #1677ff);
  font-size: 15px;
}

.batch-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-secondary, #909399);
}

@keyframes batch-bar-in {
  from { opacity: 0; transform: translateY(-4px); }
  to   { opacity: 1; transform: translateY(0); }
}
</style>
