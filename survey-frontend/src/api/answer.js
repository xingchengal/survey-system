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
   * ★ 它是**预留扩展点、不是死代码** —— 别按"前端零调用"把它删掉：
   *   · 它是**唯一**能把答题人身份写进 t_answer.user_id 的路径（submit-share 传的 userId 恒为 null）；
   *   · 「答卷防重 / 我的答题历史 / 实名问卷」都要靠它，删掉等于把这些功能的实现路径一起堵死。
   *   ★ 当前页面里**仍然没有调用点**（router 只有匿名答题页）；
   *     真要做登录态答题页时，直接 api.answer.submit(surveyId, details) 即可。
   */
  submit: (surveyId, details) => http.post(`/api/answer/submit/${surveyId}`, details),

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
