/**
 * 答卷相关接口（后端 AnswerController，基础路径 /api/answer）。
 *
 * ★ 接口契约：submit-share 失败抛 BizException → 400 + message，前端只看状态码。
 */
import http from './http'

export default {
  /** 某问卷的答卷份数 */
  count: (surveyId) => http.get(`/api/answer/count/${surveyId}`),

  /** 单题的选项分布 [{content, count}] */
  statistics: (questionId) => http.get(`/api/answer/statistics/${questionId}`),

  /** 单题的填空题文本列表 */
  texts: (questionId) => http.get(`/api/answer/texts/${questionId}`),

  /** 某问卷的答卷列表 */
  list: (surveyId) => http.get(`/api/answer/list/${surveyId}`),

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
   */
  submitByShareCode: (shareCode, details) =>
    http.post(`/api/answer/submit-share/${shareCode}`, details)
}
