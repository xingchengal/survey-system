/**
 * 图表配色与空态工具。
 * ECharts 画在 canvas 上读不到 CSS 变量，所以颜色必须按当前主题现取（chartText() 等）。
 * ★ 色值必须与 styles/theme.css 的令牌一致，不要在这里另起一套颜色。
 * 切换主题后图表要重新 init（页面监听 theme-change）。
 */
export function isDarkTheme() {
  return document.documentElement.dataset.theme === 'dark'
}

/** 次要文字（坐标轴、图例、标签） */
export function chartText() {
  return isDarkTheme() ? '#94a3b8' : '#475569'
}

/** 主要文字（中心数字、标题） */
export function chartStrong() {
  return isDarkTheme() ? '#e6edf7' : '#0f172a'
}

/** 网格线 */
export function chartSplit() {
  return isDarkTheme() ? '#1e293b' : '#eef3fb'
}

/** 卡片底色：环形图扇区的分隔线要与卡片同色（深色下不能再用纯白，否则一圈白缝） */
export function chartSurface() {
  return isDarkTheme() ? '#101d31' : '#ffffff'
}

/** tooltip 的通用样式（深色底 + 浅色字，两种主题下都清楚） */
export function chartTooltip() {
  return isDarkTheme()
    ? {
        backgroundColor: '#13223a',
        borderColor: '#22314a',
        textStyle: { color: '#e6edf7' }
      }
    : {
        backgroundColor: 'rgba(255,255,255,.96)',
        borderColor: '#e6eefb',
        textStyle: { color: '#0f172a' }
      }
}

/**
 * 全站图表统一调色板（按序取色）：蓝=问卷/管理，绿=已发布/成功，橙=草稿/待办，
 * 紫=答卷/AI，青=复制/分享，红=删除/危险；深色模式自动换成同色系提亮版。
 * ★ 页面里不要再各写一份色值数组，直接用 chartPalette() / chartColor()。
 */
export const CHART_PALETTE = ['#2f80ed', '#22c55e', '#f59e0b', '#8b5cf6', '#06b6d4', '#ef4444']

/** 深色模式下的同序提亮版（色相不变，只提亮度） */
const CHART_PALETTE_DARK = ['#5b9cf3', '#34d399', '#fbbf24', '#a78bfa', '#22d3ee', '#f87171']

/** 语义槽位 → 调色板下标（避免页面里写魔法数字） */
export const CHART_INDEX = {
  blue: 0,
  green: 1,
  orange: 2,
  purple: 3,
  cyan: 4,
  red: 5
}

/** 当前主题下的完整调色板（可直接赋给 series.color） */
export function chartPalette() {
  return isDarkTheme() ? CHART_PALETTE_DARK : CHART_PALETTE
}

/** 按序取色（超出长度循环，选项很多时也不会没颜色） */
export function chartColor(index) {
  const palette = chartPalette()
  return palette[index % palette.length]
}

/** 按语义取色：chartColorOf('green') → 已发布 / 成功 */
export function chartColorOf(name) {
  const index = CHART_INDEX[name]
  return chartColor(index === undefined ? 0 : index)
}

/**
 * 图表内空数据态：居中一行文案，如 `graphic: isEmpty ? chartEmptyGraphic('暂无数据') : []`。
 * 与页面里的 el-empty 二选一即可（有独立图表的卡片用 graphic 更省事）。
 */
export function chartEmptyGraphic(text = '暂无数据') {
  return [
    {
      type: 'text',
      left: 'center',
      top: 'middle',
      silent: true,
      style: {
        text,
        fill: chartText(),
        fontSize: 14,
        fontWeight: 500
      }
    }
  ]
}

/** 数字保留规则：整数原样，小数保留 1 位（统一 tooltip / 标签的写法） */
export function chartNum(value) {
  const n = Number(value || 0)
  return Number.isInteger(n) ? String(n) : n.toFixed(1)
}

/** 百分比保留规则：四舍五入到整数，避免 tooltip 里出现 33.333333% */
export function chartPct(percent) {
  return Math.round(Number(percent) || 0)
}

/** 主题切换事件名：AdminLayout 切换时 dispatch，图表页监听后重新 init */
export const THEME_EVENT = 'theme-change'
