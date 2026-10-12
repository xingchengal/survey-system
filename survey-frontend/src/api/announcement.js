/**
 * 系统公告接口（后端 AnnouncementController，基础路径 /api/announcement）。
 *
 * ★ active 是公开接口（登录页也要显示公告），其余四个都需管理员
 *   （另：`api/captcha.js` 同样免登录）。
 * ★ 接口契约：add / status / delete 失败一律 **400 + {success:false,message}**，
 *   本文件没有任何字符串判断。
 */
import http from './http'

export default {
  /**
   * 有效公告（已上架且未过期）。
   * ★ silent：未登录时也会调它（登录页），失败不该弹红字打扰访客。
   */
  active: () => http.get('/api/announcement/active', null, { silent: true }),

  /** 全部公告（管理员，含已下架/已过期） */
  list: () => http.get('/api/announcement/list'),

  /** 发布公告（管理员） */
  add: (data) => http.post('/api/announcement/add', data),

  /** 上下架（管理员，★ PUT，status 走 query 参数） */
  updateStatus: (id, status) =>
    http.put(`/api/announcement/status/${id}`, null, { params: { status } }),

  /** 删除公告（管理员） */
  remove: (id) => http.del(`/api/announcement/delete/${id}`)
}
