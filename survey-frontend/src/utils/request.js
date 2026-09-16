/**
 * 全局 fetch 拦截器
 * 1. 自动为每个请求加上 Authorization: Bearer <token>
 * 2. 统一处理 401（token 过期或未登录）
 * 3. 统一处理 400/500 错误提示
 */
import { ElMessage } from 'element-plus'

export function setupFetchInterceptor() {
  const originalFetch = window.fetch

  window.fetch = async function (url, options = {}) {
    // ============ 1. 自动加 token ============
    const token = localStorage.getItem('token')
    if (token) {
      options.headers = options.headers || {}
      options.headers['Authorization'] = `Bearer ${token}`
    }

    // 默认 JSON 请求头（如果调用方没指定）
    if (options.body && typeof options.body === 'string') {
      options.headers = options.headers || {}
      if (!options.headers['Content-Type']) {
        options.headers['Content-Type'] = 'application/json'
      }
    }

    // ============ 2. 发起请求 ============
    let response
    try {
      response = await originalFetch(url, options)
    } catch (err) {
      ElMessage.error('网络异常，请检查后端是否启动')
      throw err
    }

    // ============ 3. 统一处理 401 ============
    if (response.status === 401) {
      // 白名单接口不算 401 错误（比如未登录时访问匿名问卷）
      const isWhiteList = url.includes('/api/user/login')
        || url.includes('/api/user/register')
        || url.includes('/api/survey/share/')
        || url.includes('/api/answer/submit-share/')
        || url.includes('/api/announcement/active')
        || url.includes('/api/health')

      if (!isWhiteList) {
        // 清除本地登录状态
        localStorage.removeItem('user')
        localStorage.removeItem('token')

        // 避免重复跳转
        if (!window.location.pathname.includes('/login')) {
          ElMessage.warning('登录已过期，请重新登录')
          setTimeout(() => {
            window.location.href = '/login'
          }, 800)
        }
      }
    }

    return response
  }
}