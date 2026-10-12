/**
 * 登录态读写工具 —— 统一管理 localStorage 里的 token、refreshToken 与 user。
 *
 * ══ 为什么要有这个文件 ══
 *   各处直接写 `JSON.parse(localStorage.getItem('user'))` 时**必须**自己 try-catch：
 *   那个值一旦被写坏（比如手工改过、或旧版本存了别的格式），JSON.parse 就会抛异常 ——
 *   在路由守卫里抛异常 = **整个应用白屏**，连登录页都进不去。
 *   所以统一收到这里：解析失败就当作"未登录"，清掉脏数据，用户可以正常去登录。
 *
 * ══ 另一个脆弱点：token 与 user 不一致 ══
 *   只看 `user` 存在就放行是不行的。如果 token 过期或被清了、user 还在，
 *   用户会"进得去页面但每个请求都 401"，然后被踢回登录页 —— 体验很割裂。
 *   所以 isLoggedIn() 要求**两者同时存在**。
 *
 * ══ localStorage 里的三个 key（与后端约定一致）══
 *   token        : 登录接口返回的 JWT（access token，12 小时），请求时放在 Authorization: Bearer <token>
 *   refreshToken : 勾了"记住我"才有，7 天有效，只用来换新的 access token
 *                  （在 api/http.js 的 401 分支里自动使用，页面层不用管它）
 *   user         : { id, username, nickname, role, phone, ... }，role 用于前端权限判断
 *
 * ★ 为什么 refreshToken 必须由这里统一管：它和 token 的**生命周期不同**（7 天 vs 12 小时），
 *   一旦某处只清 token 不清它，"退出登录后刷新页面又自动登录"就会发生
 *   （任何一次 401 都会拿残留的 refresh token 去续期）。所以 setAuth/clearAuth 都成对处理。
 *
 * ⚠️ 安全说明：localStorage 是明文可篡改的，所以**前端的角色判断只是"体验层"**，
 *    真正的权限防线在后端（JwtAuthFilter + 各接口的归属/角色校验）。
 *    前端改 role 最多让自己看到空的页面，拿不到任何不该拿的数据。
 */

const TOKEN_KEY = 'token'
const USER_KEY = 'user'
const REFRESH_KEY = 'refreshToken'

/**
 * 安全解析 JSON：解析失败返回 null（并顺手清掉这个坏值）。
 * 这是本文件存在的核心理由 —— 避免一个坏值让整个应用崩掉。
 */
function safeParse(raw, storageKey) {
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw)
    // 只接受对象（有些旧数据可能存的是字符串 "undefined" 之类）
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    console.warn(`[auth] localStorage.${storageKey} 不是合法 JSON，已清除。原值：`, raw)
    localStorage.removeItem(storageKey)
    return null
  }
}

/** 取 token（没有则返回 null） */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY)
}

/** 取当前用户对象（解析失败或格式不对返回 null） */
export function getUser() {
  return safeParse(localStorage.getItem(USER_KEY), USER_KEY)
}

/** 当前用户角色：'admin' / 'user' / null */
export function getRole() {
  const user = getUser()
  return user ? user.role : null
}

/**
 * 是否已登录。
 * ★ 要求 token 与 user **同时存在** —— 只有 user 而没有 token 时，
 *   页面能进去但所有请求都会 401，不如直接当未登录去登录页。
 */
export function isLoggedIn() {
  return Boolean(getToken() && getUser())
}

/**
 * 保存登录态（登录成功后调用）。
 *
 * @param {string} token         access token（JWT）
 * @param {object} user          用户对象
 * @param {string} [refreshToken] 勾了"记住我"时后端会给，必须一起存；
 *                               不勾时传空 → **主动删掉旧的**，否则上次登录残留的那枚
 *                               会让"这次我没勾记住我"却依然能续期 7 天（与用户选择不符）。
 */
export function setAuth(token, user, refreshToken) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
  if (refreshToken) {
    localStorage.setItem(REFRESH_KEY, refreshToken)
  } else {
    localStorage.removeItem(REFRESH_KEY)
  }
}

/** 只更新用户信息（如改完昵称/头像后刷新本地缓存），不动 token */
export function setUser(user) {
  if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/**
 * 清空登录态（登出、401 时调用）。
 * ★ refresh token **必须一起清** —— 它是 7 天的长效凭证，
 *   漏掉就等于"用户以为退出了，但服务端那枚凭证还能续期"。
 */
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(REFRESH_KEY)
}

/**
 * 按角色算出"首页"路径 —— 路由守卫和登录跳转都用它，避免两处写死同样的三元表达式。
 * 管理员首页是系统总览，普通用户是个人数据看板。
 */
export function homePathFor(role) {
  return role === 'admin' ? '/dashboard' : '/my/dashboard'
}

export default {
  getToken,
  getUser,
  getRole,
  isLoggedIn,
  setAuth,
  setUser,
  clearAuth,
  homePathFor
}
