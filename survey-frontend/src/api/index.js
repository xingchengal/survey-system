/**
 * 接口层统一出口：页面里 `import api from '../api'`，按 `api.survey.my()` 调用。
 * ★ 新增页面用 `api.survey.xxx`，不要直接 import 具体模块，也不要自己 fetch / 拼 URL；
 *   存量还有页面直接 import wallet / review / collaborator（index.js 尚未 re-export 这三个）。
 * 命名约定：各模块的命名导出（demo / misc / operationLog / errorLog）提升到顶层，避免 api.demo.demo.init()。
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
  demo, // api.demo.init() / reset()
  misc, // api.misc.health() / seedTemplates()
  captcha, // api.captcha.slider()
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
