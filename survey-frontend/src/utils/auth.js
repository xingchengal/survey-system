/**
 * 登录态读写工具 —— 统一管理 localStorage 里的 token / refreshToken / user 与权限点。
 * token 放 Authorization: Bearer；refreshToken 只有"记住我"才有（7 天），用于静默续期。
 *
 * ★ token / refreshToken / user 必须一起写、一起清：漏清 refreshToken 会导致
 *   "退出登录后刷新页面又自动登录"。
 * 前端角色判断只是体验层（localStorage 可改），真正的权限防线在后端。
 */

const TOKEN_KEY = 'token'
const USER_KEY = 'user'
const REFRESH_KEY = 'refreshToken'
/** 登录接口下发的全局权限点（survey:edit 等），只用于界面显隐；单份问卷权限看 myPermissions */
const PERMISSIONS_KEY = 'permissions'

/** 安全解析 JSON：失败返回 null 并清掉坏值 —— 路由守卫里抛异常会让整个应用白屏 */
function safeParse(raw, storageKey) {
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw)
    // 只接受对象（旧数据可能存的是字符串 "undefined" 之类）
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

/**
 * 取 refresh token（只有勾了"记住我"才有），登出时要交给后端精确吊销。
 * ★ key 名只有本文件与 api/http.js 知道，改名必须两处一起改。
 */
export function getRefreshToken() {
  return localStorage.getItem(REFRESH_KEY)
}

/** 取当前用户对象（解析失败或格式不对返回 null） */
export function getUser() {
  return safeParse(localStorage.getItem(USER_KEY), USER_KEY)
}

/** 当前用户角色：'admin' / 'subadmin' / 'user' / null */
export function getRole() {
  const user = getUser()
  return user ? user.role : null
}

/** 是否已登录：token 与 user 必须同时存在，否则进得去页面但每个请求都 401 */
export function isLoggedIn() {
  return Boolean(getToken() && getUser())
}

/**
 * 保存登录态（登录成功后调用）。
 * refreshToken 传空时主动删掉旧值，否则上次的"记住我"会继续生效 7 天。
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

/** 取全局权限点（解析失败/未存 → 空数组） */
export function getPermissions() {
  const raw = localStorage.getItem(PERMISSIONS_KEY)
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    localStorage.removeItem(PERMISSIONS_KEY)
    return []
  }
}

/** 保存权限点（登录成功后调用；传非数组视为"没给"，清掉旧值） */
export function setPermissions(permissions) {
  if (Array.isArray(permissions)) {
    localStorage.setItem(PERMISSIONS_KEY, JSON.stringify(permissions))
  } else {
    localStorage.removeItem(PERMISSIONS_KEY)
  }
}

/**
 * 前端权限判断 —— 只用于显隐，不是安全边界。
 * 没有权限点时（旧登录态）按角色兜底：admin 恒 true，普通用户放行 survey:*，
 * 否则老用户刷新前会丢掉一半按钮。
 */
export function hasPermission(code) {
  if (!code) return true
  // admin 恒放行，与后端同一口径：老后端只下发旧权限点时，超管仍要看到新菜单
  if (getRole() === 'admin') return true
  const stored = localStorage.getItem(PERMISSIONS_KEY)
  if (stored == null) {
    const role = getRole()
    if (role === 'admin') return true
    return typeof code === 'string' && code.startsWith('survey:')
  }
  return getPermissions().includes(code)
}

/** 只更新用户信息（如改完昵称/头像后刷新本地缓存），不动 token */
export function setUser(user) {
  if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/**
 * 清空登录态（登出、401 时调用）。
 * 四个 key 必须一起清：残留 refreshToken 还能续期，残留权限会让下个账号短暂按旧菜单渲染。
 */
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(REFRESH_KEY)
  // 权限点跟着登录态一起清
  localStorage.removeItem(PERMISSIONS_KEY)
}

/** 是否管理端人员（admin / subadmin）：只用于数据范围与管理端外壳，模块权限看 hasPermission() */
export function isStaff(role) {
  const r = role || getRole()
  return r === 'admin' || r === 'subadmin'
}

/** 是否超级管理员（admin）：次级管理员即使权限再多，也看不到超管专属入口 */
export function isSuperAdmin(role) {
  const r = role || getRole()
  return r === 'admin'
}

/**
 * 按角色算首页路径（路由守卫与登录跳转共用）：
 * admin / subadmin → 系统总览（subadmin 只看已授权模块），其余 → 个人数据看板。
 */
export function homePathFor(role) {
  if (role === 'admin' || role === 'subadmin') return '/dashboard'
  return '/my/dashboard'
}

export default {
  getToken,
  getRefreshToken,
  getUser,
  getRole,
  getPermissions,
  setPermissions,
  hasPermission,
  isLoggedIn,
  setAuth,
  setUser,
  clearAuth,
  homePathFor,
  isStaff,
  isSuperAdmin
}
