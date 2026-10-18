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
  /**
   * 分页查询，params: { pageNum, pageSize, username, operation, role, startDate, endDate }
   * ★ role 是「级别筛选」的落地口径：只认 'admin' / 'user'，不传 = 全部。
   *   t_operation_log 没有"级别"列（那是异常日志的概念），这里的"级别"= **操作人角色**。
   * ★ startDate / endDate 是 `yyyy-MM-dd`（闭区间，后端把结束日补到 23:59:59）。
   */
  page: (params) => http.get('/api/log/page', params),

  /**
   * 导出操作日志为 Excel（管理员），params 与 page 的筛选条件相同（不含分页参数）。
   * ★ 后端上限 10000 行，超出返回 400 + 明确文案（http.js 会自动弹红字）。
   */
  exportExcel: (params) => http.download('/api/log/export', params, '操作日志.xlsx')
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
