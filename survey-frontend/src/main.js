import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// ★ 组件库语言包：不配这个，分页器会显示 Total / 10/page / Go to（英文），
//   全站中文里混英文非常显眼。日期选择器、空态等文案也一起变成中文。
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// ★ 全站公共样式（工具类 + 语义类 + 设计令牌）：必须放在 element-plus 的 CSS **之后**，
//   这样同为单类选择器时我们这边胜出（详见 src/styles/common.css 顶部说明）。
//   各页面原来散落的 style="..." 正逐步换成这里的类，改样式/换主题色只动这一个文件。
import './styles/common.css'
// ★ 主题层：天空蓝主色 + 卡片圆角/阴影 + 组件级动效 + 骨架屏。
//   必须放在 Element Plus 与 common.css 之后（覆盖组件默认外观）；
//   页面里不要再写死颜色，统一用这里的变量（--sky-500 / --brand-gradient / --shadow-md ...）。
import './styles/theme.css'
import { initTheme } from './utils/theme'
import App from './App.vue'
import router from './router'
// ★ 不要引入"重写 window.fetch"的全局拦截器：token、错误提示、401 处理统一在 api/http.js
//   里完成，页面一律走 src/api/。那类拦截器有两个绕不过去的隐患：
//     1. 内置的 401 白名单要与后端 JwtAuthFilter 重复维护，两处不一致就会出问题；
//     2. 它不认识 api 层的 { silent: true } 选项，无法按调用场景区分要不要跳登录。

// ==================== 图标：按需注册 ====================
// ★ 不要写成遍历整个图标库：
//     import * as ElementPlusIconsVue from '@element-plus/icons-vue'
//     for (const [key, component] of Object.entries(ElementPlusIconsVue)) app.component(key, component)
//   那会把 @element-plus/icons-vue 的**全部（约 293 个）图标**都注册成全局组件，
//   它们也就会被整包打进产物 —— 这是打包体积偏大的主要来源之一。
//   这里只注册项目实际用到的图标（下面这 40 个，与 import 列表一一对应）。
//
// ⚠️ 维护提示：以后在页面里用到新图标，需要在①下面的 import 和②icons 对象里各补一行，
//    否则那个图标不会渲染（控制台会提示组件未注册）。
import {
  AlarmClock, ArrowDown, Bell, BellFilled, Calendar, Camera, ChatDotRound,
  CircleCheck, CircleCheckFilled, CircleCloseFilled, Close, DataBoard, Delete,
  Document, DocumentCopy, Download, EditPen, Expand, Fold, HomeFilled, InfoFilled,
  Loading, MagicStick, Memo, PieChart, Promotion, Rank, Select, Sort, Star, SuccessFilled,
  SwitchButton, Timer, TopRight, User, UserFilled, View, Warning, WarningFilled,
  Moon, Sunny
} from '@element-plus/icons-vue'

const app = createApp(App)

// Element Plus 全量引入（按需引入是进一步减体积的方向，但会改动所有页面的写法）
// ★ 传 locale：分页器「共 N 条 / N 条/页 / 前往」、日期面板、空态等全部走中文
app.use(ElementPlus, { locale: zhCn })
app.use(router)

// 逐个注册上面 import 的图标
const icons = {
  AlarmClock, ArrowDown, Bell, BellFilled, Calendar, Camera, ChatDotRound,
  CircleCheck, CircleCheckFilled, CircleCloseFilled, Close, DataBoard, Delete,
  Document, DocumentCopy, Download, EditPen, Expand, Fold, HomeFilled, InfoFilled,
  Loading, MagicStick, Memo, PieChart, Promotion, Rank, Select, Sort, Star, SuccessFilled,
  SwitchButton, Timer, TopRight, User, UserFilled, View, Warning, WarningFilled,
  Moon, Sunny
}
for (const [name, comp] of Object.entries(icons)) {
  app.component(name, comp)
}

// ★ 恢复上次的主题（深/浅），必须在挂载前设置，避免闪一下浅色
initTheme()

app.mount('#app')
