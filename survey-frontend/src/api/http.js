/**
 * 统一请求客户端 —— 全项目的接口调用都从这里走。
 *
 * ══ 它解决什么问题 ══
 *   页面自己 fetch，就要各自取 token、各自判断成败；而一旦用「比对后端中文文案」
 *   判断成败，后端措辞改一个字，前端就静默失效 —— 所以统一收在这里，
 *   页面只关心「调哪个接口、拿到什么数据」。
 *
 * ══ 成败判断标准（★ 核心设计）══
 *   **只看 HTTP 状态码**，不看响应体里的任何文字：
 *     - 2xx    → 成功，resolve 出响应体（JSON 会自动 parse）
 *     - 非 2xx → 失败，reject 一个 ApiError（message 取后端返回的 message 字段）
 *   之所以能这么简单，是因为后端已经统一：业务失败一律 throw BizException →
 *   GlobalExceptionHandler → **400 + {success:false, code, message, timestamp}**。
 *
 *   ★ 注意：后端**仍有少数** 200 + 文字的端点 ——
 *     注册/登出/改昵称/改密码/改头像/改联系方式都 `return "注册成功"|"已退出"|"修改成功"`。
 *     所以这里一律只看 HTTP 状态码，不比对任何文案。
 *
 * ══ 401 的统一处理（"先续期、续不动才跳登录"）══
 *   ① 收到 401 且本地有 **refresh token** → 静默调 POST /api/user/refresh 换新的 access token，
 *      成功后**自动重放**刚才那次请求 —— 用户完全无感（只要 7 天内的 refresh token 还在，
 *      就不用重新输密码）。
 *   ② 续期失败（或本地没有 refresh token）→ 清登录态 + 跳登录页（带防重复跳转）。
 *   ★ 并发去重：多个请求同时过期时**只发一次** refresh（见下面的 refreshPromise），
 *     否则会触发 refresh token 的"一次性轮换"互相踩踏（第二个请求拿着已吊销的旧 token 必失败，
 *     把用户莫名踢下线）。这是双 token 方案最容易踩的坑。
 *   ★ silent: true 的调用（通知轮询、异常计数、退出登录、匿名页按分享码查问卷）
 *     既不跳转也不弹提示 —— 适合"失败也不该打扰用户"的场景。但它们**同样会尝试续期**：
 *     轮询类请求正是最容易撞上过期的，续上了就不该继续静默失败。
 *   ★ 登录失败是 400（不是 401），不会走这里的续期分支；登录接口的 `silent: true`
 *     是为了把错误信息渲染到页面表单里（见 api/user.js），与本节的 401 处理无关。
 *
 * ══ 不要再引入"重写 window.fetch"的全局拦截器 ══
 *   token、错误提示、401 处理统一在这里完成。那类拦截器有两个绕不过去的隐患：
 *   它内置的 401 白名单要与后端 JwtAuthFilter 重复维护（两处不一致就会出问题），
 *   而且它不认识 `{ silent: true }` 选项，无法按调用场景区分要不要跳登录。
 */

import { ElMessage } from 'element-plus'

/** 与后端 JwtAuthFilter 的约定：token 放在 localStorage 的这个 key 下 */
const TOKEN_KEY = 'token'
const USER_KEY = 'user'
/**
 * refresh token 的存储 key（"记住我"才有）。
 * ★ 为什么它不该和 access token 混在一起：两者生命周期完全不同
 *   （12 小时 vs 7 天），且续期时**两枚都会换新**，混存会让"谁过期了"难以判断。
 */
const REFRESH_KEY = 'refreshToken'

/** refresh token 换取 access token 的端点（后端在 JwtAuthFilter 白名单里，可匿名调） */
const REFRESH_URL = '/api/user/refresh'

/** 接口失败时抛出的错误对象：页面可以用 e.status / e.message 做细分处理 */
export class ApiError extends Error {
  constructor(status, message, body) {
    super(message || '请求失败')
    this.name = 'ApiError'
    this.status = status // HTTP 状态码；0 表示网络层失败（拿不到状态码）
    this.body = body
  }
}

/** 从响应体里尽量提取可读的错误信息（后端异常统一返回 {success:false, message}） */
function extractMessage(body) {
  if (!body) return ''
  if (typeof body === 'string') return body
  return body.message || body.error || ''
}

/**
 * 把 Content-Disposition 里的文件名参数还原成可读中文。
 *
 * ★ 两个分支都做一次"能解就解"，并且对不含 % 的纯 ASCII 名（如 survey_1_answers.xlsx）
 *   保持原样（decodeURIComponent 对它是幂等的）—— 否则中文名被百分号编码时
 *   会原样当文件名，下载下来就是 "%E4%BD%A0%E5%96%9C..." 这种乱码。
 */
