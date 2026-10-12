/**
 * 日志类接口：操作日志 + 异常日志（后端 OperationLogController / ErrorLogController）。
 *
 * ★ 两个模块放在一起是因为它们都在管理端「系统监控」页面里成对出现，
 *   而且都只有管理员能访问。
 * ★ 异常日志的删除/清空失败 → 400 + message；/error-log/count 非管理员同样返回 400
 *   （而不是 {count:0} —— 那会把"无权限"伪装成"没有异常"）。
 */
import http from './http'

/** 操作日志（基础路径 /api/log） */
export const operationLog = {
  /** 分页查询，params: { pageNum, pageSize, username, operation } */
  page: (params) => http.get('/api/log/page', params)
}

/** 异常日志（基础路径 /api/error-log） */
export const errorLog = {
  /** 分页查询，params: { pageNum, pageSize, keyword }（关键字匹配异常信息/路径/类型） */
  page: (params) => http.get('/api/error-log/page', params),

  /** 异常总数；silent 是因为它常作为附带统计，失败不该打断主流程 */
  count: () => http.get('/api/error-log/count', null, { silent: true }),

  /** 删除单条 */
  remove: (id) => http.del(`/api/error-log/${id}`),

  /** 清空全部 */
  clear: () => http.del('/api/error-log/clear')
}

export default { operationLog, errorLog }
