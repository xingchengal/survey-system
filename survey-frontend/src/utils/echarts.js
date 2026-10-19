/**
 * ECharts 按需注册 —— 图表页面统一 `import echarts from '../utils/echarts'`。
 * ★ 不要 `import * as echarts from 'echarts'`：会把整个包（地图、3D 等）打进产物。
 * ★ 新增图表类型/组件时必须同时补 import 和 echarts.use([...])，否则图表渲染不出来，
 *   控制台也没有明显报错。
 * 当前注册：pie / bar / line / gauge + title / tooltip / legend / grid，Canvas 渲染。
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

// echarts.graphic.LinearGradient 由 echarts/core 导出（统计页做渐变填充用），无需额外注册
export default echarts
