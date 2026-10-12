/**
 * ECharts 按需引入 —— 所有图表页面统一从这里拿 echarts 实例。
 *
 * ── 为什么要有这个文件 ────────────────────────────────
 *   图表页面必须从这里拿 echarts，**不要**写 `import * as echarts from 'echarts'` ——
 *   那会把**整个 echarts**（含地图、3D、各种用不到的图表类型）打进来，
 *   产物里 echarts 那个 chunk 会明显变大。这里只注册项目用得到的东西。
 *
 * ── 用法 ─────────────────────────────────────────────
 *   import echarts from '../utils/echarts'
 *   const chart = echarts.init(dom)          // init / graphic / 用法完全不变
 *
 * ── ⚠️ 维护提示 ───────────────────────────────────────
 *   如果以后要用新的图表类型（例如散点图 scatter）或新组件（例如工具栏 toolbox），
 *   必须在上面的 import 和下面的 echarts.use([...]) 里补上，
 *   否则图表会渲染不出来或缺少交互（控制台可能没有明显报错，容易踩坑）。
 *
 * ── 当前注册清单（依据实际代码统计）────────────────────
 *   图表：饼图 pie、柱状图 bar、折线图 line、仪表盘 gauge
 *   组件：标题 title、提示框 tooltip、图例 legend、直角坐标系 grid（xAxis/yAxis 依赖它）
 *   渲染器：Canvas（页面里没有用 SVG 渲染的场景）
 */

import * as echarts from 'echarts/core'
import { BarChart, GaugeChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  // 四种图表
  PieChart,
  BarChart,
  LineChart,
  GaugeChart,
  // 四个组件（grid 是 xAxis/yAxis 的容器，柱图/折线图必须注册它）
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  // 渲染器
  CanvasRenderer
])

// 说明：echarts.graphic.LinearGradient（SystemStatistics.vue 里做渐变填充用到）
// 包含在 echarts/core 的导出里，不需要额外注册。
export default echarts
