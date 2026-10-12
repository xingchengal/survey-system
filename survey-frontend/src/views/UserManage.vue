<template>
  <div>
    <div class="page-header">
      <h2 class="m-0">👥 用户管理</h2>
      <div class="flex-gap-10">
        <!-- ★ 账号注销（软删除）：两个视图。
             "在用用户"里看不到已注销的人（后端按设计过滤了 deleted_at），
             所以恢复入口必须有一个能**看见**他们的列表，否则 restore 就是"有端点没入口"。 -->
        <el-radio-group v-model="view" @change="handleViewChange">
          <el-radio-button value="active">在用用户</el-radio-button>
          <el-radio-button value="deleted">已注销</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="keyword"
          placeholder="搜索用户名或昵称..."
          clearable
          :prefix-icon="Search"
          style="width: 260px;"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-button type="primary" @click="handleSearch">搜索</el-button>
        <el-button :icon="Refresh" @click="loadUsers">刷新</el-button>
      </div>
    </div>

    <el-table class="w-full" :data="users" stripe border v-loading="loading">
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
      <!-- 已注销视图下把"注册时间"换成"注销时间"：管理员要找的通常是刚注销的那个 -->
      <el-table-column v-if="view === 'deleted'" prop="deletedAt" label="注销时间" width="180" />
      <el-table-column v-else prop="createdTime" label="注册时间" width="180" />
      <el-table-column label="操作" width="150" align="center">
        <template #default="{ row }">
          <el-button v-if="view === 'deleted'" type="primary" link size="small" @click="handleRestore(row)">
            恢复账号
          </el-button>
          <el-button v-else type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
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

    <el-empty v-if="users.length === 0 && !loading" :description="view === 'deleted' ? '没有被注销的账号' : '暂无用户'" />
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// Refresh / Search 用于属性变量形式（:icon="Refresh"、:prefix-icon="Search"），必须 import
import { Refresh, Search } from '@element-plus/icons-vue'
// ★ 统一接口层（src/api）：用户分页 / 删除用户都走它，
//   分页参数对象直接传，query 拼接与错误提示都由接口层负责
import api from '../api'

// 图标已在 main.js 里全局注册，这里不再重复注册
// （模板标签走全局注册；:prefix-icon="Search" 这种属性变量走本文件顶部的 import，两者都不受影响）
export default {
  data() {
    return {
      users: [],
      loading: false,
      keyword: '',
      pageNum: 1,
      pageSize: 10,
      total: 0,
      Search: markRaw(Search),
      Refresh: markRaw(Refresh)
    }
  },
  mounted() {
    this.loadUsers()
  },
  methods: {
    async loadUsers() {
      this.loading = true
      try {
        // 参数对象直接给接口层（空关键字由 toQuery 自动跳过）
        const params = {
          pageNum: this.pageNum,
          pageSize: this.pageSize,
          keyword: this.keyword.trim()
        }
        // ★ 按视图选接口 —— 已注销的人走 /page-deleted
        //   （/page 按设计过滤了 deleted_at，用它永远看不到已注销账号，也就无从恢复）
        const data = this.view === 'deleted'
          ? await api.user.pageDeleted(params)
          : await api.user.page(params)
        this.users = data.list || []
        this.total = data.total || 0
      } catch {
        // ★ 接口层已统一提示，这里只保留流程控制，loading 由 finally 复位
      } finally {
        this.loading = false
      }
    },

    /**
     * 切换「在用用户 / 已注销」视图。
     * ★ 先清空列表再拉：否则切换瞬间会看到上一个视图的数据（像是新数据），
     *   而两边的"ID/角色"长得很像，很容易让人误判。
     */
    handleViewChange() {
      this.users = []
      this.total = 0
      this.pageNum = 1
      this.loadUsers()
    },

    handleSearch() {
      this.pageNum = 1
      this.loadUsers()
    },

    handleSizeChange(size) {
      this.pageSize = size
      this.pageNum = 1
      this.loadUsers()
    },

    handlePageChange(page) {
      this.pageNum = page
      this.loadUsers()
    },

    handleDelete(row) {
      ElMessageBox.confirm(`确定删除用户「${row.username}」吗？该操作不可恢复！`, '警告', {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(async () => {
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
        `确定恢复账号「${row.username}」吗？恢复后他需要用原密码**重新登录**` +
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