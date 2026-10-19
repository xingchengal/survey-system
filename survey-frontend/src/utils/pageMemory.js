/**
 * 列表页页码记忆（sessionStorage）。
 * 翻到第 3 页 → 进详情 → 返回要停在原页，关掉标签页再打开则回到第 1 页。
 *
 * ★ 只存页码：不要顺手塞筛选值或用户输入（敏感数据不落盘）；
 *   且必须是合法页码（正整数、≤ 10000），否则 el-pagination 会落到不存在的页。
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
