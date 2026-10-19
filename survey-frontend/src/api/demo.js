/**
 * 演示数据与杂项接口。
 *
 * ★ 演示数据与模板种子都仅非 prod 可用（后端 @Profile("!prod")）：/init 批量建密码统一的
 *   demo_user*，生产误点即开撞库入口；两者都需管理员（非管理员 403），init 不幂等（每次追加一批）。
 * 契约：业务失败 400 + message；前端不判断 success 字段。
 * 数据量级：一批 = 20 个用户 + 30 份问卷，每份已发布问卷 3~8 份答卷；
 * 访问量按「答卷数 + 0~19 次只看未提交」回填，保证「访客次数」≥「总答卷」。
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
   * 后端健康检查（Dashboard 的「系统信息」绿点/红点）。
   * silent 是必须的：后端没启动时正靠它显示异常状态，不该同时弹一堆错误提示。
   */
  health: () => http.get('/api/health', null, { silent: true }),

  /** 初始化系统预置模板（管理员，幂等：已有 user_id=0 的预置模板时返回 count=0） */
  seedTemplates: () => http.post('/api/template-seeder/init')
}

export default { demo, misc }
