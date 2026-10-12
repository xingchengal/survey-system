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

  /** 全部问卷（管理员），params: { pageNum, pageSize, keyword, status } */
  page: (params) => http.get('/api/survey/page', params),

  /**
   * 全部问卷 + 每份的答卷数（管理员，一次请求）。
   * ★ 用途：系统统计页的"答卷数排行"。用它一次拿完，**不要**改成对每份问卷
   *   串行 await api.answer.count(id)（前端 N+1，问卷越多越慢）——
   *   后端这里一次 GROUP BY 算完全部答卷数。
   */
  listWithResponseCount: () => http.get('/api/survey/list-with-response-count'),

  /** 新建问卷。★ 归属由后端按登录身份写死，请求体里的 userId 会被忽略 */
  add: (data) => http.post('/api/survey/add', data),

  /** 更新问卷（**标题 / 描述 / 起止时间**，共 4 个字段）。
   *  ★ status / shareCode / userId 后端一律**静默忽略** —— 要改状态必须走 publish()，否则页面不会有任何变化 */
  update: (data) => http.put('/api/survey/update', data),

  /** 按 id 查问卷详情 */
  detail: (id) => http.get(`/api/survey/${id}`),

  /**
   * 匿名访客按分享码查问卷（公开接口）。
   * ★ opts 可传 { silent: true }：匿名答题页打开失效链接时，
   *   页面本身会显示"问卷不存在或已下线"的空态，不需要再弹一条红字。
   *   （后端 JwtAuthFilter 已把 /api/survey/share/ 列入白名单，本就不会返回 401。）
   */
  byShareCode: (shareCode, opts) => http.get(`/api/survey/share/${shareCode}`, null, opts),

  /** 记一次访问量（公开接口，失败也不打扰用户） */
  view: (shareCode) => http.post(`/api/survey/view/${shareCode}`, null, { silent: true }),

  /** 发布问卷（后端会按需生成分享码）；失败 400（无权限/问卷不存在） */
  publish: (id) => http.post(`/api/survey/publish/${id}`),

  /** 复制问卷（含题目、选项与跳转规则）；失败 400 */
  copy: (id) => http.post(`/api/survey/copy/${id}`),

  // ===== 回收站（★ 动词容易记错，注释里都标了）=====

  /** 移入回收站（★ 是 PUT，不是 POST） */
  recycle: (id) => http.put(`/api/survey/recycle/${id}`),

  /** 从回收站恢复（★ 是 PUT） */
  restore: (id) => http.put(`/api/survey/restore/${id}`),

  /** 彻底删除（★ 是 DELETE；后端要求问卷**已在回收站里**，否则 400） */
  permanent: (id) => http.del(`/api/survey/permanent/${id}`),

  // ===== 其余 =====

  /** 我的回收站（分页 + 标题搜索） */
  recyclePage: (params) => http.get('/api/survey/recycle/page', params),

  /** 模板广场（支持 keyword 搜索） */
  templates: (params) => http.get('/api/survey/templates', params),

  /** 从模板创建问卷 */
  fromTemplate: (templateId) => http.post(`/api/survey/from-template/${templateId}`),

  /** 设为/取消模板（管理员） */
  setTemplate: (id, isTemplate, category) =>
    http.put(`/api/survey/set-template/${id}`, null, { params: { isTemplate, category } }),

  /** 系统总览（管理员）：含用户数、访客次数 */
  systemOverview: () => http.get('/api/survey/system-overview'),

  /** 我的个人统计 */
  myStatistics: () => http.get('/api/survey/my-statistics'),

  // ★ setTemplate() 是"设为/取消模板"的**唯一**写入端点，别删：
  //   入口在 AdminSurveyList.vue（"显示模板"开关 + 「设为模板/取消模板」按钮 + 分类弹窗）。
}
