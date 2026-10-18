/**
 * 举报相关接口（后端 SurveyReviewController）。
 * ★ 提交举报在 api/survey.js 的 report()（它属于"某份问卷"的操作）；
 *   这里只放"我的举报"与"管理端审核"。
 */
import http from './http'

export default {
  /** 我的举报 */
  my: () => http.get('/api/review/my'),

  /** 管理端列表：status = PENDING / APPROVED / REJECTED / null（全部） */
  list: (status) => http.get('/api/review/list' + (status ? `?status=${status}` : '')),

  /** 取消下架（管理员；只对 SUSPENDED 有效） */
  unsuspend: (surveyId) => http.post(`/api/survey/unsuspend/${surveyId}`),

  /** 处理：action = SUSPEND（通过并下架）| IGNORE（驳回） */
  handle: (id, action) => http.post(`/api/review/${id}/handle`, { action })
}
