/**
 * 答卷相关接口（后端 AnswerController，基础路径 /api/answer）。
 * 失败一律 400 + {success:false,message}；提交类的**成功响应体是对象**（判分字段），不是字符串。
 */
import http from './http'

export default {
  /** 某问卷的答卷份数 */
  count: (surveyId) => http.get(`/api/answer/count/${surveyId}`),

  /** 单题的选项分布 [{content, count}]；excludeInvalid=true 时只统计有效答卷 */
  statistics: (questionId, excludeInvalid = false) =>
    http.get(`/api/answer/statistics/${questionId}${excludeInvalid ? '?excludeInvalid=true' : ''}`),

  /**
   * 答卷质控摘要：`{ total, suspicious, suspiciousRate, reasons:[{code,label,count}], samples }`
   * "可疑"是打标不是删除：可疑答卷仍计入回收数量，是否剔除由统计接口的 excludeInvalid 决定。
   */
  quality: (surveyId) => http.get(`/api/answer/quality/${surveyId}`),

  /** 单题的填空题文本列表 */
  texts: (questionId) => http.get(`/api/answer/texts/${questionId}`),

  /**
   * 整份问卷的统计（一次取回）。
   * 返回 `{ surveyId, title, answerCount, questions: [{ question, chartData, textAnswers }] }`。
   * 单题的 statistics / texts 保留：将来"只刷新某一题"还要用，别因为统计页不用就删。
   */
  surveyStatistics: (surveyId, excludeInvalid = false) =>
    http.get(`/api/answer/statistics/survey/${surveyId}${excludeInvalid ? '?excludeInvalid=true' : ''}`),

  /**
   * 某问卷的答卷列表（分页）。
   * 返回 `{ total, list, pageNum, pageSize }`（后端 PageResult），**不是数组**。
   * 后端把 pageNum 夹到 ≥1、pageSize 夹到 [1,100]，这里默认 1 / 10 与分页器一致。
   */
  list: (surveyId, pageNum = 1, pageSize = 10) =>
    http.get(`/api/answer/list/${surveyId}?pageNum=${pageNum}&pageSize=${pageSize}`),

  /** 某份答卷的明细 */
  detail: (answerId) => http.get(`/api/answer/detail/${answerId}`),

  /**
   * 我答过的问卷（分页）。
   * 只返回登录态提交的记录：匿名答卷没有 user_id，永远查不到。
   * 返回 `{ total, list, pageNum, pageSize }`；list 项含问卷标题、分数、状态与 shareCode。
   */
  my: (pageNum = 1, pageSize = 10) =>
    http.get(`/api/answer/my?pageNum=${pageNum}&pageSize=${pageSize}`),

  /**
   * 我某份答卷的详情（逐题回放）。
   * 权限按答卷归属校验：别人的 answerId 会 400（不是 403，避免探测存在性）。
   * correct 只有计分题才有值；correctAnswers 只在作者开启「提交后显示答案」时才有。
   */
  myDetail: (answerId) => http.get(`/api/answer/my/${answerId}`),

  /** 公开报名名单（接龙用，前 50 位）。作者没开启时后端 400，页面静默不显示即可 */
  participants: (shareCode) => http.get(`/api/answer/participants/${shareCode}`),

  /** 签到 / 取消签到（作者；问卷需在设置里启用签到核销） */
  checkin: (answerId, checkedIn = true) =>
    http.put(`/api/answer/checkin/${answerId}?checkedIn=${checkedIn}`),

  /**
   * 查我在某份问卷的成绩（登录用户；考试问卷才有）。
   * 按 surveyId + 当前登录用户定位，不接受 answerId —— 匿名答卷（user_id = NULL）查不到。
   * 返回 `{ surveyId, title, totalScore, earnedScore, passScore, passed, submittedAt }`；
   * 没交过 / 未开判分 → 400 + message。
   */
  score: (surveyId) => http.get(`/api/answer/score/${surveyId}`),

  /**
   * 导出某问卷的答卷为 Excel。
   * 文件名由 http.download 从 Content-Disposition 解析（优先 filename*），
   * 兜底名是第三个参数；页面只需 `await api.answer.exportExcel(id)`。
   */
  exportExcel: (surveyId) => http.download(`/api/answer/export/${surveyId}`, null, '答卷数据.xlsx'),

  /**
   * 登录用户提交答卷（POST /api/answer/submit/{surveyId}）。
   * 「仅限登录用户填写」的问卷必须走这里：submit-share 的 userId 恒为 null，会被 400 拒收。
   * 它是唯一能把答题人身份写进 t_answer.user_id 的路径，「答卷防重 / 我的答卷」都建立在它之上。
   * 返回 SubmitResult 对象：`{ message, graded, totalScore, earnedScore, passScore, passed, questions }`
   * —— graded=false 时判分字段全为 null；questions 仅在开启「提交后显示答案」时有值。
   * durationMs 只用于限时校验与质控提示，可伪造。
   */
  submit: (surveyId, details, durationMs) => {
    const query = durationMs == null ? '' : `?durationMs=${durationMs}`
    return http.post(`/api/answer/submit/${surveyId}${query}`, details)
  },

  /**
   * 匿名访客凭分享码提交答卷（公开接口）。
   * 故意不传 silent：访客提交失败（已截止 / 已下线 / 网络异常）必须看到原因。
   * 接口在白名单里不会 401，所以不传 silent 是安全的；失败原因由 400 + message 给出。
   * durationMs 只用于质控（"答题过快"判定），可伪造，不是安全边界。
   * opts 可传 { headers }：有答题密码时带 X-Survey-Password（密码只放请求头，不进 query）。
   */
  submitByShareCode: (shareCode, details, durationMs, opts) => {
    const query = durationMs == null ? '' : `?durationMs=${durationMs}`
    return http.post(`/api/answer/submit-share/${shareCode}${query}`, details, opts)
  }
}
