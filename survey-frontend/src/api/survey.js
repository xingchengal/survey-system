/**
 * 问卷相关接口（后端 SurveyController，基础路径 /api/survey）。
 * 失败一律 400 + {success:false,message}，本文件不做字符串判断；
 * 但**成功响应体不统一**（裸字符串 / 实体 / 列表 / PageResult），须按各接口实际类型处理。
 * 导出走 http.download：文件名优先取响应头 filename*（RFC 5987），解析不到才用兜底名。
 */
import http from './http'

export default {
  /** 我的问卷列表 */
  my: () => http.get('/api/survey/my'),

  /** 全部问卷（管理员），params: { pageNum, pageSize, keyword, status, startDate, endDate } */
  page: (params) => http.get('/api/survey/page', params),

  /**
   * 导出问卷列表为 Excel（管理员），params: { keyword, status, startDate, endDate }
   * 筛选条件与 page 完全一致（导出的就是屏幕上筛的），口径不含模板（与 page 的 SQL 一致）。
   * 后端上限 10000 份，超出返回 400 + 明确文案。
   */
  exportExcel: (params) => http.download('/api/survey/export', params, '问卷列表.xlsx'),

  /**
   * 全部问卷 + 每份的答卷数（管理员，一次请求）。
   * 一次 GROUP BY 算完；不要改成对每份问卷串行调 answer.count(id)（N+1）。
   */
  listWithResponseCount: () => http.get('/api/survey/list-with-response-count'),

  /** 新建问卷。归属由后端按登录身份写死，请求体里的 userId 会被忽略 */
  add: (data) => http.post('/api/survey/add', data),

  /**
   * 某问卷的版本列表（新的在前）。
   * 后端不返回题目快照 JSON（极端问卷下是 MB 级），列表只给摘要。
   */
  versions: (surveyId) => http.get(`/api/survey/version/list/${surveyId}`),

  /** 对比两个版本（结构化差异：新增 / 删除 / 修改） */
  versionDiff: (fromId, toId) => http.get(`/api/survey/version/diff/${fromId}/${toId}`),

  /** 回滚到某个版本：会生成一个**新版本**（不是撤销），回滚错了还能再回滚回去 */
  rollbackVersion: (versionId) => http.post(`/api/survey/version/rollback/${versionId}`),

  /** 更新问卷（标题 / 描述 / 起止时间，共 4 个字段）。
   *  ★ status / shareCode / userId 后端静默忽略 —— 要改状态必须走 publish()，否则页面不会有任何变化 */
  update: (data) => http.put('/api/survey/update', data),

  /** 举报问卷（需登录；理由 1~500 字） */
  report: (surveyId, reason) => http.post(`/api/survey/${surveyId}/report`, { reason }),

  /**
   * 作者申请恢复上架（需登录；只有自己名下、且 lifecycle=SUSPENDED 的问卷能申请）。
   * 它只通知全部管理员，不改 lifecycle —— 真正恢复是 reviewApi.unsuspend(surveyId)。
   */
  requestRestore: (id) => http.post(`/api/survey/${id}/request-restore`),

  /** 按 id 查问卷详情 */
  detail: (id) => http.get(`/api/survey/${id}`),

  /**
   * 匿名访客按分享码查问卷（公开接口）。
   * opts 可传 { silent: true }：链接失效时页面自己显示空态，不再弹红字。
   */
  byShareCode: (shareCode, opts) => http.get(`/api/survey/share/${shareCode}`, null, opts),

  /**
   * 公开结果页（匿名）：仅作者开启「公开结果」时可用，返回脱敏统计（不含填空题原文）。
   * 未开启 / 不存在 → 400；silent：结果页自己展示空态。
   */
  result: (shareCode) => http.get(`/api/survey/result/${shareCode}`, null, { silent: true }),

  /** 记一次访问量（公开接口，失败也不打扰用户） */
  view: (shareCode) => http.post(`/api/survey/view/${shareCode}`, null, { silent: true }),

  /** 发布问卷（后端会按需生成分享码）；失败 400（无权限 / 问卷不存在） */
  publish: (id) => http.post(`/api/survey/publish/${id}`),

  /** 复制问卷（含题目、选项与跳转规则）；失败 400 */
  copy: (id) => http.post(`/api/survey/copy/${id}`),

  /**
   * 移入回收站（★ 是 PUT，不是 POST）。
   * 批量移入时页面用 allSettled 逐条调用并传 { silent: true }，失败由页面统一汇总。
   */
  recycle: (id, opts) => http.put(`/api/survey/recycle/${id}`, null, opts),

  /** 从回收站恢复（PUT）。opts 同 recycle（批量时传 silent） */
  restore: (id, opts) => http.put(`/api/survey/restore/${id}`, null, opts),

  /** 彻底删除（DELETE；后端要求问卷已在回收站里，否则 400） */
  permanent: (id) => http.del(`/api/survey/permanent/${id}`),

  /** 我的回收站（分页 + 标题搜索） */
  recyclePage: (params) => http.get('/api/survey/recycle/page', params),

  /** 模板广场（支持 keyword 搜索） */
  templates: (params) => http.get('/api/survey/templates', params),

  /** 从模板创建问卷 */
  fromTemplate: (templateId) => http.post(`/api/survey/from-template/${templateId}`),

  /** 设为/取消模板（管理员）；设为模板时必须带 category */
  setTemplate: (id, isTemplate, category) =>
    http.put(`/api/survey/set-template/${id}`, null, { params: { isTemplate, category } }),

  /**
   * 模板分类清单（管理员）。
   * 分类可以独立于模板存在（模板管理的「+ 新增分类」），不能从模板列表反推。
   * opts 可传 { silent: true }：初始化拉清单属于锦上添花，失败不该弹红字（页面会退回从模板推导）。
   */
  templateCategories: (opts) => http.get('/api/survey/template-categories', null, opts),

  /** 新增模板分类（管理员）。后端返回新增后的完整清单，可直接覆盖本地下拉，不必再 GET 一次 */
  addTemplateCategory: (name) =>
    http.post('/api/survey/template-categories', null, { params: { name } }),

  /** 系统总览（管理员）：含用户数、访客次数 */
  systemOverview: () => http.get('/api/survey/system-overview'),

  /** 我的个人统计 */
  myStatistics: () => http.get('/api/survey/my-statistics'),

  // ★ setTemplate() 是"设为/取消模板"的唯一写入端点，别删：
  //   入口在 AdminSurveyList.vue（"显示模板"开关 +「设为模板/取消模板」按钮 + 分类弹窗）。
}
