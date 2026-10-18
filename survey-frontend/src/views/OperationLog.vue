<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="page-title">系统监控</h2>
        <p class="page-subtitle">操作日志与异常日志 · 保留 90 天</p>
      </div>
    </div>

    <el-tabs v-model="activeTab" @tab-change="handleTabChange">
      <!-- ==================== 操作日志 ==================== -->
      <el-tab-pane name="operation">
        <template #label>
          <span class="flex-center">
            <el-icon class="mr-4"><Memo /></el-icon>
            操作日志
          </span>
        </template>

        <div class="toolbar-card">
          <el-input v-model="opUsername" placeholder="按操作人筛选..." clearable :prefix-icon="User" @keyup.enter="loadOperations" @clear="loadOperations" />
          <el-input v-model="opOperation" placeholder="按操作类型筛选..." clearable :prefix-icon="Search" @keyup.enter="loadOperations" @clear="loadOperations" />
          <!-- 级别筛选（G3）：t_operation_log **没有"级别"列**，这里的"级别"= 操作人角色。
               管理员操作与普通用户操作混在一张表里，筛出"管理员做了什么"才是审计的真需求。
               ★ 不新增 level 列：那要改表 + 回填历史 + 改 LogAspect 的写入契约；
                 "高危操作"（删除类）用左边的"操作类型"搜"删除"就能筛出来。 -->
          <el-select v-model="opRole" placeholder="全部级别" clearable class="log-role-select" @change="handleOpSearch">
            <el-option label="全部级别" value="" />
            <el-option label="管理员操作" value="admin" />
            <el-option label="普通用户操作" value="user" />
          </el-select>
          <!-- 时间范围（G3）：闭区间、只到"天"。后端把结束日补到 23:59:59，
               所以"选 10-01 到 10-01"能查到当天的全部日志。 -->
          <el-date-picker
            v-model="opDateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="操作起"
            end-placeholder="操作止"
            class="log-date-range"
            @change="handleOpSearch"
          />
          <el-button type="primary" @click="handleOpSearch">搜索</el-button>
          <el-button @click="handleOpReset">重置</el-button>
          <el-button :icon="Refresh" @click="loadOperations">刷新</el-button>
          <!-- 导出（G3）：导的是**当前筛选结果**（不是当前页），后端上限 10000 行 -->
          <el-button type="success" plain :icon="Download" :loading="opExporting" @click="handleOpExport">
            导出 Excel
          </el-button>
          <TableDensity />
        </div>

        <div class="table-card">
        <PageState
          :loading="opLoading"
          :error="opError"
          :empty="operations.length === 0"
          empty-text="暂无日志"
          @retry="loadOperations"
        >
        <el-table class="w-full" :data="operations" stripe border max-height="560">
          <el-table-column prop="id" label="ID" width="80" align="center" />
          <el-table-column prop="username" label="操作人" width="130" align="center">
            <template #default="{ row }">
              <el-tag :type="row.role === 'admin' ? 'danger' : 'info'" size="small">{{ row.username }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operation" label="操作类型" width="160" align="center">
            <template #default="{ row }">
              <el-tag type="success" size="small" effect="plain">{{ row.operation }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="method" label="调用方法" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span style="font-family: monospace; color: #666; font-size: 12px;">{{ row.method }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="ip" label="IP 地址" width="140" align="center">
            <template #default="{ row }">
              <span style="color: #909399; font-size: 12px;">{{ row.ip }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="操作时间" width="180" />
        </el-table>

        <div class="pager-row">
          <el-pagination
            v-model:current-page="opPageNum"
            v-model:page-size="opPageSize"
            :page-sizes="[10, 20, 50, 100]"
            :total="opTotal"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @size-change="handleOpSizeChange"
            @current-change="handleOpPageChange"
          />
        </div>
        </PageState>
        </div>

      </el-tab-pane>

      <!-- ==================== 异常日志 ==================== -->
      <el-tab-pane name="error">
        <template #label>
          <span class="flex-center">
            <el-icon class="mr-4"><WarningFilled /></el-icon>
            异常日志
          </span>
        </template>

        <div class="toolbar-card">
          <el-input v-model="errorKeyword" placeholder="搜索异常信息、路径..." clearable :prefix-icon="Search" style="width: 300px;" @keyup.enter="loadErrors" @clear="loadErrors" />
          <el-button type="primary" @click="loadErrors">搜索</el-button>
          <el-button :icon="Refresh" @click="loadErrors">刷新</el-button>
          <el-button type="danger" :icon="Delete" @click="clearErrors" :disabled="errorTotal === 0">清空全部</el-button>
          <TableDensity />
        </div>

        <div class="table-card">
        <PageState
          :loading="errorLoading"
          :error="errorError"
          :empty="errors.length === 0"
          empty-text="暂无异常"
          @retry="loadErrors"
        >
        <el-table class="w-full" :data="errors" stripe border max-height="560">
          <el-table-column prop="id" label="ID" width="70" align="center" />
          <el-table-column prop="errorType" label="异常类型" width="180" align="center">
            <template #default="{ row }">
              <el-tag type="danger" size="small">{{ row.errorType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="requestMethod" label="方式" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="getMethodColor(row.requestMethod)" size="small" effect="dark">
                {{ row.requestMethod }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="requestUri" label="请求路径" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span style="font-family: monospace; font-size: 12px; color: #606266;">{{ row.requestUri }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="errorMessage" label="异常信息" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span style="color: #f56c6c;">{{ row.errorMessage || '（无消息）' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="username" label="操作人" width="120" align="center">
            <template #default="{ row }">
              <span>{{ row.username || '匿名' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="发生时间" width="170" />
          <el-table-column label="操作" width="150" align="center" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="viewDetail(row)">详情</el-button>
              <el-button type="danger" link size="small" @click="deleteError(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager-row">
          <el-pagination
            v-model:current-page="errorPageNum"
            v-model:page-size="errorPageSize"
            :page-sizes="[10, 20, 50, 100]"
            :total="errorTotal"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @size-change="handleErrorSizeChange"
            @current-change="handleErrorPageChange"
          />
        </div>
        </PageState>
        </div>

      </el-tab-pane>
    </el-tabs>

    <!-- 异常详情对话框 -->
    <el-dialog v-model="detailVisible" title="异常详情" width="900px" top="5vh">
      <div v-if="currentError">
        <el-descriptions class="mb-16" :column="2" border size="small">
          <el-descriptions-item label="异常ID">#{{ currentError.id }}</el-descriptions-item>
          <el-descriptions-item label="异常类型">{{ currentError.errorType }}</el-descriptions-item>
          <el-descriptions-item label="请求方式">{{ currentError.requestMethod }}</el-descriptions-item>
          <el-descriptions-item label="操作人">{{ currentError.username || '匿名' }}</el-descriptions-item>
          <el-descriptions-item label="请求路径" :span="2">
            <code>{{ currentError.requestUri }}</code>
          </el-descriptions-item>
          <el-descriptions-item label="IP 地址">{{ currentError.ip }}</el-descriptions-item>
          <el-descriptions-item label="发生时间">{{ currentError.createTime }}</el-descriptions-item>
          <el-descriptions-item label="异常信息" :span="2">
            <span style="color: #f56c6c;">{{ currentError.errorMessage }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <div style="font-weight: bold; margin-bottom: 8px;">堆栈信息：</div>
        <pre style="background: #1e1e1e; color: #d4d4d4; padding: 16px; border-radius: 6px; max-height: 400px; overflow: auto; font-size: 12px; line-height: 1.5;">{{ currentError.stackTrace }}</pre>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// Search / User 用于 :prefix-icon，Refresh / Delete 用于 :icon，都是「属性变量」形式，必须 import；
// 注意区别：模板标签形式（<WarningFilled />）由 main.js 全局注册提供，不需要 import
import { Refresh, Search, User, Delete, Download } from '@element-plus/icons-vue'
// ★ 统一接口层（src/api）：日志类接口按「操作日志 / 异常日志」两个子对象导出，
//   页面只关心「调哪个接口、拿到什么数据」
import api from '../api'
// 三态容器：骨架 / 空 / 错误（带重试）
import PageState from '../components/PageState.vue'
// 行密度切换（默认 / 紧凑）
import TableDensity from '../components/TableDensity.vue'
// 页码记忆（sessionStorage）：两个 tab 各存各的
import { readPageNum, savePageNum } from '../utils/pageMemory'

/** 两个 tab 各自的页码键 */
const OP_PAGE_KEY = 'OperationLog:op'
const ERROR_PAGE_KEY = 'OperationLog:error'

// 图标已在 main.js 里全局注册，这里不再重复注册
// （模板标签走全局注册；:prefix-icon="Search" 这种属性变量走本文件顶部的 import，两者都不受影响）
export default {
  components: { PageState, TableDensity },
  data() {
    return {
      activeTab: 'operation',

      // 操作日志
      operations: [],
      opLoading: false,
      // 接口失败标记：由 PageState 渲染「加载失败 + 重试」
      opError: false,
      opUsername: '',
      opOperation: '',
      // 级别筛选（G3）：'' = 全部、'admin' / 'user' = 按操作人角色筛
      opRole: '',
      // 时间范围（G3）：['2026-10-01', '2026-10-02'] 或 null
      opDateRange: null,
      opExporting: false,
      opPageNum: 1,
      opPageSize: 10,
      opTotal: 0,

      // 异常日志
      errors: [],
      errorLoading: false,
      errorError: false,
      errorKeyword: '',
      errorPageNum: 1,
      errorPageSize: 10,
      errorTotal: 0,

      // 详情
      detailVisible: false,
      currentError: null,

      // 图标
      User: markRaw(User),
      Search: markRaw(Search),
      Refresh: markRaw(Refresh),
      Delete: markRaw(Delete),
      Download: markRaw(Download)
    }
  },
  mounted() {
    // 恢复两个 tab 各自停留的页码
    this.opPageNum = readPageNum(OP_PAGE_KEY)
    this.errorPageNum = readPageNum(ERROR_PAGE_KEY)
    this.loadOperations()
    this.loadErrorCount()
  },
  methods: {
    /** 操作日志：改条数 / 翻页都要记页码（改条数时页码复位，避免落到空页） */
    handleOpSizeChange() {
      this.opPageNum = 1
      savePageNum(OP_PAGE_KEY, 1)
      this.loadOperations()
    },

    handleOpPageChange(page) {
      this.opPageNum = page
      savePageNum(OP_PAGE_KEY, page)
      this.loadOperations()
    },

    handleErrorPageChange(page) {
      this.errorPageNum = page
      savePageNum(ERROR_PAGE_KEY, page)
      this.loadErrors()
    },

    /**
     * ★ 改"每页条数"时要把**页码复位到 1**，否则会落到空页上：
     *   页码仍是旧值时，例如在第 5 页把每页改成 100 条，总页数变成 1 页，
     *   用户会看到"暂无异常"，以为数据没了。
     *   （对照写法：Recycle.vue / AdminSurveyList.vue / UserManage.vue 的 size-change 都复位了。）
     */
    handleErrorSizeChange() {
      this.errorPageNum = 1
      savePageNum(ERROR_PAGE_KEY, 1)
      this.loadErrors()
    },

    handleTabChange(name) {
      if (name === 'error') {
        this.loadErrors()
      } else {
        this.loadOperations()
      }
    },

    // ==================== 操作日志 ====================
    async loadOperations() {
      this.opLoading = true
      this.opError = false
      try {
        // 参数对象交给接口层，toQuery 会自动拼串并跳过 null/undefined/空串
        // ★ 空的时间范围会变成 undefined（被 toQuery 跳过），所以不必自己拼条件
        const data = await api.operationLog.page({
          pageNum: this.opPageNum,
          pageSize: this.opPageSize,
          username: this.opUsername.trim(),
          operation: this.opOperation.trim(),
          role: this.opRole,
          startDate: this.opDateRange && this.opDateRange[0],
          endDate: this.opDateRange && this.opDateRange[1]
        })
        this.operations = data.list || []
        this.opTotal = data.total || 0
      } catch {
        // ★ 接口层已按 HTTP 状态码统一提示，这里只留 catch 做流程控制，loading 由 finally 复位
        this.opError = true
      } finally {
        this.opLoading = false
      }
    },

    /** 操作日志筛选后回第 1 页（否则在第 5 页改了条件会落到空页） */
    handleOpSearch() {
      this.opPageNum = 1
      savePageNum(OP_PAGE_KEY, 1)
      this.loadOperations()
    },

    /** 重置操作日志的全部筛选条件（G3）：操作人 / 操作类型 / 级别 / 时间范围 */
    handleOpReset() {
      this.opUsername = ''
      this.opOperation = ''
      this.opRole = ''
      this.opDateRange = null
      this.handleOpSearch()
    },

    /** 导出操作日志（G3）：导当前筛选结果，不是当前页 */
    async handleOpExport() {
      this.opExporting = true
      try {
        // ★ 走 http.download()：解析后端 Content-Disposition 里的文件名并触发浏览器下载。
        //   失败（如超过 10000 行）由 http.js 统一弹红字并 throw，这里不用再提示。
        await api.operationLog.exportExcel({
          username: this.opUsername.trim(),
          operation: this.opOperation.trim(),
          role: this.opRole,
          startDate: this.opDateRange && this.opDateRange[0],
          endDate: this.opDateRange && this.opDateRange[1]
        })
        ElMessage.success('导出已开始，请查看浏览器下载')
      } catch {
        // 失败提示已由 http.js 统一弹出
      } finally {
        this.opExporting = false
      }
    },

    // ==================== 异常日志 ====================
    async loadErrors() {
      this.errorLoading = true
      this.errorError = false
      try {
        // 同 loadOperations：只传参数对象，query 拼接交给接口层
        const data = await api.errorLog.page({
          pageNum: this.errorPageNum,
          pageSize: this.errorPageSize,
          keyword: this.errorKeyword.trim()
        })
        this.errors = data.list || []
        this.errorTotal = data.total || 0
      } catch {
        // ★ 不再自己弹「加载异常日志失败」（接口层已统一提示），只保留流程控制
        this.errorError = true
      } finally {
        this.errorLoading = false
      }
    },

    async loadErrorCount() {
      try {
        // api.errorLog.count() 内部已带 { silent: true }
        const data = await api.errorLog.count()
        this.errorTotal = data.count || 0
      } catch {
        // 接口异常（未登录 / 网络问题）时忽略即可，计数保持 0；
        // 这里显式保留 catch 块并写注释，是为了同时满足 ESLint 的 no-empty 规则
        // ★ count() 内部传了 silent，所以失败既不弹提示也不跳登录
      }
    },

    viewDetail(row) {
      this.currentError = row
      this.detailVisible = true
    },

    deleteError(row) {
      ElMessageBox.confirm(`确定删除异常 #${row.id} 吗？`, '提示', {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
        try {
          // 删除是 DELETE，失败即 throw，await 通过就是删成功
          await api.errorLog.remove(row.id)
          ElMessage.success('已删除')
          this.loadErrors()
          this.loadErrorCount()
        } catch {
          // 失败提示已由接口层统一弹出；这里挡住 rejection
        }
      }).catch(() => {})
    },

    clearErrors() {
      ElMessageBox.confirm('⚠️ 确定清空所有异常日志吗？此操作不可恢复！', '警告', {
        confirmButtonText: '确定清空',
        cancelButtonText: '取消',
        type: 'error'
      }).then(async () => {
        try {
          // 清空是 DELETE，只需 await 通过
          await api.errorLog.clear()
          ElMessage.success('已清空')
          this.loadErrors()
          this.loadErrorCount()
        } catch {
          // 失败提示由接口层统一弹出，这里只挡住 rejection
        }
      }).catch(() => {})
    },

    getMethodColor(method) {
      const map = { GET: 'success', POST: 'primary', PUT: 'warning', DELETE: 'danger' }
      return map[method] || 'info'
    }
  }
}
</script>

<style scoped>
/* 级别筛选与时间范围：固定宽度，避免把工具栏挤成两行 */
.log-role-select {
  width: 140px;
  flex: 0 0 auto;
}

.log-date-range {
  width: 250px;
  flex: 0 0 auto;
}

:deep(.el-tabs__item) {
  font-size: 14px;
  font-weight: 500;
}
:deep(.el-tabs__item.is-active) {
  font-weight: 600;
}
pre {
  margin: 0;
  font-family: Consolas, Monaco, monospace;
}
</style>