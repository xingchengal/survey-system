import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// 组件库语言包：不配 locale，分页器/日期面板/空态会显示英文
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// 全站公共样式（工具类 + 设计令牌）：必须放在 element-plus 的 CSS 之后，否则同权重覆盖不了
import './styles/common.css'
// 主题层：主色/圆角/阴影/骨架屏；必须在 Element Plus 与 common.css 之后，页面不要写死颜色
import './styles/theme.css'
import { initTheme } from './utils/theme'
import { hasPermission } from './utils/auth'
import App from './App.vue'
import router from './router'
// 不要引入重写 window.fetch 的全局拦截器：token / 错误提示 / 401 统一在 api/http.js 处理

// ==================== 图标：按需注册 ====================
// 不要遍历整个图标库：全量注册会把整包图标都打进产物。
// ★ 新图标要在下面 import 与 icons 各补一行，否则渲染不出来（控制台提示组件未注册）。
import {
  AlarmClock, ArrowDown, Bell, BellFilled, Calendar, Camera, ChatDotRound,
  CircleCheck, CircleCheckFilled, CircleCloseFilled, Close, DataBoard, Delete,
  Document, DocumentCopy, Download, EditPen, Expand, Files, Fold, HomeFilled, InfoFilled,
  Loading, MagicStick, Memo, PieChart, Promotion, Rank, Select, Sort, Star, SuccessFilled,
  SwitchButton, Timer, TopRight, User, UserFilled, View, Warning, WarningFilled,
  Moon, Sunny, Grid, DataLine, Wallet
} from '@element-plus/icons-vue'

const app = createApp(App)

// 全量引入 Element Plus（按需引入会改动所有页面写法）；locale 让分页器/日期面板/空态走中文
app.use(ElementPlus, { locale: zhCn })
app.use(router)

// 逐个注册上面 import 的图标
const icons = {
  AlarmClock, ArrowDown, Bell, BellFilled, Calendar, Camera, ChatDotRound,
  CircleCheck, CircleCheckFilled, CircleCloseFilled, Close, DataBoard, Delete,
  Document, DocumentCopy, Download, EditPen, Expand, Files, Fold, HomeFilled, InfoFilled,
  Loading, MagicStick, Memo, PieChart, Promotion, Rank, Select, Sort, Star, SuccessFilled,
  SwitchButton, Timer, TopRight, User, UserFilled, View, Warning, WarningFilled,
  Moon, Sunny, Grid, DataLine, Wallet
}
for (const [name, comp] of Object.entries(icons)) {
  app.component(name, comp)
}

/**
 * v-permission：没有该权限点就把元素从 DOM 移除。
 * ★ 只做显隐，不是安全边界 —— localStorage 改一下就能显示；真正拦截在后端。
 * 用 removeChild 而非 display:none：隐藏元素仍可 Tab 聚焦、被脚本点击，容易被误当授权。
 */
app.directive('permission', {
  mounted(el, binding) {
    if (!hasPermission(binding.value)) {
      el.parentNode && el.parentNode.removeChild(el)
    }
  }
})

// 恢复上次主题（深/浅），必须在挂载前执行，否则会闪一下浅色
initTheme()

app.mount('#app')
