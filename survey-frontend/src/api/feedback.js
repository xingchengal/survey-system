/**
 * 反馈 / 客服接口（后端 FeedbackController）。
 * ★ 用户侧全部按当前登录用户与工单归属校验；管理侧只认 feedback:handle。
 * ★ 消息发送带 clientMsgId（UUID）幂等；轮询用 afterId 增量。
 */
import http from './http'

/** 生成发送幂等键（浏览器自带 crypto.randomUUID，失败时退回时间戳 + 随机数） */
export function newClientMsgId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export default {
  /** 提交反馈 / 发起客服工单：{ type, subject, content, clientMsgId } */
  create: (data) => http.post('/api/feedback', data),

  /** 我的工单列表 */
  my: () => http.get('/api/feedback/my'),

  /** 我的未读数（staff 带 adminUnread） */
  unread: () => http.get('/api/feedback/unread-count', null, { silent: true }),

  /** 工单详情（afterId 增量） */
  detail: (id, afterId) => http.get(`/api/feedback/${id}`, afterId ? { afterId } : null),

  /** 追加消息 */
  send: (id, content, clientMsgId) =>
    http.post(`/api/feedback/${id}/messages`, { content, clientMsgId }),

  /** 标记已读 */
  read: (id) => http.post(`/api/feedback/${id}/read`, null, { silent: true }),

  /** 关闭自己的工单 */
  close: (id) => http.post(`/api/feedback/${id}/close`),

  /** 管理端列表：{ type, status, pageNum, pageSize } */
  adminPage: (params) => http.get('/api/feedback/admin/page', params),

  /** 管理端详情（afterId 增量） */
  adminDetail: (id, afterId) =>
    http.get(`/api/feedback/admin/${id}`, afterId ? { afterId } : null),

  /** 管理员回复 */
  adminReply: (id, content, clientMsgId) =>
    http.post(`/api/feedback/admin/${id}/reply`, { content, clientMsgId }),

  /** 管理员关闭 */
  adminClose: (id) => http.post(`/api/feedback/admin/${id}/close`)
}
