/**
 * 举报接口（后端 SurveyReviewController）。
 * ★ 举报入口是 api/survey.js 的 report()；这里只放我的举报与管理端审核（列表 / 处理 / 取消下架）。
 */
import http from './http'

export default {
  /** 我的举报 */
  my: () => http.get('/api/review/my'),

  /** 管理端列表：status = PENDING / APPROVED / REJECTED，不传 = 全部 */
  list: (status) => http.get('/api/review/list' + (status ? `?status=${status}` : '')),

  /** 取消下架（管理员；只对 SUSPENDED 有效） */
  unsuspend: (surveyId) => http.post(`/api/survey/unsuspend/${surveyId}`),

  /** 处理：action = SUSPEND（通过并下架）| IGNORE（驳回） */
  handle: (id, action) => http.post(`/api/review/${id}/handle`, { action })
}
