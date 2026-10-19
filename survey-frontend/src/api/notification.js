/**
 * 站内通知接口（后端 NotificationController，基础路径 /api/notification）。
 * ★ 列表 / 未读数 / 单条已读 / 全部已读 / 删除 / 清空都只按当前登录用户；
 *   后端 SQL 带 user_id 条件，传别人的通知 id 返回 400，不会静默成功。
 */
import http from './http'

export default {
  /** 我的通知列表 */
  list: () => http.get('/api/notification/list'),

  /** 我的未读数量（顶栏铃铛红点，每 30 秒轮询）；silent：轮询失败不弹红字、不踢登录 */
  unread: () => http.get('/api/notification/unread', null, { silent: true }),

  /** 标记单条已读（PUT） */
  read: (id) => http.put(`/api/notification/read/${id}`),

  /** 全部标记已读（PUT） */
  readAll: () => http.put('/api/notification/read-all'),

  /** 删除单条（DELETE） */
  remove: (id) => http.del(`/api/notification/${id}`),

  /** 清空我的全部通知（DELETE） */
  clear: () => http.del('/api/notification/clear')
}