function decodeFilename(raw) {
  if (!raw) return ''
  try {
    return decodeURIComponent(raw)
  } catch {
    // 含 % 但不是合法的百分号编码（例如文件名里本来就有 % ）→ 原样返回，不能让它抛异常
    return raw
  }
}

/**
 * 只清本地登录态（不跳转、不提示）。
 *
 * ★ **必须连 refresh token 一起清**。
 *   漏掉它的后果很具体：用户点"退出登录"后，access token 没了，
 *   但 7 天的 refresh token 还躺在 localStorage 里 —— 任何一次 401 都会把它拿去续期，
 *   于是"我明明退出了，刷新页面又自动登录了"。
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

/**
 * 一次性写入登录态（登录/续期成功后调用）。
 * ★ 集中在这里的目的：避免"有的地方写了 refresh token、有的地方没写"这类不一致
 */
export function setAuthTokens(token, refreshToken) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken)
}

/** 清登录态并跳登录页（避免重复跳转、避免在登录页自己跳自己） */
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

/**
 * 把对象拼成查询串（自动跳过 null / undefined / 空串，并做 URL 编码）。
 * 页面里就不用再手写 `?a=1&b=2` 了。
 */
export function toQuery(params) {
  if (!params) return ''
  const parts = Object.entries(params)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
  return parts.length ? `?${parts.join('&')}` : ''
}

/**
 * 正在进行的续期请求（并发去重）。
 *
 * ★ 为什么必须是模块级变量：双 token 的 refresh 是**一次性轮换**的 ——
 *   两个请求同时拿到 401、各自去 refresh，第二个会拿着"已被第一个吊销的旧 token"，
 *   结果续期失败 → 用户被莫名踢下线。所以同一时刻只允许一个续期在飞，
 *   其它请求 await 同一个 Promise。
 * ★ 完成后必须置回 null（finally），否则后续过期永远不再续期。
 */
let refreshPromise = null

/**
 * 静默续期：用 refresh token 换新的 access token（并轮换 refresh token）。
 *
 * ★ 直接用原生 fetch，**不走 sendRequest** —— 否则一旦后端返回 401 又回到这里，
 *   就成了无限递归。
 *
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
      // ★ 两枚都要覆盖：后端做了轮换，旧的 refresh token 此刻已被吊销，
      //   继续用旧的会让"下一次续期"必失败。
      setAuthTokens(data.token, data.refreshToken)
      return true
    } catch {
      // 网络异常也当续期失败：交给上层走"跳登录"，不要在请求链路里抛异常
      return false
    } finally {
      refreshPromise = null
    }
  })()

  return refreshPromise
}

/**
 * 真正发一次请求（不含 401 续期逻辑）。
 * ★ 拆出来的原因：401 续期成功后需要**原样重放**这次请求，所以发送动作必须可复用。
 *
 * @param {string} method   GET / POST / PUT / DELETE
 * @param {string} url      接口路径（以 /api 开头）
 * @param {object} options
 *   - data   请求体对象，自动 JSON 序列化
 *   - params 查询参数对象，自动拼成 query string
 *   - blob   为 true 时返回 Blob（导出 Excel 用）
 *   - silent 为 true 时不自动弹错误提示、不自动跳登录
 *   - raw    为 true 时直接返回 Response 对象
 */
