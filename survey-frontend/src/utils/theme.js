/**
 * 主题切换（浅色 / 深色）。
 * ★ 唯一入口：读写都走这里，页面不要直接碰 localStorage 或 documentElement。
 * ★ 颜色令牌只在 styles/theme.css 定义，这里只负责切换 <html data-theme="...">；
 *   main.js 最早调 initTheme() 恢复，避免刷新时先闪一下浅色。
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
    // 隐私模式下可能不可写：主题仍在本次会话生效，不抛错
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
