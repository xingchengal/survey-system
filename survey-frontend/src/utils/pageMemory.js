/**
 * 列表页页码记忆（sessionStorage）。
 *
 * 为什么用 sessionStorage 而不是 localStorage：
 *   用户"翻到第 3 页 → 进详情 → 返回"要停在原页，但**关掉浏览器再打开不该跳到第 3 页**，
 *   sessionStorage 的生命周期正好等于这个语义（同一标签页会话内有效）。
 *
 * ⚠️ 存进去的必须是合法页码（正整数、≤ 10000，与后端 PageParams 上限一致），
 *    否则脏值会让 el-pagination 落到一个不存在的页上，用户看到"空列表"。
 */

const PREFIX = 'survey_page_'
/** 与后端 PageParams 的页码上限保持一致 */
const MAX_PAGE = 10000

function storageKey(key) {
  return `${PREFIX}${key}`
}

/** 读回上次的页码；没有/脏值/超范围一律回落 */
export function readPageNum(key, fallback = 1) {
  try {
    const raw = sessionStorage.getItem(storageKey(key))
    const num = Number(raw)
    if (!Number.isInteger(num) || num < 1 || num > MAX_PAGE) return fallback
    return num
  } catch {
    // 隐私模式/存储被禁用时 sessionStorage 可能直接抛异常，退回默认值即可
    return fallback
  }
}

/** 记住当前页码（翻页、改变每页条数时调用） */
export function savePageNum(key, num) {
  try {
    sessionStorage.setItem(storageKey(key), String(num))
  } catch {
    // 存不进去不影响功能：最多下次回来落在第 1 页
  }
}
