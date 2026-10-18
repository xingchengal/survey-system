/**
 * 题型元数据 —— 与后端 `common/QuestionType` 的数字**必须一致**。
 *
 * ★ 数字只追加、绝不复用：历史答卷只存了 content，题型由 t_question.type 决定，
 *   复用数字会让"老答卷按新题型解释"，统计直接错乱。
 * ★ 页面里不要再写 `type === 1 || type === 2` 这种判断：用 hasOptions / hasChart。
 * ★ 矩阵题的行列标题是**两个 JSON 数组文本**（后端 t_question.matrix_rows/matrix_cols），
 *   读进来要先 parseMatrixAxis；编辑器内部用数组，保存前用 stringifyMatrixAxis 转回去。
 */

export const QTYPE = {
  SINGLE: 1,
  MULTI: 2,
  TEXT: 3,
  TEXTAREA: 4,
  SELECT: 5,
  RATING: 6,
  DATE: 7,
  MATRIX: 8,
  RANKING: 9,
  NPS: 10
}

/**
 * 题型展示元数据（标签 / 标签色 / 图标名）。
 * icon 用 main.js 里已全局注册的图标名，模板里通过 `<component :is="meta.icon" />` 渲染。
 */
export const QUESTION_TYPE_META = {
  1: { label: '单选题', tag: 'primary', icon: 'CircleCheck' },
  2: { label: '多选题', tag: 'warning', icon: 'Select' },
  3: { label: '单行文本', tag: 'success', icon: 'EditPen' },
  4: { label: '多行文本', tag: 'success', icon: 'Memo' },
  5: { label: '下拉选择', tag: 'primary', icon: 'ArrowDown' },
  6: { label: '评分题', tag: 'warning', icon: 'Star' },
  7: { label: '日期', tag: 'info', icon: 'Calendar' },
  8: { label: '矩阵题', tag: 'danger', icon: 'Grid' },
  9: { label: '排序题', tag: 'warning', icon: 'Sort' },
  10: { label: 'NPS', tag: 'danger', icon: 'DataLine' }
}

/** 评分题的 5 档文案（1~5 分，和填写页/统计页共用） */
export const RATING_LABELS = ['很差', '较差', '一般', '满意', '非常满意']

/** 评分题的分数选项（存库是字符串 "1"~"5"） */
export const RATING_VALUES = ['1', '2', '3', '4', '5']

/** NPS 的 11 个分值（存库是字符串 "0"~"10"） */
export const NPS_SCORES = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '10']

export function typeMeta(type) {
  return QUESTION_TYPE_META[type] || { label: `题型 ${type}`, tag: 'info', icon: 'Memo' }
}

export function typeLabel(type) {
  return typeMeta(type).label
}

/** 有选项的题型：单选 / 多选 / 下拉 / 排序（排序的候选对象就是选项） */
export function hasOptions(type) {
  return type === QTYPE.SINGLE || type === QTYPE.MULTI ||
    type === QTYPE.SELECT || type === QTYPE.RANKING
}

/** 单选语义（一题一行）：单选 / 下拉 */
export function isSingle(type) {
  return type === QTYPE.SINGLE || type === QTYPE.SELECT
}

export function isMulti(type) {
  return type === QTYPE.MULTI
}

/** 单选题、多选题、下拉、排序都走"选项列表"编辑区 */
export function isChoice(type) {
  return hasOptions(type)
}

export function isRating(type) {
  return type === QTYPE.RATING
}

export function isDate(type) {
  return type === QTYPE.DATE
}

/** 矩阵（行 × 列） */
export function isMatrix(type) {
  return type === QTYPE.MATRIX
}

/** 排序（拖成 1..N） */
export function isRanking(type) {
  return type === QTYPE.RANKING
}

/** NPS（0~10） */
export function isNps(type) {
  return type === QTYPE.NPS
}

/** 进阶题型（矩阵 / 排序 / NPS）—— 与后端 isAdvanced 同口径，用于提示扣费 */
export function isAdvanced(type) {
  return isMatrix(type) || isRanking(type) || isNps(type)
}

/** 自由文本：单行 / 多行 */
export function isFreeText(type) {
  return type === QTYPE.TEXT || type === QTYPE.TEXTAREA
}

/**
 * 统计是否按"分布图 / 统计卡"渲染。
 * ★ 评分题没有 options，但 1~5 分的分布本身就是图表数据，和后端 hasChart 口径一致。
 * ★ 矩阵 / 排序 / NPS 同样走 chartData，但渲染方式各自定制（矩阵表格、排序平均名次、NPS 分档）。
 */
export function hasChart(type) {
  return hasOptions(type) || isRating(type) || isNps(type) || isMatrix(type)
}

/** 评分题的平均分（统计页/结果页用）；没有数据返回 null */
export function ratingAverage(chartData) {
  if (!Array.isArray(chartData) || chartData.length === 0) return null
  let sum = 0
  let total = 0
  chartData.forEach(item => {
    const score = Number(item.content)
    const count = Number(item.count) || 0
    if (!Number.isNaN(score)) {
      sum += score * count
      total += count
    }
  })
  return total > 0 ? sum / total : null
}

/**
 * 解析矩阵题的行/列标题。
 *
 * ★ 入参可能是三种形态：
 *   · 后端返回的 JSON 字符串（`["A","B"]`）—— 正常路径；
 *   · 编辑器内部已经在用的数组 —— 直接返回副本；
 *   · null / 空串 / 损坏的 JSON —— 返回空数组（页面显示"配置不完整"，不让页面崩）。
 */
export function parseMatrixAxis(raw) {
  if (Array.isArray(raw)) return raw.map(item => String(item == null ? '' : item))
  if (typeof raw !== 'string' || raw.trim() === '') return []
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.map(item => String(item == null ? '' : item))
  } catch {
    return []
  }
}

/** 把编辑器里的标题数组转成后端要的 JSON 文本（保留空项，交给后端 400 说清楚） */
export function stringifyMatrixAxis(list) {
  return JSON.stringify((list || []).map(item => String(item == null ? '' : item).trim()))
}

/**
 * NPS 汇总：推荐者 9~10、被动者 7~8、贬损者 0~6。
 * NPS =（推荐者 - 贬损者）/ 总数 × 100，保留一位小数。
 * ★ 没有任何回答时返回 total=0、score=null（页面显示"暂无数据"，不显示 0 分）。
 */
export function npsSummary(chartData) {
  const buckets = { promoters: 0, passives: 0, detractors: 0, total: 0 }
  if (Array.isArray(chartData)) {
    chartData.forEach(item => {
      const score = Number(item.content)
      const count = Number(item.count) || 0
      if (Number.isNaN(score) || count <= 0) return
      buckets.total += count
      if (score >= 9) buckets.promoters += count
      else if (score >= 7) buckets.passives += count
      else buckets.detractors += count
    })
  }
  const score = buckets.total > 0
    ? Math.round((buckets.promoters - buckets.detractors) * 1000 / buckets.total) / 10
    : null
  return { ...buckets, score }
}

/** 某个 NPS 分值属于哪一档（统计页/结果页给分布条上色用） */
export function npsBucket(score) {
  const n = Number(score)
  if (n >= 9) return 'promoter'
  if (n >= 7) return 'passive'
  return 'detractor'
}
