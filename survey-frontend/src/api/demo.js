/**
 * 演示数据与杂项接口。
 *
 * ★ demo 接口是答辩演示用的：/init 造一批用户和问卷，/reset 按用户名前缀精确回收。
 *   两个都需管理员，且都不是幂等的（每次 init 都会追加一批）。
 *
 * ★ 接口契约：/init、/reset、/template-seeder/init 非管理员一律 **400 + message**，
 *   前端不需要（也不应该）判断 success 字段。
 *
 * ★ 演示数据的量级（务必与看板数字口径一致）：
 *   一批 = 20 个用户 + 30 份问卷；每份已发布问卷 3~8 份答卷；
 *   访问量按「该问卷答卷数 + 0~19 次只浏览未提交的访问」回填，
 *   所以「访客次数」永远 ≥「总答卷」且量级自洽。
 */
import http from './http'

/** 演示数据（基础路径 /api/demo） */
export const demo = {
  /** 生成演示数据（管理员，★ 每点一次追加一批：+20 用户 / +30 问卷） */
  init: () => http.post('/api/demo/init'),

  /** 清理演示数据（管理员，★ DELETE；按 demo_user 前缀精确回收，不动真实数据） */
  reset: () => http.del('/api/demo/reset')
}

/** 健康检查与模板种子 */
export const misc = {
  /**
   * 后端健康检查。
   * ★ 用在 Dashboard 的「系统信息」卡片上（绿点/红点）；
   *   silent 是必须的 —— 后端没启动时正是靠它显示异常状态，不该弹一堆错误提示。
   */
  health: () => http.get('/api/health', null, { silent: true }),

  /** 初始化系统预置模板（管理员，幂等：库里已有**预置模板**时返回 count=0）
   *  ★ 判据是"已存在 user_id=0 的预置模板"（TemplateSeederService）——
   *    管理员把自己的问卷设为模板**不会**阻塞播种。 */
  seedTemplates: () => http.post('/api/template-seeder/init')
}

export default { demo, misc }
