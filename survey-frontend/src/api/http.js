/**
 * 统一请求客户端 —— 全项目接口调用都从这里走。
 *
 * 成败只看 HTTP 状态码，不比对后端中文文案；非 2xx 抛 ApiError，
 * message 取后端响应体里的 message 字段。
 * ★ 401 先静默续期 refresh token，成功则原样重放一次；续期失败才清登录态跳登录。
 * ★ silent: true 的调用不弹提示、不跳转，但仍会尝试续期（轮询最容易撞上过期）。
 */

import { ElMessage } from 'element-plus'

/** 与后端 JwtAuthFilter 的约定：token / user 存 localStorage 的这两个 key */
const TOKEN_KEY = 'token'
const USER_KEY = 'user'
/**
 * refresh token 的存储 key（"记住我"才有）。
 * ★ 必须与 utils/auth.js 里的同名常量一致 —— 两处 key 名要一起改。
 */
const REFRESH_KEY = 'refreshToken'

/** refresh token 换 access token 的端点（后端白名单内，可匿名调） */
const REFRESH_URL = '/api/user/refresh'

/** 接口失败时抛出的错误对象：页面可用 e.status / e.message 做细分处理 */
export class ApiError extends Error {
  constructor(status, message, body) {
    super(message || '请求失败')
    this.name = 'ApiError'
    this.status = status // HTTP 状态码；0 表示网络层失败（拿不到状态码）
    this.body = body
  }
}

/** 从响应体里尽量提取可读错误信息（后端异常统一返回 {success:false, message}） */
function extractMessage(body) {
  if (!body) return ''
  if (typeof body === 'string') return body
  return body.message || body.error || ''
}

/**
 * 把 Content-Disposition 里的文件名参数还原成可读中文。
 * 非法百分号编码（文件名本身含 %）原样返回，不能让它抛异常。
 */
function decodeFilename(raw) {
  if (!raw) return ''
  try {
    return decodeURIComponent(raw)
  } catch {
    // 含 % 但不是合法编码 → 原样返回
    return raw
  }
}

/**
 * 只清本地登录态（不跳转、不提示）。
 * 必须连 refresh token 一起清，否则"退出登录后刷新页面又自动登录"。
 */
export function clearLocalAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(REFRESH_KEY)
}

/** 读当前 refresh token（没有就返回 null） */
export function getRefreshToken() {
  return localStorage.getItem(REFRESH_KEY)
}

/** 一次性写入登录态（登录/续期成功后调用），避免有的地方写了 refresh token、有的没写 */
export function setAuthTokens(token, refreshToken) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken)
}

/** 清登录态并跳登录页（防重复跳转、不在登录页自己跳自己） */
let redirecting = false
function handleUnauthorized() {
  clearLocalAuth()
  if (redirecting) return
  if (window.location.pathname.includes('/login')) return
  redirecting = true
  ElMessage.warning('登录已过期，请重新登录')
  setTimeout(() => {
    window.location.href = '/login'
  }, 800)
}

/** 把对象拼成查询串（跳过 null / undefined / 空串并做 URL 编码） */
export function toQuery(params) {
  if (!params) return ''
  const parts = Object.entries(params)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

/**
 * 正在进行的续期请求（并发去重）。
 * refresh token 是一次性轮换：并发续期会让后到的一个拿已吊销的旧 token，把用户莫名踢下线。
 * 完成后必须置回 null（finally），否则后续过期永远不再续期。
 */
let refreshPromise = null

/**
 * 静默续期：用 refresh token 换新的 access token（并轮换 refresh token）。
 * 直接用原生 fetch，不走 sendRequest —— 否则 401 会回到这里形成无限递归。
 * @returns {Promise<boolean>} 续期是否成功
 */
async function tryRefresh() {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return false
  if (refreshPromise) return refreshPromise

  refreshPromise = (async () => {
    try {
      const res = await fetch(REFRESH_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken })
      })
      if (!res.ok) return false
      const data = await res.json()
      if (!data || !data.token) return false
      // 两枚都要覆盖：后端已轮换，旧的 refresh token 此刻已吊销
      setAuthTokens(data.token, data.refreshToken)
      return true
    } catch {
      // 网络异常也按续期失败处理，由上层统一跳登录
      return false
    } finally {
      refreshPromise = null
    }
  })()

  return refreshPromise
}

/**
 * 真正发一次请求（不含 401 续期逻辑）；拆出来是为了续期成功后能原样重放。
 *
 * @param {string} method GET / POST / PUT / DELETE
 * @param {string} url    接口路径（以 /api 开头）
 * @param {object} options data 请求体 / params 查询参数 / blob 返回 Blob /
 *                         silent 不弹提示不跳登录 / raw 直接返回 Response
 */
