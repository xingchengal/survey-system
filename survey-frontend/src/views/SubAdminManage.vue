<template>
  <div class="subadmin-page">
    <div class="page-head">
      <div>
        <h2 class="page-title">次级管理员</h2>
        <p class="page-subtitle">
          由超级管理员选定并逐项授权。次级管理员只能看到被分配的模块；超级管理员拥有全部权限。<br />
          权限或角色变更后，对方的登录状态会立即失效，需要重新登录才能拿到新菜单。
        </p>
      </div>
      <el-button type="primary" @click="openAdd">添加次级管理员</el-button>
    </div>

    <el-card>
      <el-table v-loading="loading" :data="rows" empty-text="还没有次级管理员">
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="30">{{ (row.nickname || row.username || '?').charAt(0) }}</el-avatar>
              <div>
                <div class="user-name">{{ row.nickname || row.username }}</div>
                <div class="user-account">@{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="权限" min-width="360">
          <template #default="{ row }">
            <el-tag
              v-for="code in row.permissions"
              :key="code"
              size="small"
              effect="light"
              class="perm-tag"
            >
              {{ labelOf(code) }}
            </el-tag>
            <span v-if="!row.permissions || row.permissions.length === 0" class="muted">未授权</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.disabled === 1 ? 'danger' : 'success'" size="small">
              {{ row.disabled === 1 ? '已禁用' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdTime" label="注册时间" width="170" />
        <el-table-column label="操作" width="190" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openEdit(row)">修改权限</el-button>
            <el-button type="danger" link size="small" @click="demote(row)">降为普通用户</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 添加 / 修改权限 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '修改权限' : '添加次级管理员'" width="560px">
      <el-form label-width="90px">
        <el-form-item v-if="!editing" label="选择用户">
          <el-select
            v-model="form.userId"
            filterable
            remote
            clearable
            reserve-keyword
            :remote-method="searchUsers"
            :loading="userLoading"
            placeholder="输入账号或昵称搜索普通用户"
            class="w-full"
          >
            <el-option
              v-for="u in userOptions"
              :key="u.id"
              :label="`${u.nickname || u.username}（@${u.username}）`"
              :value="u.id"
            />
          </el-select>
          <div class="hint">只列出「在用」的普通用户；已是管理员/次级管理员的账号不会出现。</div>
        </el-form-item>
        <el-form-item v-else label="用户">
          <span class="text-bold">{{ form.nickname || form.username }}</span>
          <span class="muted"> @{{ form.username }}</span>
        </el-form-item>

        <el-form-item label="分配权限">
          <el-checkbox-group v-model="form.permissions" class="perm-group">
            <el-checkbox v-for="p in permissionOptions" :key="p.code" :value="p.code">
              {{ p.label }}
            </el-checkbox>
          </el-checkbox-group>
          <div class="hint">至少选一项；对象级权限（改/删自己的问卷）由普通用户角色自带，不在这里分配。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

/**
 * 次级管理员：搜索普通用户 → 勾选可分配权限 → 提升 / 改权限 / 降级（仅超管）。
 * ★ 超管不可被操作、权限只认 ADMIN_GRANTABLE、角色与权限同事务 —— 安全边界在后端 AdminManagementService。
 */
export default {
  name: 'SubAdminManage',
  data() {
    return {
      loading: false,
      rows: [],
      permissionOptions: [],
      dialogVisible: false,
      editing: false,
      saving: false,
      userLoading: false,
      userOptions: [],
      form: { userId: null, username: '', nickname: '', permissions: [] }
    }
  },
  mounted() {
    this.load()
    this.loadPermissions()
  },
  methods: {
    async load() {
      this.loading = true
      try {
        this.rows = await api.admin.subAdmins()
      } catch {
        this.rows = []
      } finally {
        this.loading = false
      }
    },
    async loadPermissions() {
      try {
        this.permissionOptions = await api.admin.permissions()
      } catch {
        this.permissionOptions = []
      }
    },
    labelOf(code) {
      const item = this.permissionOptions.find(p => p.code === code)
      return item ? item.label : code
    },
    async searchUsers(keyword) {
      this.userLoading = true
      try {
        const data = await api.user.page({
          pageNum: 1,
          pageSize: 20,
          keyword: (keyword || '').trim(),
          disabled: 0
        })
        // 只留 role=user：超管/次级管理员不能被重复提升
        this.userOptions = (data.list || []).filter(u => u.role === 'user')
      } catch {
        this.userOptions = []
      } finally {
        this.userLoading = false
      }
    },
    openAdd() {
      this.editing = false
      this.form = { userId: null, username: '', nickname: '', permissions: [] }
      this.dialogVisible = true
      this.searchUsers('')
    },
    openEdit(row) {
      this.editing = true
      this.form = {
        userId: row.userId,
        username: row.username,
        nickname: row.nickname,
        permissions: [...(row.permissions || [])]
      }
      this.dialogVisible = true
    },
    async submit() {
      if (!this.editing && !this.form.userId) {
        ElMessage.warning('请选择要提升的用户')
        return
      }
      if (!this.form.permissions.length) {
        // 至少一项权限：后端也会拒绝空列表，前端先拦给出明确提示
        ElMessage.warning('请至少选择一项权限')
        return
      }
      this.saving = true
      try {
        const rows = this.editing
          ? await api.admin.updatePermissions(this.form.userId, this.form.permissions)
          : await api.admin.promote(this.form.userId, this.form.permissions)
        if (Array.isArray(rows)) this.rows = rows
        else await this.load()
        ElMessage.success(this.editing ? '权限已更新，对方需重新登录生效' : '已设为次级管理员，对方需重新登录生效')
        this.dialogVisible = false
      } catch {
        // 失败提示由 http.js 统一弹出
      } finally {
        this.saving = false
      }
    },
    async demote(row) {
      try {
        await ElMessageBox.confirm(
          `把「${row.nickname || row.username}」降为普通用户？账号与数据都保留，只收回管理权限。`,
          '降级确认',
          { confirmButtonText: '降为普通用户', cancelButtonText: '取消', type: 'warning' }
        )
      } catch {
        return
      }
      try {
        const rows = await api.admin.demote(row.userId)
        if (Array.isArray(rows)) this.rows = rows
        else await this.load()
        ElMessage.success('已降为普通用户，对方的登录状态已失效')
      } catch {
        // 提示由接口层统一弹出
      }
    }
  }
}
</script>

<style scoped>
.subadmin-page { max-width: 1200px; }
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.page-title { margin: 0 0 6px; font-size: 22px; color: var(--ink); }
.page-subtitle { margin: 0; font-size: 13px; color: var(--ink-3); }
.user-cell { display: flex; align-items: center; gap: 10px; }
.user-name { font-size: 13px; color: var(--ink); font-weight: 600; }
.user-account { font-size: 11px; color: var(--ink-3); }
.perm-tag { margin: 2px 6px 2px 0; }
.perm-group { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
.hint { width: 100%; font-size: 12px; color: var(--ink-3); line-height: 1.5; margin-top: 4px; }
.muted { color: var(--ink-3); font-size: 12px; }
</style>
