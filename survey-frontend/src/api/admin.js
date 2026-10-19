/**
 * 次级管理员管理（后端 AdminManagementController，仅超级管理员）。
 * ★ 提升 / 改权限都传 permissions 数组；失败 400/403，前端只认 HTTP 状态码，不比对中文文案。
 */
import http from './http'

export default {
  /** 可分配的权限目录 [{ code, label }] */
  permissions: () => http.get('/api/admin/permissions'),

  /** 现有次级管理员 [{ userId, username, nickname, disabled, createdTime, permissions }] */
  subAdmins: () => http.get('/api/admin/sub-admins'),

  /** 提升为次级管理员：{ userId, permissions: [...] } */
  promote: (userId, permissions) => http.post('/api/admin/sub-admins', { userId, permissions }),

  /** 修改权限：PUT /api/admin/sub-admins/{userId}/permissions */
  updatePermissions: (userId, permissions) =>
    http.put(`/api/admin/sub-admins/${userId}/permissions`, { permissions }),

  /** 降级为普通用户（不删除账号） */
  demote: (userId) => http.del(`/api/admin/sub-admins/${userId}`)
}
