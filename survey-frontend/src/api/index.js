/**
 * 接口层统一出口 —— 页面里这样用：
 *
 *   import api from '../api'
 *   const list = await api.survey.my()
 *   await api.notification.read(id)
 *   await api.errorLog.page({ pageNum: 1, pageSize: 10 })
 *
 * ★ 为什么要有这一层：路径、HTTP 方法、错误处理全部集中到 api/ 目录 ——
 *   页面里不要自己 fetch、不要自己拼 URL，改接口只动这里。
 *
 * ★ 命名约定：为了避免出现 `api.demo.demo.init()` 这种套娃写法，
 *   这里把各模块内部的命名导出**提升到顶层**（demo / misc / operationLog / errorLog），
 *   所以统一用扁平形式调用。
 */
import http, { ApiError, request, toQuery, notifyError, download } from './http'
import survey from './survey'
import user from './user'
import answer from './answer'
import question from './question'
import ai from './ai'
import notification from './notification'
import announcement from './announcement'
import { operationLog, errorLog } from './log'
import { demo, misc } from './demo'
import captcha from './captcha'
import admin from './admin'

const api = {
  http,
  survey,
  user,
  answer,
  question,
  ai,
  notification,
  announcement,
  operationLog, // api.operationLog.page(...)
  errorLog, // api.errorLog.page(...)
  demo, // api.demo.init() / api.demo.reset()
  misc, // api.misc.health() / api.misc.seedTemplates()
  captcha, // api.captcha.slider()（注册页滑块验证码）
  admin // api.admin.subAdmins() / promote() / updatePermissions() / demote()
}

export { http, ApiError, request, toQuery, notifyError, download }
export {
  survey,
  user,
  answer,
  question,
  ai,
  notification,
  announcement,
  operationLog,
  errorLog,
  demo,
  misc,
  captcha,
  admin
}
export default api
