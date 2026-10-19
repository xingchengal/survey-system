/**
 * 日志接口：操作日志 + 异常日志（后端 OperationLogController / ErrorLogController）。
 * 两个模块只在管理端「系统监控」页成对出现，均需管理员。
 */
import http from './http'

/** 操作日志（基础路径 /api/log） */
export const operationLog = {
  /**
   * 分页查询，params: { pageNum, pageSize, username, operation, role, startDate, endDate }
   * role 只认 'admin' / 'user'（= 操作人角色，不传 = 全部）；startDate / endDate 为 yyyy-MM-dd 闭区间。
   */
  page: (params) => http.get('/api/log/page', params),

  /** 导出 Excel（管理员），筛选条件同 page；★ 后端上限 10000 行，超出 400 + 文案。 */
  exportExcel: (params) => http.download('/api/log/export', params, '操作日志.xlsx')
}

/** 异常日志（基础路径 /api/error-log） */
export const errorLog = {
  /** 分页查询，params: { pageNum, pageSize, keyword }（关键字匹配异常信息/路径/类型） */
  page: (params) => http.get('/api/error-log/page', params),

  /** 异常总数（silent：附带统计失败不打断主流程）；非管理员返回 400，不是 {count:0} */
  count: () => http.get('/api/error-log/count', null, { silent: true }),

  /** 删除单条 */
  remove: (id) => http.del(`/api/error-log/${id}`),

  /** 清空全部 */
  clear: () => http.del('/api/error-log/clear')
}

export default { operationLog, errorLog }
