/**
 * 问卷相关接口（后端 SurveyController，基础路径 /api/survey）。
 *
 * ★ 页面里不要再写裸 fetch，也不要用 url 字符串 —— 一律从这里调，
 *   接口路径或方法变了只改这一个文件。
 *
 * ★ 接口契约：这些接口**失败**一律 **400 + {success:false,message}**，
 *   所以本文件里**没有任何字符串判断**，前端只依赖 HTTP 状态码。
 *   ⚠️ 注意"失败一律 400"说的是**失败时的状态码**，**成功响应体并不统一** ——
 *   有的是裸字符串（"删除成功"），有的是实体 / 列表 / PageResult，
 *   必须按各接口的实际返回类型处理，别假设它们长一个样。
 */
import http from './http'

export default {
  /** 我的问卷列表 */
  my: () => http.get('/api/survey/my'),

  /** 全部问卷（管理员），params: { pageNum, pageSize, keyword, status, startDate, endDate } */
  page: (params) => http.get('/api/survey/page', params),

  /**
   * 导出问卷列表为 Excel（管理员），params: { keyword, status, startDate, endDate }
   * ★ 筛选条件与 page 完全一致，页面把当前筛选原样传过来即可（"导出的就是屏幕上筛的"）。
   * ★ 口径是「全部问卷」：**不含模板**（与 page 的 SQL 一致；模板有单独的管理页）。
   * ★ 后端上限 10000 份，超出返回 400 + 明确文案（http.js 会自动弹红字）。
   */
  exportExcel: (params) => http.download('/api/survey/export', params, '问卷列表.xlsx'),

  /**
   * 全部问卷 + 每份的答卷数（管理员，一次请求）。
   * ★ 用途：系统统计页的"答卷数排行"。用它一次拿完，**不要**改成对每份问卷
   *   串行 await api.answer.count(id)（前端 N+1，问卷越多越慢）——
   *   后端这里一次 GROUP BY 算完全部答卷数。
   */
  listWithResponseCount: () => http.get('/api/survey/list-with-response-count'),

  /** 新建问卷。★ 归属由后端按登录身份写死，请求体里的 userId 会被忽略 */
  add: (data) => http.post('/api/survey/add', data),

  // ===== 版本快照（编辑历史 / 对比 / 回滚）=====

  /**
   * 某问卷的版本列表（新的在前）。
   * ★ 后端**不返回**题目快照 JSON（极端问卷下是 MB 级）—— 列表只给摘要。
   */
  versions: (surveyId) => http.get(`/api/survey/version/list/${surveyId}`),

  /** 对比两个版本（结构化差异：新增 / 删除 / 修改） */
  versionDiff: (fromId, toId) => http.get(`/api/survey/version/diff/${fromId}/${toId}`),

  /**
   * 回滚到某个版本。
   * ★ 回滚**不是撤销**：它会生成一个**新版本**（内容等于目标版本），历史仍然是线性的 ——
   *   所以"回滚错了"还能再回滚回去。
   */
  rollbackVersion: (versionId) => http.post(`/api/survey/version/rollback/${versionId}`),

  /** 更新问卷（**标题 / 描述 / 起止时间**，共 4 个字段）。
   *  ★ status / shareCode / userId 后端一律**静默忽略** —— 要改状态必须走 publish()，否则页面不会有任何变化 */
  update: (data) => http.put('/api/survey/update', data),

  /** 举报问卷（需登录；理由 1~500 字） */
  report: (surveyId, reason) => http.post(`/api/survey/${surveyId}/report`, { reason }),

  /**
   * 作者申请恢复上架（需登录；只有**自己名下、且 lifecycle=SUSPENDED** 的问卷能申请）。
   * ★ 它只是"通知全部管理员"，不会改 lifecycle —— 真正恢复是管理员的
   *   reviewApi.unsuspend(surveyId)（/api/survey/unsuspend/{id}）。
   */
  requestRestore: (id) => http.post(`/api/survey/${id}/request-restore`),

  /** 按 id 查问卷详情 */
  detail: (id) => http.get(`/api/survey/${id}`),

  /**
   * 匿名访客按分享码查问卷（公开接口）。
   * ★ opts 可传 { silent: true }：匿名答题页打开失效链接时，
   *   页面本身会显示"问卷不存在或已下线"的空态，不需要再弹一条红字。
   *   （后端 JwtAuthFilter 已把 /api/survey/share/ 列入白名单，本就不会返回 401。）
   */
  byShareCode: (shareCode, opts) => http.get(`/api/survey/share/${shareCode}`, null, opts),

  /**
   * 公开结果页（匿名）：只有作者在设置里开启「公开结果」才可用。
   * ★ 返回的是**脱敏统计**（不含填空题原文），未开启/不存在时 400。
   * ★ 传 silent：结果页自己会展示"未开启 / 链接失效"的空态，不需要再弹一条红字。
   */
  result: (shareCode) => http.get(`/api/survey/result/${shareCode}`, null, { silent: true }),

  /** 记一次访问量（公开接口，失败也不打扰用户） */
  view: (shareCode) => http.post(`/api/survey/view/${shareCode}`, null, { silent: true }),

  /** 发布问卷（后端会按需生成分享码）；失败 400（无权限/问卷不存在） */
  publish: (id) => http.post(`/api/survey/publish/${id}`),

  /** 复制问卷（含题目、选项与跳转规则）；失败 400 */
  copy: (id) => http.post(`/api/survey/copy/${id}`),

  // ===== 回收站（★ 动词容易记错，注释里都标了）=====

  /**
   * 移入回收站（★ 是 PUT，不是 POST）。
   * ★ opts 可传 `{ silent: true }`：**批量**移入时页面用 allSettled 逐条调用，
   *   传 silent 可以避免"每失败一条弹一次红字"，由页面统一汇总提示。
   */
  recycle: (id, opts) => http.put(`/api/survey/recycle/${id}`, null, opts),

  /** 从回收站恢复（★ 是 PUT）。opts 同 recycle（批量时传 silent） */
  restore: (id, opts) => http.put(`/api/survey/restore/${id}`, null, opts),

  /** 彻底删除（★ 是 DELETE；后端要求问卷**已在回收站里**，否则 400） */
  permanent: (id) => http.del(`/api/survey/permanent/${id}`),

  // ===== 其余 =====

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
   * ★ 分类可以独立于模板存在（模板管理的「+ 新增分类」），所以不能从模板列表反推。
   * ★ opts 可传 { silent: true }：页面初始化时拉清单属于"锦上添花"，
   *   后端没重启/接口暂时不可用不该在页面上弹红字（页面会退回从模板推导分类）。
   */
  templateCategories: (opts) => http.get('/api/survey/template-categories', null, opts),

  /**
   * 新增模板分类（管理员）。
   * ★ 后端返回**新增后的完整清单**，调用方可直接覆盖本地下拉，不必再 GET 一次。
   */
  addTemplateCategory: (name) =>
    http.post('/api/survey/template-categories', null, { params: { name } }),

  /** 系统总览（管理员）：含用户数、访客次数 */
  systemOverview: () => http.get('/api/survey/system-overview'),

  /** 我的个人统计 */
  myStatistics: () => http.get('/api/survey/my-statistics'),

  // ★ setTemplate() 是"设为/取消模板"的**唯一**写入端点，别删：
  //   入口在 AdminSurveyList.vue（"显示模板"开关 + 「设为模板/取消模板」按钮 + 分类弹窗）。
}
