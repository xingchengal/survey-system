/**
 * 题目相关接口（后端 QuestionController，基础路径 /api/question）。
 *
 * ★ 保存接口的后端契约：成功 200、失败 400 + {success:false,message}，
 *   所以这里没有任何字符串判断。
 */
import http from './http'

export default {
  /**
   * 保存问卷的全部题目（**全量覆盖**语义）。
   *
   * ★ 后端会重排 sortOrder、按 id 更新或新增、删掉本次没传的题，
   *   所以调用时必须传**完整的题目数组**，不能只传改动的那些。
   * ★ 失败（问卷不存在 / 无权限修改他人问卷 / 跳转目标非法）是 400 + message，由 http.js 弹红字。
   * ★ **返回值是"按下标一一对应的题目 id 列表"** [{ id, sortOrder }, ...]：
   *   编辑器要靠它把草稿阶段自己发的临时 id 换成真实 id（新建的题保存前没有数据库 id）。
   */
  save: (surveyId, questions) => http.post(`/api/question/save/${surveyId}`, questions),

  /**
   * 查某问卷的题目（含选项）。
   *
   * ★ 这个接口是「半公开」的：已发布的问卷任何人不登录都能读（匿名答题页依赖它），
   *   草稿则只有作者本人或管理员能读。
   * ★ opts 可传 { silent: true }：匿名答题页未登录时不该触发"登录过期跳转"。
   */
  list: (surveyId, opts) => http.get(`/api/question/list/${surveyId}`, null, opts)
}
