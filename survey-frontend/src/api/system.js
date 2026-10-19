/**
 * 系统统计接口（后端 SystemStatisticsController）。
 * 字段按模块权限裁剪：有哪个模块的权限才返回哪个模块，前端按返回的模块渲染 Tab。
 */
import http from './http'

export default {
  /** 系统统计（管理端人员）：{ survey?, user?, template?, review?, wallet?, log?, resource? } */
  statistics: () => http.get('/api/system/statistics')
}
