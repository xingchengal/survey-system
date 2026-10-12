/**
 * 用户相关接口（后端 UserController，基础路径 /api/user）。
 *
 * ══ 契约（唯一判据）══
 *   业务失败一律 **400 + {success:false,code,message}**，所以本文件**没有任何字符串判断**：
 *     · 2xx  → 成功（http.js 把响应体 resolve 出来）
 *     · 非 2xx → 失败（http.js 已用后端 message 弹过红字，并抛 ApiError）
 *   ⇒ 不要在这里比对中文文案：文案一改就会静默失效。
 */
import http from './http'

export default {
  /**
   * 登录。
   * ★ **silent: true**：需求要求"错误信息在表单区域展示，不使用弹窗 alert" ——
   *   抑制 http.js 的自动红字弹窗，由 Login.vue 读 e.message 渲染到页内 el-alert。
   * @param {{username:string, password:string, rememberMe?:boolean}} data
   * @returns 成功：{ success, message, token, user, refreshToken?, refreshExpiresIn? }
   */
  login: (data) => http.post('/api/user/login', data, { silent: true }),

  /** 注册（失败 → 400 + message）
   *  ★ 登录注册的规则（后端强制，前端只要对齐提示文案）：
   *    · 账号规则为"字母数字 4~20 位"（后端 RegisterRequest 的 @Pattern 强制）；
   *    · 密码规则为"6~20 位"；
   *    · 注册**必须**带滑块验证（两步式，见 api/captcha.js）：
   *      拖动结束先调 /api/captcha/verify 由后端判定，通过后把 **captchaToken** 作为 query 参数带上；
   *      后端只认"凭证已被标记为 verified"并消费它，验证不过直接 400。
   *      ★ **不要传 captchaLeft**：判定已前移到 /verify，后端不看这个参数。
   *    · **silent: true**：需求明确要求"接口返回错误信息在表单区域展示，不使用弹窗 alert"，
   *      所以这里抑制 http.js 的自动红字弹窗，由 Register.vue 读 e.message 渲染到页内 el-alert。
   *      ★ 这是全项目少数几个"故意 silent"的写请求之一 —— 改它之前先读上面这句需求。
   * @param {object} data  { username, password, nickname?, phone? }
   * @param {object} captcha { token, left }（来自 api.captcha.slider + 组件 success 回调）
   */
  register: (data, captcha) =>
    http.post('/api/user/register', data, {
      silent: true,
      params: { captchaToken: captcha && captcha.token }
    }),

  /**
   * 用 refresh token 换新的 access token（"记住我"续期）。
   *
   * ★ 通常**页面层不需要直接调它** —— api/http.js 的 401 分支会自动调（静默 + 重放原请求）。
   *   这里保留封装是给两类场景：① 排查问题时手工验一次；② 将来做"提前续期"（在过期前主动换）。
   * ★ 成功返回 { token, refreshToken, user }：**两枚都是新的**（后端做一次性轮换，
   *   旧的 refresh token 已失效）—— 调用方必须把两枚都写回本地（用 utils/auth 的 setAuth）。
   */
  refresh: (refreshToken) => http.post('/api/user/refresh', { refreshToken }),

  /** 退出登录（后端会顺便清掉接口文档的 doc_token Cookie）。
   *  ★ silent：退出本来就是离开本页，没必要为失败再弹一条红字。
   *  ★ **必须带 refreshToken**：不带的话"退出登录"只是前端删了本地存储，
   *    那枚 7 天的凭证在服务端仍然有效（后端按它精确吊销）。 */
  logout: (refreshToken) =>
    http.post('/api/user/logout', refreshToken ? { refreshToken } : null, { silent: true }),

  /** 当前登录用户信息 */
  profile: () => http.get('/api/user/profile'),

  /** 改昵称（失败：昵称不能为空 → 400） */
  updateNickname: (nickname) => http.put('/api/user/nickname', { nickname }),

  /** 改密码（失败：请输入原密码 / 新密码至少6位 / **原密码错误** → 400 + message，自动弹红字） */
  updatePassword: (oldPassword, newPassword) =>
    http.put('/api/user/password', { oldPassword, newPassword }),

  /** 改头像（avatar 是 data URL；后端 AvatarValidator 校验类型白名单 + 魔数 + **解码后 ≤2MB**） */
  updateAvatar: (avatar) => http.put('/api/user/avatar', { avatar }),

  /** 改联系方式（手机号 / 邮箱，空值表示清空该项；格式不对 → 400） */
  updateContact: (phone, email) => http.put('/api/user/contact', { phone, email }),

  /**
   * 我的最近登录记录（需求 三.3）。
   * ★ 返回最近 10 条 `{ id, ip, userAgent, status, createTime }`，**新的在前**；
   *   第一行就是本次登录（登录成功即写日志），所以"上次登录"看第二行。
   * ★ 没有分页参数、也没有 userId 参数 —— 只能查自己，上限写死在后端（LoginLogService）。
   */
  loginLogs: () => http.get('/api/user/login-logs'),

  /**
   * 注销我的账号（需求 三.5，**软删除**）。
   *
   * ★ 必须带密码：这是不可逆的账号级操作（对用户自己而言），只凭一枚可能被盗的 token
   *   就能注销账号的风险高于"多输一次密码"的麻烦（理由同 updatePassword 要求原密码）。
   * ★ 路径是 `/account` 而不是 `/delete/{id}` —— 后者是**管理员删别人**（物理删除 + 级联 7 张表），
   *   两者语义与权限都不同，刻意不复用。
   * ★ 成功之后本地登录态就失效了（后端会顺带吊销全部 refresh token，
   *   而 JwtAuthFilter 每个请求都会发现"账号已注销"→ 401），所以调用方**必须** clearAuth 并回登录页。
   */
  deleteAccount: (password) => http.del('/api/user/account', { password }),

  /** 用户分页（管理员），params: { pageNum, pageSize, keyword } */
  page: (params) => http.get('/api/user/page', params),

  /** 已注销用户分页（管理员）—— 「恢复账号」列表，params 同上 */
  pageDeleted: (params) => http.get('/api/user/page-deleted', params),

  /**
   * 恢复已注销账号（管理员）。
   * ★ 恢复后该用户**必须重新登录**（后端会把 token_valid_after 推到此刻，注销前签发的票全作废）；
   *   数据一直都在（软删除只标记、不删数据）。
   */
  restore: (id) => http.put(`/api/user/restore/${id}`, {}),

  /** 删除用户（管理员，后端级联删除其问卷/答卷/通知；失败 → 400 而不是"谎报成功"） */
  remove: (id) => http.del(`/api/user/delete/${id}`)
}
