/**
 * 站内通知接口（后端 NotificationController，基础路径 /api/notification）。
 *
 * ★ 所有接口都只操作"当前登录用户自己的"通知 —— 后端在 SQL 层也带了 user_id 条件，
 *   所以传别人的通知 id 不会生效（会返回 400「标记失败或无权限」/「删除失败或无权限」，
 *   而不是静默成功）。
 * ★ 已读/删除统一走 HTTP 状态码，不比对任何中文文案。
 */
import http from './http'

export default {
  /** 我的通知列表 */
  list: () => http.get('/api/notification/list'),

  /** 我的未读数量（顶栏铃铛红点，前端每 30 秒轮询一次）。
   *  ★ silent：轮询失败不该弹红字、更不该把用户踢去登录页。 */
  unread: () => http.get('/api/notification/unread', null, { silent: true }),

  /** 标记单条已读（★ PUT） */
  read: (id) => http.put(`/api/notification/read/${id}`),

  /** 全部标记已读（★ PUT） */
  readAll: () => http.put('/api/notification/read-all'),

  /** 删除单条（★ DELETE） */
  remove: (id) => http.del(`/api/notification/${id}`),

  /** 清空我的全部通知（★ DELETE） */
  clear: () => http.del('/api/notification/clear')
}
