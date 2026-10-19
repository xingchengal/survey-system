/**
 * 用户相关接口（后端 UserController，基础路径 /api/user）。
 * 业务失败一律 400 + {success:false,message}，本文件不做任何字符串判断：
 * 成功由 http.js resolve 响应体，失败它已用后端 message 弹过红字并抛 ApiError。
 */
import http from './http'

export default {
  /**
   * 登录。
   * ★ silent: true 让错误留在表单里（Login.vue 读 e.message 渲染到 el-alert），不弹 alert。
   * @param {{username:string, password:string, rememberMe?:boolean}} data
   * @returns 成功：{ success, message, token, user, refreshToken?, refreshExpiresIn? }
   */
  login: (data) => http.post('/api/user/login', data, { silent: true }),

  /**
   * 注册。规则后端强制：账号字母数字 4~20 位、密码 6~20 位。
   * 必须先过滑块验证：captchaToken 由 /api/captcha/verify 判定通过后带回，后端消费一次即失效；
   * 不要传 captchaLeft（判定已前移到 /verify，后端不看）。
   * silent: true 同上，错误由 Register.vue 渲染到页内。
   * @param {object} data    { username, password, nickname?, phone? }
   * @param {object} captcha { token, left }（来自 api.captcha.slider + 组件 success 回调）
   */
  register: (data, captcha) =>
    http.post('/api/user/register', data, {
      silent: true,
      params: { captchaToken: captcha && captcha.token }
    }),

  /**
   * 用 refresh token 换新的 access token（"记住我"续期）。
   * 通常页面不用直接调：http.js 的 401 分支会自动续期并重放原请求。
   * 成功返回 { token, refreshToken, user }，两枚都是新的（后端一次性轮换），
   * 调用方必须把两枚都写回本地（utils/auth 的 setAuth）。
   */
  refresh: (refreshToken) => http.post('/api/user/refresh', { refreshToken }),

  /**
   * 退出登录（后端顺带清接口文档的 doc_token Cookie）。
   * ★ 没有 refreshToken 时传 {} 而不是 null：后端收不到 body 会抛 400，
   *   "吊销该用户全部 refresh token"的分支就永远走不到。
   * silent：退出本身就是离开，不为失败再弹红字。
   */
  logout: (refreshToken) =>
    http.post('/api/user/logout', refreshToken ? { refreshToken } : {}, { silent: true }),

  /** 当前登录用户信息 */
  profile: () => http.get('/api/user/profile'),

  /** 改昵称（昵称为空 → 400） */
  updateNickname: (nickname) => http.put('/api/user/nickname', { nickname }),

  /** 改密码（原密码错误 / 新密码不足 6 位 → 400 + message，自动弹红字） */
  updatePassword: (oldPassword, newPassword) =>
    http.put('/api/user/password', { oldPassword, newPassword }),

  /** 改头像（avatar 是 data URL；后端 AvatarValidator 校验类型白名单 + 魔数 + 解码后 ≤2MB） */
  updateAvatar: (avatar) => http.put('/api/user/avatar', { avatar }),

  /** 改联系方式（手机号 / 邮箱，空值表示清空该项；格式不对 → 400） */
  updateContact: (phone, email) => http.put('/api/user/contact', { phone, email }),

  /**
   * 我的最近登录记录：最近 10 条，新的在前，第一行就是本次登录。
   * 没有分页参数、也没有 userId 参数 —— 只能查自己，上限写死在后端。
   */
  loginLogs: () => http.get('/api/user/login-logs'),

  /**
   * 注销我的账号（软删除）。
   * 必须带密码：不可逆的账号级操作，只凭一枚可能被盗的 token 风险太高。
   * 成功即本地登录态失效（后端吊销全部 refresh token），调用方须 clearAuth 并回登录页。
   */
  deleteAccount: (password) => http.del('/api/user/account', { password }),

  /**
   * 用户分页（管理员），params: { pageNum, pageSize, keyword, role }
   * role 只认 'admin' / 'user'；不传 = 全部角色（含管理员）。
   * 行上的 disabled：0 正常，1 已禁用（禁止登录、数据保留，可解禁）。
   */
  page: (params) => http.get('/api/user/page', params),

  /** 已注销用户分页（管理员）—— 「恢复账号」列表，params: { pageNum, pageSize, keyword, role } */
  pageDeleted: (params) => http.get('/api/user/page-deleted', params),

  /**
   * 禁用 / 解禁一个账号（管理员），disabled: 1 = 禁用，0 = 解禁。
   * 批量时页面逐条调用（Promise.allSettled）并传 { silent: true }，失败由页面统一汇总。
   * 后端保护：不能禁用自己、不能禁用最后一个可用管理员；禁用会作废该账号此前签发的 token。
   */
  setDisabled: (id, disabled, opts) => http.put(`/api/user/${id}/disabled`, null, { ...opts, params: { disabled } }),

  /**
   * 导出用户列表为 Excel（管理员），params: { keyword, role, deleted }
   * deleted=true 导「已注销」视图，否则导「在用」；筛选条件与 page 同一套。
   * 后端上限 10000 行，超出返回 400 + 明确文案。
   */
  exportExcel: (params) => http.download('/api/user/export', params, '用户列表.xlsx'),

  /** 恢复已注销账号（管理员）。opts 可传 { silent: true }（批量恢复时避免逐条弹错） */
  restore: (id, opts) => http.put(`/api/user/restore/${id}`, {}, opts),

  /** 删除用户（管理员，后端级联删除其问卷/答卷/通知；失败 400 而不是谎报成功）。
   *  opts 可传 { silent: true }（批量删除时由页面统一汇总提示） */
  remove: (id, opts) => http.del(`/api/user/delete/${id}`, null, opts)
}
