<template>
  <div>
    <div class="page-header">
      <h2 class="m-0">📜 系统监控</h2>
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

        <div style="display: flex; justify-content: flex-end; margin-bottom: 16px; gap: 10px;">
          <el-input v-model="opUsername" placeholder="按操作人筛选..." clearable :prefix-icon="User" style="width: 200px;" @keyup.enter="loadOperations" @clear="loadOperations" />
          <el-input v-model="opOperation" placeholder="按操作类型筛选..." clearable :prefix-icon="Search" style="width: 220px;" @keyup.enter="loadOperations" @clear="loadOperations" />
          <el-button type="primary" @click="loadOperations">搜索</el-button>
          <el-button :icon="Refresh" @click="loadOperations">刷新</el-button>
        </div>

        <el-table class="w-full" :data="operations" stripe border v-loading="opLoading">
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
            @size-change="loadOperations"
            @current-change="loadOperations"
          />
        </div>

        <el-empty v-if="operations.length === 0 && !opLoading" description="暂无日志" />
      </el-tab-pane>

      <!-- ==================== 异常日志 ==================== -->
      <el-tab-pane name="error">
        <template #label>
          <span class="flex-center">
            <el-icon class="mr-4"><WarningFilled /></el-icon>
            异常日志
          </span>
        </template>

        <div style="display: flex; justify-content: flex-end; margin-bottom: 16px; gap: 10px;">
          <el-input v-model="errorKeyword" placeholder="搜索异常信息、路径..." clearable :prefix-icon="Search" style="width: 300px;" @keyup.enter="loadErrors" @clear="loadErrors" />
          <el-button type="primary" @click="loadErrors">搜索</el-button>
          <el-button :icon="Refresh" @click="loadErrors">刷新</el-button>
          <el-button type="danger" :icon="Delete" @click="clearErrors" :disabled="errorTotal === 0">清空全部</el-button>
        </div>

        <el-table class="w-full" :data="errors" stripe border v-loading="errorLoading">
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
          <el-table-column label="操作" width="120" align="center" fixed="right">
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
            @current-change="loadErrors"
          />
        </div>

        <el-empty v-if="errors.length === 0 && !errorLoading" description="暂无异常" />
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
import { Refresh, Search, User, Delete } from '@element-plus/icons-vue'
// ★ 统一接口层（src/api）：日志类接口按「操作日志 / 异常日志」两个子对象导出，
//   页面只关心「调哪个接口、拿到什么数据」
import api from '../api'

// 图标已在 main.js 里全局注册，这里不再重复注册
// （模板标签走全局注册；:prefix-icon="Search" 这种属性变量走本文件顶部的 import，两者都不受影响）
export default {
  data() {
    return {
      activeTab: 'operation',

      // 操作日志
      operations: [],
      opLoading: false,
      opUsername: '',
      opOperation: '',
      opPageNum: 1,
      opPageSize: 10,
      opTotal: 0,

      // 异常日志
      errors: [],
      errorLoading: false,
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
      Delete: markRaw(Delete)
    }
  },
  mounted() {
    this.loadOperations()
    this.loadErrorCount()
  },
  methods: {
    /**
     * ★ 改"每页条数"时要把**页码复位到 1**，否则会落到空页上：
     *   页码仍是旧值时，例如在第 5 页把每页改成 100 条，总页数变成 1 页，
     *   用户会看到"暂无异常"，以为数据没了。
     *   （对照写法：Recycle.vue / AdminSurveyList.vue / UserManage.vue 的 size-change 都复位了。）
     */
    handleErrorSizeChange() {
      this.errorPageNum = 1
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
      try {
        // 参数对象交给接口层，toQuery 会自动拼串并跳过 null/undefined/空串
        const data = await api.operationLog.page({
          pageNum: this.opPageNum,
          pageSize: this.opPageSize,
          username: this.opUsername.trim(),
          operation: this.opOperation.trim()
        })
        this.operations = data.list || []
        this.opTotal = data.total || 0
      } catch {
        // ★ 接口层已按 HTTP 状态码统一提示，这里只留 catch 做流程控制，loading 由 finally 复位
      } finally {
        this.opLoading = false
      }
    },

    // ==================== 异常日志 ====================
    async loadErrors() {
      this.errorLoading = true
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