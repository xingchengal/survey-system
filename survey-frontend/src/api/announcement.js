/**
 * 系统公告接口（后端 AnnouncementController，基础路径 /api/announcement）。
 * ★ active 是公开接口（登录页也用，silent）；管理端 list / add / status / delete 均需管理员。
 * add / status / delete 失败一律 400 + {success:false,message}，本文件不做字符串判断。
 */
import http from './http'

export default {
  /** 有效公告（已上架且未过期）；silent：登录页失败不弹红字 */
  active: () => http.get('/api/announcement/active', null, { silent: true }),

  /** 全部公告（管理员，含已下架/已过期） */
  list: () => http.get('/api/announcement/list'),

  /** 发布公告（管理员） */
  add: (data) => http.post('/api/announcement/add', data),

  /** 上下架（管理员；PUT，status 走 query） */
  updateStatus: (id, status) =>
    http.put(`/api/announcement/status/${id}`, null, { params: { status } }),

  /** 删除公告（管理员） */
  remove: (id) => http.del(`/api/announcement/delete/${id}`)
}
