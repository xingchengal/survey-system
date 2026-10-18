/**
 * 主题切换（浅色 / 深色）。
 *
 * ★ 唯一入口：所有读写都走这里，不要在页面里直接碰 localStorage 或 documentElement。
 * ★ 存在 localStorage 的 `survey_theme` 里；刷新后由 main.js 的 initTheme() 在最早期恢复，
 *   避免"先闪一下浅色再变深色"。
 */

const KEY = 'survey_theme'
const DARK = 'dark'
const LIGHT = 'light'

/** 当前主题（读不到一律当浅色） */
export function getTheme() {
  try {
    return localStorage.getItem(KEY) === DARK ? DARK : LIGHT
  } catch {
    return LIGHT
  }
}

/** 应用主题：写到 <html data-theme="...">，并持久化 */
export function applyTheme(theme) {
  const value = theme === DARK ? DARK : LIGHT
  document.documentElement.dataset.theme = value
  try {
    localStorage.setItem(KEY, value)
  } catch {
    // 隐私模式下 localStorage 可能不可写：主题仍在本次会话生效，不抛错
  }
  return value
}

/** 启动时调用（main.js）：恢复上次选择 */
export function initTheme() {
  return applyTheme(getTheme())
}

/** 切换并返回新主题 */
export function toggleTheme() {
  return applyTheme(getTheme() === DARK ? LIGHT : DARK)
}

export { DARK, LIGHT }
