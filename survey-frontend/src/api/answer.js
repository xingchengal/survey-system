/**
 * 答卷相关接口（后端 AnswerController，基础路径 /api/answer）。
 *
 * ★ 接口契约：submit-share 失败抛 BizException → 400 + message，前端只看状态码。
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
   * ★ "可疑"是**打标**不是删除：可疑答卷仍计入回收数量，是否剔除由统计接口的 excludeInvalid 决定。
   */
  quality: (surveyId) => http.get(`/api/answer/quality/${surveyId}`),

  /** 单题的填空题文本列表 */
  texts: (questionId) => http.get(`/api/answer/texts/${questionId}`),

  /**
   * 整份问卷的统计（**一次取回**）。
   *
   * ★ 统计页专用：替代原来的"逐题请求"（N 道题 = N 次**串行**往返，见 SurveyStatistics.vue）。
   *   返回 `{ surveyId, title, answerCount, questions: [{ question, chartData, textAnswers }] }`。
   * ★ 上面的 `statistics` / `texts` 两个单题方法**保留**：它们是单题接口的契约，
   *   将来若出现"只刷新某一题"的场景还要用（别因为统计页不用了就把它们删掉）。
   */
  surveyStatistics: (surveyId, excludeInvalid = false) =>
    http.get(`/api/answer/statistics/survey/${surveyId}${excludeInvalid ? '?excludeInvalid=true' : ''}`),

  /**
   * 某问卷的答卷列表（**分页**）。
   *
   * ★ 返回 `{ total, list, pageNum, pageSize }`（后端 PageResult），**不是数组**。
   * ★ 为什么必须分页：原先一次返回整份问卷的全部答卷（两万份 = 两万个对象、
   *   实测 P50 239ms），点开"所有答卷"就能把服务端与浏览器一起拖慢。
   * ★ 后端把 pageNum 夹到 ≥1、pageSize 夹到 [1,100]，这里默认 1 / 10 与分页器一致。
   */
  list: (surveyId, pageNum = 1, pageSize = 10) =>
    http.get(`/api/answer/list/${surveyId}?pageNum=${pageNum}&pageSize=${pageSize}`),

  /** 某份答卷的明细 */
  detail: (answerId) => http.get(`/api/answer/detail/${answerId}`),

  /**
   * 我答过的问卷（分页）。
   *
   * ★ 只返回**登录态提交**的记录：匿名答卷没有 user_id，永远查不到。
   * ★ 返回 `{ total, list, pageNum, pageSize }`；list 项含问卷标题、分数、
   *   问卷状态与 shareCode（用来判断还能不能打开）。
   */
  my: (pageNum = 1, pageSize = 10) =>
    http.get(`/api/answer/my?pageNum=${pageNum}&pageSize=${pageSize}`),

  /**
   * 我某份答卷的详情（逐题回放）。
   *
   * ★ 权限按**答卷归属**校验：别人的 answerId 会 400（不是 403，避免探测存在性）。
   * ★ `correct` 只有计分题才有值；`correctAnswers` 只在作者开启「提交后显示答案」时才有。
   */
  myDetail: (answerId) => http.get(`/api/answer/my/${answerId}`),

  /**
   * 公开报名名单（接龙用，前 50 位）。
   * ★ 作者没开启「公开报名名单」时后端 400；页面静默不显示即可。
   */
  participants: (shareCode) => http.get(`/api/answer/participants/${shareCode}`),

  /** 签到 / 取消签到（作者；问卷需在设置里启用签到核销） */
  checkin: (answerId, checkedIn = true) =>
    http.put(`/api/answer/checkin/${answerId}?checkedIn=${checkedIn}`),

  /**
   * 查**我**在某份问卷的成绩（登录用户；考试问卷才有）。
   *
   * ★ 只按 `surveyId + 当前登录用户` 定位，不接受 answerId ——
   *   匿名答卷（user_id = NULL）查不到，匿名考生只在提交那一刻看到分数。
   * ★ 返回 `{ surveyId, title, totalScore, earnedScore, passScore, passed, submittedAt }`。
   * ★ 没交过 / 问卷未开判分 → 后端 400 + message（由 http.js 统一提示）。
   */
  score: (surveyId) => http.get(`/api/answer/score/${surveyId}`),

  /**
   * 导出某问卷的答卷为 Excel。
   *
   * ★ 走 http.download()：它会从响应头的 Content-Disposition 里解析**后端给的文件名**
   *   （后端用 RFC 5987 的 filename* 传中文名，如"问卷标题_答卷数据.xlsx"），
   *   然后直接触发浏览器下载 —— 页面里只需要 `await api.answer.exportExcel(id)` 一行。
   * ★ 第三个参数是"后端没给文件名"时的兜底名。
   */
  exportExcel: (surveyId) => http.download(`/api/answer/export/${surveyId}`, null, '答卷数据.xlsx'),

  /**
   * 登录用户提交答卷（POST /api/answer/submit/{surveyId}）。
   *
   * ★ 「仅限登录用户填写」的问卷**必须**走这里：后端只认带 token 的提交，
   *   submit-share 的 userId 恒为 null，会被 400 拒收（见 AnswerService.doSubmit）。
   * ★ 它同时是**唯一**能把答题人身份写进 t_answer.user_id 的路径；
   *   「答卷防重 / 我的答卷」都建立在它之上。
   * ★ durationMs 与匿名提交同口径：只用于限时校验与质控提示，可伪造。
   */
  submit: (surveyId, details, durationMs) => {
    const query = durationMs == null ? '' : `?durationMs=${durationMs}`
    return http.post(`/api/answer/submit/${surveyId}${query}`, details)
  },

  /**
   * 匿名访客凭分享码提交答卷（公开接口）。
   *
   * ★ 这里**故意不传 silent**：
   *   访客点"提交问卷"失败时（问卷已截止 / 已下线 / 网络异常），必须让他看到原因，
   *   否则点了没反应等于功能坏了。接口在白名单里，本来也不会返回 401 触发"登录过期跳转"，
   *   所以不传 silent 是安全的。
   * ★ 失败原因由后端 400 + message 给出，http.js 统一弹红字。
   * ★ durationMs：答题页从打开到提交的毫秒数，**只用于质控**（"答题过快"判定）。
   *   它是客户端值、可伪造 —— 后端也只当提示用，不当安全边界。
   * ★ opts 可传 { headers }：有答题密码的问卷要带 X-Survey-Password，
   *   由填写页从本地密码门拿到的值传进来（密码只放请求头，不进 query）。
   */
  submitByShareCode: (shareCode, details, durationMs, opts) => {
    const query = durationMs == null ? '' : `?durationMs=${durationMs}`
    return http.post(`/api/answer/submit-share/${shareCode}${query}`, details, opts)
  }
}
