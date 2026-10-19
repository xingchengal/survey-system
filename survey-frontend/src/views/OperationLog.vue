<template>
  <div>
    <div class="page-header">
      <div>
        <h2 class="page-title">系统监控</h2>
        <p class="page-subtitle">操作日志与异常日志 · 保留 90 天</p>
      </div>
    </div>

    <el-tabs v-model="activeTab" @tab-change="handleTabChange">
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
          <!-- ★ “级别”= 操作人角色：t_operation_log 没有 level 列，筛管理员操作才是审计真需求；
               不新增 level 列（要改表 + 回填历史 + 改 LogAspect 写入契约），删除类操作可用「操作类型」搜 -->
          <el-select v-model="opRole" placeholder="全部级别" clearable class="log-role-select" @change="handleOpSearch">
            <el-option label="全部级别" value="" />
            <el-option label="管理员操作" value="admin" />
            <el-option label="普通用户操作" value="user" />
          </el-select>
          <!-- 闭区间、只到天；后端把结束日补到 23:59:59，选同一天能查到当天全部日志 -->
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
          <!-- 导出当前筛选结果（不是当前页），后端上限 10000 行 -->
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
// Search / User 用于 :prefix-icon，Refresh / Delete / Download 用于 :icon，属性变量必须 import；
// 标签形式（<WarningFilled />）由 main.js 全局注册提供
import { Refresh, Search, User, Delete, Download } from '@element-plus/icons-vue'
// 日志接口统一走 src/api：operationLog / errorLog 两个子对象
import api from '../api'
import PageState from '../components/PageState.vue'
import TableDensity from '../components/TableDensity.vue'
// 页码记忆（sessionStorage）：两个 tab 各存各的
import { readPageNum, savePageNum } from '../utils/pageMemory'

/** 两个 tab 各自的页码键 */
const OP_PAGE_KEY = 'OperationLog:op'
const ERROR_PAGE_KEY = 'OperationLog:error'

export default {
  components: { PageState, TableDensity },
  data() {
    return {
      activeTab: 'operation',

      operations: [],
      opLoading: false,
      // 接口失败标记：由 PageState 渲染「加载失败 + 重试」
      opError: false,
      opUsername: '',
      opOperation: '',
      // 级别筛选：提交为 role；'' = 全部，'admin' / 'user' = 按操作人角色筛
      opRole: '',
      // 时间范围：['2026-10-01', '2026-10-02'] 或 null，提交为 startDate / endDate
      opDateRange: null,
      opExporting: false,
      // 并发保护：只认最后一次请求的响应（见 loadOperations）
      opSeq: 0,
      opPageNum: 1,
      opPageSize: 10,
      opTotal: 0,

      errors: [],
      errorLoading: false,
      errorError: false,
      errorKeyword: '',
      errorPageNum: 1,
      errorPageSize: 10,
      errorTotal: 0,

      detailVisible: false,
      currentError: null,

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

    /** 改每页条数时页码必须复位到 1：否则在第 5 页改成 100 条会落到空页，显示“暂无异常” */
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

    // ★ 并发保护：点「重置」会连发两次（时间控件的 @change + 自己调用），响应顺序不保证，
    //   只认最后一次请求，否则慢的那个后到会把新结果覆盖成筛选前的数据。
    async loadOperations() {
      const seq = ++this.opSeq
      this.opLoading = true
      this.opError = false
      try {
        // 参数对象交给接口层：toQuery 自动拼串并跳过 null/undefined/空串；空时间范围不必自己判断
        const data = await api.operationLog.page({
          pageNum: this.opPageNum,
          pageSize: this.opPageSize,
          username: this.opUsername.trim(),
          operation: this.opOperation.trim(),
          role: this.opRole,
          startDate: this.opDateRange && this.opDateRange[0],
          endDate: this.opDateRange && this.opDateRange[1]
        })
        if (seq !== this.opSeq) return
        this.operations = data.list || []
        this.opTotal = data.total || 0
      } catch {
        // 接口层已按 HTTP 状态码统一提示；这里只做流程控制，loading 由 finally 复位
        if (seq === this.opSeq) this.opError = true
      } finally {
        if (seq === this.opSeq) this.opLoading = false
      }
    },

    /** 操作日志筛选后回第 1 页（否则在第 5 页改了条件会落到空页） */
    handleOpSearch() {
      this.opPageNum = 1
      savePageNum(OP_PAGE_KEY, 1)
      this.loadOperations()
    },

    /** 重置操作日志的全部筛选条件：操作人 / 操作类型 / 级别 / 时间范围 */
    handleOpReset() {
      this.opUsername = ''
      this.opOperation = ''
      this.opRole = ''
      this.opDateRange = null
      this.handleOpSearch()
    },

    /** 导出操作日志：导当前筛选结果，不是当前页 */
    async handleOpExport() {
      this.opExporting = true
      try {
        // 走 http.download()：解析 Content-Disposition 文件名并触发下载；
        //   失败（如超过 10000 行）由 http.js 统一弹错
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
        // 接口层已统一提示；这里只置错误标记让 PageState 显示重试入口
        this.errorError = true
      } finally {
        this.errorLoading = false
      }
    },

    async loadErrorCount() {
      try {
        const data = await api.errorLog.count()
        this.errorTotal = data.count || 0
      } catch {
        // 计数失败保持 0；count() 内部 silent，不弹提示也不跳登录（保留注释同时避开 no-empty）
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