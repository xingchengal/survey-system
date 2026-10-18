/**
 * 题型元数据 —— 与后端 `common/QuestionType` 的数字**必须一致**。
 *
 * ★ 数字只追加、绝不复用：历史答卷只存了 content，题型由 t_question.type 决定，
 *   复用数字会让"老答卷按新题型解释"，统计直接错乱。
 * ★ 页面里不要再写 `type === 1 || type === 2` 这种判断：用 hasOptions / hasChart。
 */

export const QTYPE = {
  SINGLE: 1,
  MULTI: 2,
  TEXT: 3,
  TEXTAREA: 4,
  SELECT: 5,
  RATING: 6,
  DATE: 7
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
  7: { label: '日期', tag: 'info', icon: 'Calendar' }
}

/** 评分题的 5 档文案（1~5 分，和填写页/统计页共用） */
export const RATING_LABELS = ['很差', '较差', '一般', '满意', '非常满意']

/** 评分题的分数选项（存库是字符串 "1"~"5"） */
export const RATING_VALUES = ['1', '2', '3', '4', '5']

export function typeMeta(type) {
  return QUESTION_TYPE_META[type] || { label: `题型 ${type}`, tag: 'info', icon: 'Memo' }
}

export function typeLabel(type) {
  return typeMeta(type).label
}

/** 有选项的题型：单选 / 多选 / 下拉 */
export function hasOptions(type) {
  return type === QTYPE.SINGLE || type === QTYPE.MULTI || type === QTYPE.SELECT
}

/** 单选语义（一题一行）：单选 / 下拉 */
export function isSingle(type) {
  return type === QTYPE.SINGLE || type === QTYPE.SELECT
}

export function isMulti(type) {
  return type === QTYPE.MULTI
}

/** 单选题、多选题、下拉都走"选项列表"编辑区 */
export function isChoice(type) {
  return hasOptions(type)
}

export function isRating(type) {
  return type === QTYPE.RATING
}

export function isDate(type) {
  return type === QTYPE.DATE
}

/** 自由文本：单行 / 多行 */
export function isFreeText(type) {
  return type === QTYPE.TEXT || type === QTYPE.TEXTAREA
}

/**
 * 统计是否按"分布图"渲染。
 * ★ 评分题没有 options，但 1~5 分的分布本身就是图表数据，和后端 hasChart 口径一致。
 */
export function hasChart(type) {
  return hasOptions(type) || isRating(type)
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