async function sendRequest(method, url, options = {}) {
  const { data, params, blob, silent, raw } = options

  const finalUrl = url + toQuery(params)
  const headers = {}

  // 1. 自动带登录凭证
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) headers['Authorization'] = `Bearer ${token}`

  const init = { method, headers }
  if (data !== undefined && data !== null) {
    headers['Content-Type'] = 'application/json'
    init.body = JSON.stringify(data)
  }

  // 2. 发请求：网络层异常单独区分（这时没有状态码，不能当 HTTP 错误处理）
  let response
  try {
    response = await fetch(finalUrl, init)
  } catch {
    if (!silent) ElMessage.error('网络异常，请检查后端是否启动')
    throw new ApiError(0, '网络异常')
  }

  // 3. 401：**这一层不直接踢人**，而是抛一个带标记的错误，由下面的 request() 负责
  //    "先续期、续不动才跳登录"。
  //    ★ 注意这里**不清本地登录态** —— 否则续期时读不到 refresh token（它和 token 一起被清）。
  //      真正该清的时刻是"续期失败"，那时由 handleUnauthorized() 统一清。
  if (response.status === 401) {
    const err = new ApiError(401, '未登录或登录已过期')
    err.needRefresh = true   // 标记：请求包装层据此决定要不要续期
    err.silent = silent
    throw err
  }

  // 4. 需要原始 Response 的场景（如下载文件时要从响应头读 Content-Disposition）
  if (raw) {
    if (!response.ok) {
      // ★ 尽量把后端的 message 取出来（后端失败时返回 {success:false,message} JSON），
      //   否则用户只看到"请求失败（HTTP 400）"，分不清是"无权限"还是"问卷不存在"。
      const body = await response.text().catch(() => '')
      // 后端错误体一般是 JSON（{success:false,message}）；解析失败就按纯文本处理。
      // 用解析结果优先，其次原文，最后兜底成"请求失败（HTTP xxx）"。
      let parsed = null
      try {
        parsed = JSON.parse(body)
      } catch {
        // 不是 JSON：例如网关返回的 HTML —— 保持 parsed = null，下面用 body 原文
      }
      const message = extractMessage(parsed) || body || `请求失败（HTTP ${response.status}）`
      if (!silent) ElMessage.error(message)
      throw new ApiError(response.status, message, body)
    }
    return response
  }

  // 5. 二进制响应（blob 通道）
  //   ★ 失败时**复用 raw 分支的做法**把后端 message 取出来，不要硬编码"导出失败"：
  //     导出的真实失败原因很有价值：「无权查看他人问卷的数据」、
  //     「该问卷已有 N 份答卷，单次最多导出 5000 份…」、「明细共 N 行…」——
  //     吞成一句"导出失败"就分不清是无权限、超限（该分批）还是没数据。
  //   ★ 现状说明：目前唯一的下载入口 http.download() 走的是 raw 通道，
  //     所以 blob 通道属于**预留但未被使用**的路径 —— 留着并修好，避免以后有人用它时踩坑。
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

  // 6. 解析响应体：后端多数接口返回 JSON，少数（如"修改成功"）返回纯字符串
  const contentType = response.headers.get('content-type') || ''
  let body = null
  try {
    body = contentType.includes('application/json') ? await response.json() : await response.text()
  } catch {
    // 解析失败就当作空响应体（body 保持 null）——
    // 例如后端返回 204 / 空内容，或声明的 content-type 与实际内容不符
  }

  // 7. 非 2xx 一律按失败处理
  if (!response.ok) {
    const message = extractMessage(body) || `请求失败（HTTP ${response.status}）`
    if (!silent) ElMessage.error(message)
    throw new ApiError(response.status, message, body)
  }

  return body
}

/**
 * 主动弹一条错误提示（手动提示用）。
 *
 * ★ 绝大多数失败都由 http.js 在非 2xx 时自动弹出后端 message，
 *   本函数只留给两类场景：
 *     ① 请求成功但返回的数据不可用（例如 ai.js 拿不到 surveyId）；
 *     ② 调用时传了 silent: true（轮询、附带统计、退出登录），又确实需要提示。
 */
export function notifyError(message) {
  ElMessage.error(message || '操作失败')
}

/**
 * 下载二进制文件（如导出 Excel）。
 *
 * ★ 与 get/post 的区别：它会从响应头 Content-Disposition 里解析**后端给的文件名**，
 *   然后直接触发浏览器下载。页面里只需要 `await api.answer.exportExcel(id)` 一行，
 *   不用自己写 createObjectURL / <a download> / revokeObjectURL。
 *
 * @param {string} url          接口路径
 * @param {object} params       查询参数
 * @param {string} fallbackName 后端没给文件名时的兜底文件名
 * @param {object} opts         其余请求选项（如 silent）—— ★ 必须转发给 request，
 *                              不转发的话传了也不生效（静默会失效）
 * @returns {Promise<string>}   实际使用的文件名
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
    // ★ 防御性分支：后端现在统一用 filename*（RFC 5987）传中文名，filename= 里
    //   不会再出现 URLEncoder 编码的中文；这里仍解一次，否则万一有旧格式进来，
    //   下载下来的文件名就是 "%E4%BD%A0..." 这种乱码。
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
 * 对外的请求入口：在 sendRequest 之上加"401 → 静默续期 → 重放一次"的逻辑。
 *
 * ★ 为什么把包装放在这里而不是 sendRequest 内部：sendRequest 是"一次干净的 HTTP 交互"，
 *   重放是**编排**，两者混在一起会让 401 分支同时承担"发送"和"续期"两件事，很难读。
 *
 * ★ 只重放**一次**（靠"进了 catch 就不再重放"的结构保证）：如果续期拿到的 token 仍然 401
 *   （例如后端刚改了密钥、或该用户被删了），继续重试就是死循环。
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
 * 方便使用：与后端 HTTP 动词一一对应。
 *
 * ★ `del` 的签名是 `(url, data, opts)`：只接 `(url, opts)` **发不出请求体**，
 *   而「账号注销」需要 `DELETE /api/user/account` 带上 `{password}` 做二次确认
 *   （后端是 `@RequestBody`）。
 *   调用方式与 post/put 一致：`http.del(url)` / `http.del(url, { ... })` / `http.del(url, data, opts)`。
 *   ★ 既有调用点都是 `del(url)` 一个参数，形参追加在末尾，**不会破坏它们**。
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