async function sendRequest(method, url, options = {}) {
  const { data, params, blob, silent, raw, headers: customHeaders } = options

  const finalUrl = url + toQuery(params)
  const headers = {}

  // 自动带登录凭证
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) headers['Authorization'] = `Bearer ${token}`

  // 额外请求头（匿名答题页的 X-Survey-Password）：密码只放头不放 query，
  // query 会进访问日志、浏览器历史与 Referer
  if (customHeaders && typeof customHeaders === 'object') {
    Object.assign(headers, customHeaders)
  }

  const init = { method, headers }
  if (data !== undefined && data !== null) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(data)
  }

  // 网络层异常单独区分：这时没有状态码，不能当 HTTP 错误处理
  let response
  try {
    response = await fetch(finalUrl, init)
  } catch {
    if (!silent) ElMessage.error('网络异常，请检查后端是否启动')
    throw new ApiError(0, '网络异常')
  }

  // 401 这一层不直接踢人：抛带标记的错误，由 request() 决定"先续期、续不动才跳登录"。
  // 这里不清登录态 —— 续期还要读 refresh token，清了就续不了。
  if (response.status === 401) {
    const err = new ApiError(401, '未登录或登录已过期')
    err.needRefresh = true   // 标记：请求包装层据此决定要不要续期
    err.silent = silent
    throw err
  }

  // 需要原始 Response 的场景（下载文件时要从响应头读 Content-Disposition）
  if (raw) {
    if (!response.ok) {
      // 失败也把后端 message 取出来，否则用户只看到"请求失败（HTTP 400）"，分不清原因
      const body = await response.text().catch(() => '')
      let parsed = null
      try {
        parsed = JSON.parse(body)
      } catch {
        // 不是 JSON（例如网关返回 HTML）→ 用原文兜底
      }
      const message = extractMessage(parsed) || body || `请求失败（HTTP ${response.status}）`
      if (!silent) ElMessage.error(message)
      throw new ApiError(response.status, message, body)
    }
    return response
  }

  // 二进制响应（blob 通道）：失败同样取后端 message，不要硬编码"导出失败"——
  // 真实原因（无权限 / 超出导出上限）决定了用户该怎么做
  if (blob) {
    if (!response.ok) {
      const rawBody = await response.text().catch(() => '')
      let parsedBody = null
      try {
        parsedBody = JSON.parse(rawBody)
      } catch {
        // 不是 JSON（例如网关 HTML）→ 保留原文兜底
      }
      const blobMessage = extractMessage(parsedBody) || rawBody || `导出失败（HTTP ${response.status}）`
      if (!silent) ElMessage.error(blobMessage)
      throw new ApiError(response.status, blobMessage, rawBody)
    }
    return response.blob()
  }

  // 解析响应体：多数接口返回 JSON，少数（如"修改成功"）返回纯字符串
  const contentType = response.headers.get('content-type') || ''
  let body = null
  try {
    body = contentType.includes('application/json') ? await response.json() : await response.text()
  } catch {
    // 解析失败当空响应体（204 / 空内容 / content-type 与实际不符）
  }

  // 非 2xx 一律按失败处理
  if (!response.ok) {
    const message = extractMessage(body) || `请求失败（HTTP ${response.status}）`
    if (!silent) ElMessage.error(message)
    throw new ApiError(response.status, message, body)
  }

  return body
}

/**
 * 主动弹一条错误提示（手动提示用）。
 * 多数失败已由 http.js 自动弹后端 message；这里只给两类场景：
 * ① 请求成功但数据不可用（如 ai.js 拿不到 surveyId）；② 传了 silent 又确实需要提示。
 */
export function notifyError(message) {
  ElMessage.error(message || '操作失败')
}

/**
 * 下载二进制文件（导出 Excel）：解析 Content-Disposition 里的后端文件名并触发下载。
 * opts 必须转发给 request，否则传了 silent 也不生效。
 *
 * @param {string} url
 * @param {object} params
 * @param {string} fallbackName 后端没给文件名时的兜底文件名
 * @returns {Promise<string>} 实际使用的文件名
 */
export async function download(url, params, fallbackName = 'download', opts = {}) {
  const res = await request('GET', url, { ...opts, params, raw: true })

  // 文件名优先取 RFC 5987 的 filename*（可携带中文），退回普通 filename
  let filename = fallbackName
  const disposition = res.headers.get('content-disposition') || ''
  const starMatch = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  const plainMatch = /filename="?([^";]+)"?/i.exec(disposition)
  if (starMatch) {
    // 标准写法：中文名在 filename* 里，浏览器能直接解码
    filename = decodeFilename(starMatch[1])
  } else if (plainMatch) {
    // ★ 防御旧格式：filename= 里若还有 URLEncoder 中文，也要解一次，否则文件名乱码
    filename = decodeFilename(plainMatch[1])
  }

  const blob = await res.blob()
  const objectUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = objectUrl
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(objectUrl)
  return filename
}

/**
 * 对外入口：401 → 静默续期 → 只重放一次；续期失败才清态跳登录。
 * ★ 只重放一次，否则 token 仍 401 时会死循环。
 */
export async function request(method, url, options = {}) {
  try {
    return await sendRequest(method, url, options)
  } catch (e) {
    // 只有"401 且需要续期"才走这条路；其余错误（400/403/413/429/网络）原样抛给页面
    if (!e || !e.needRefresh) throw e

    const refreshed = await tryRefresh()
    if (!refreshed) {
      // 续期失败：这才是真正该清态 + 跳登录的时刻
      handleUnauthorized()
      throw new ApiError(401, '登录已过期，请重新登录')
    }

    try {
      // 重放：此时 Authorization 已是新 token（sendRequest 每次都现读 localStorage）
      return await sendRequest(method, url, options)
    } catch (e2) {
      // 换了新 token 仍然 401 → 不再重试，直接按未登录处理
      if (e2 && e2.needRefresh) {
        handleUnauthorized()
        throw new ApiError(401, '登录已过期，请重新登录')
      }
      throw e2
    }
  }
}

/**
 * 与后端 HTTP 动词一一对应。
 * ★ `del` 带 data：账号注销需要 `DELETE /api/user/account` 的 `{password}` 请求体。
 */
const http = {
  get: (url, params, opts) => request('GET', url, { ...opts, params }),
  post: (url, data, opts) => request('POST', url, { ...opts, data }),
  put: (url, data, opts) => request('PUT', url, { ...opts, data }),
  del: (url, data, opts) => request('DELETE', url, { ...opts, data }),
  request,
  download,
  notifyError,
  ApiError
}

export default http
