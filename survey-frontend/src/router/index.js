import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../layouts/AdminLayout.vue'
// ★ 登录态统一从 utils/auth 读，不要各处直接 JSON.parse localStorage
//   （原因见 utils/auth.js 的类注释：解析失败会导致整个应用白屏）
import { getUser, isLoggedIn, homePathFor, hasPermission } from '../utils/auth'

const routes = [
  // ===== 公开页面 =====
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('../views/Register.vue'),
    meta: { title: '注册', public: true }
  },
  {
    path: '/s/:shareCode',
    name: 'SurveyFillByCode',
    component: () => import('../views/SurveyFillByCode.vue'),
    meta: { title: '填写问卷', public: true }
  },
  {
    // 公开结果页：作者在「设置」里开启「公开结果」后，匿名可访问。
    // ★ public: true —— 不加会先被守卫踢去登录页，而结果页本来就不需要账号。
    path: '/r/:shareCode',
    name: 'PublicResult',
    component: () => import('../views/PublicResult.vue'),
    meta: { title: '公开结果', public: true }
  },

  // ===== 编辑器：独立全屏布局（不套 AdminLayout）=====
  // ★ 需求：题型面板要占**整个网页最左**（像问卷星），所以编辑器不能再放进
  //   AdminLayout 的 el-main 里（那样最左永远是系统导航栏）。
  //   这两个路由放在顶层、不带父布局，由 SurveyEdit 自己渲染「左侧题型面板 + 右侧编辑区」。
  {
    path: '/my/surveys/:id/edit',
    name: 'SurveyEdit',
    component: () => import('../views/SurveyEdit.vue'),
    meta: { title: '编辑问卷', roles: ['user', 'admin'] }
  },
  {
    path: '/admin/templates/:id/edit',
    name: 'TemplateEdit',
    component: () => import('../views/SurveyEdit.vue'),
    meta: { title: '编辑模板', roles: ['admin'], fromTemplate: true }
  },

  // ===== 后台页面 =====
  {
    path: '/',
    component: AdminLayout,
    // 访问根路径时按角色分流；未登录去登录页。
    // ★ 必须走 utils/auth.getUser()：裸 JSON.parse 时 localStorage 的值一旦被写坏，
    //   访问 / 就直接白屏；getUser() 解析失败按"未登录"处理。
    redirect: () => {
      const user = getUser()
      if (!user) return '/login'
      return homePathFor(user.role)
    },
    children: [
      // ---- 管理员专属 ----
      {
        path: 'admin/profile',
        name: 'AdminProfile',
        component: () => import('../views/AdminProfile.vue'),
        meta: { title: '个人中心', roles: ['admin'] }
      },
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '系统总览', roles: ['admin'] }
      },
      {
        path: 'users',
        name: 'UserManage',
        component: () => import('../views/UserManage.vue'),
        meta: { title: '用户管理', roles: ['admin'] }
      },
      {
        path: 'admin/surveys',
        name: 'AdminSurveyList',
        component: () => import('../views/AdminSurveyList.vue'),
        meta: { title: '全部问卷', roles: ['admin'] }
      },
      {
        // 模板管理：集中维护 is_template = 1 的问卷（改分类 / 编辑内容 / 取消模板 / 删除）
        path: 'admin/templates',
        name: 'TemplateManage',
        component: () => import('../views/TemplateManage.vue'),
        meta: { title: '模板管理', roles: ['admin'] }
      },
      {
        path: 'statistics',
        name: 'SystemStatistics',
        component: () => import('../views/SystemStatistics.vue'),
        meta: { title: '系统统计', roles: ['admin'] }
      },
      {
        path: 'admin/recycle',
        name: 'AdminRecycle',
        component: () => import('../views/Recycle.vue'),
        meta: { title: '回收站', roles: ['admin'] }
      },
      {
        path: 'admin/logs',
        name: 'OperationLog',
        component: () => import('../views/OperationLog.vue'),
        meta: { title: '操作日志', roles: ['admin'] }
      },
      {
        path: 'admin/reviews',
        name: 'ReviewManage',
        component: () => import('../views/ReviewManage.vue'),
        meta: { title: '举报审核', roles: ['admin'] }
      },
      {
        // 钱包管理：用户余额、手动调整、对账（H5）
        path: 'admin/wallets',
        name: 'WalletManage',
        component: () => import('../views/WalletManage.vue'),
        meta: { title: '钱包管理', roles: ['admin'] }
      },
      {
        path: 'admin/demo',
        name: 'DemoData',
        component: () => import('../views/DemoData.vue'),
        meta: { title: '演示数据', roles: ['admin'] }
      },
      {
        path: 'admin/announcements',
        name: 'AnnouncementManage',
        component: () => import('../views/AnnouncementManage.vue'),
        meta: { title: '公告管理', roles: ['admin'] }
      },

      // ---- 普通用户专属 ----
      {
        path: 'my/profile',
        name: 'UserProfile',
        component: () => import('../views/UserProfile.vue'),
        meta: { title: '个人中心', roles: ['user'] }
      },
      {
        path: 'my/dashboard',
        name: 'MyDashboard',
        component: () => import('../views/MyDashboard.vue'),
        meta: { title: '数据看板', roles: ['user', 'admin'] }
      },
      {
        path: 'my/surveys',
        name: 'MySurveys',
        component: () => import('../views/MySurveys.vue'),
        meta: { title: '我的问卷', roles: ['user', 'admin'] }
      },
      {
        path: 'my/surveys/:id/statistics',
        name: 'SurveyStatistics',
        component: () => import('../views/SurveyStatistics.vue'),
        meta: { title: '问卷统计', roles: ['user', 'admin'] }
      },
      {
        path: 'my/recycle',
        name: 'MyRecycle',
        component: () => import('../views/Recycle.vue'),
        meta: { title: '回收站', roles: ['user', 'admin'] }
      },
      {
        // 我的答卷 / 答题记录：只列"登录态提交过"的问卷（匿名答卷没有身份可回溯）
        path: 'my/answers',
        name: 'MyAnswers',
        component: () => import('../views/MyAnswers.vue'),
        meta: { title: '我的答卷', roles: ['user', 'admin'] }
      },
      {
        // 我的钱包（H5）：普通用户看余额/流水/模拟充值；管理员也可查看自己的钱包
        path: 'my/wallet',
        name: 'MyWallet',
        component: () => import('../views/MyWallet.vue'),
        meta: { title: '我的钱包', roles: ['user', 'admin'] }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 路由守卫 —— 前端权限的「体验层」。
 *
 * ⚠️ 重要认知：前端的角色判断**不可信**（localStorage 是明文、可随意修改），
 *    它只负责"别把不该看到的菜单显示出来"，真正的权限防线全在后端
 *    （JwtAuthFilter 校验 token，各接口再做归属/角色校验）。
 *
 * ★ 这里必须守住四条不变量：
 *   1. 登录态只能走 utils/auth.getUser()：直接 `JSON.parse(localStorage.getItem('user'))`
 *      没有 try-catch，值被写坏时守卫抛异常 → **整个应用白屏**，连登录页都进不去。
 *   2. 放行条件是 isLoggedIn()（token 与 user **同时存在**），不能只看 user：
 *      若 token 已失效而 user 还在，用户能进页面但每个请求都 401、然后再被踢回登录页。
 *   3. 已登录用户访问 /login、/register 时直接回自己的首页，
 *      否则"登录成功后又手滑回到登录页"。
 *   4. 无权限时跳转的目标路径统一由 auth.homePathFor() 计算，不要两处各写一遍三元表达式。
 */
router.beforeEach((to, from, next) => {
  const loggedIn = isLoggedIn()
  const user = getUser()

  // 1. 已登录还想去登录/注册页 → 直接回自己的首页
  if (to.meta.public && loggedIn && (to.name === 'Login' || to.name === 'Register')) {
    next(homePathFor(user.role))
    return
  }

  // 2. 其它公开页（如匿名答题 /s/:shareCode）直接放行
  if (to.meta.public) {
    next()
    return
  }

  // 3. 未登录 / 登录态不完整（有 user 没 token 等）→ 跳登录
  if (!loggedIn) {
    next('/login')
    return
  }

  // 4. 角色校验：meta.roles 不含当前角色就是没权限，跳回各自的首页
  const allowedRoles = to.meta.roles
  if (allowedRoles && !allowedRoles.includes(user.role)) {
    next(homePathFor(user.role))
    return
  }

  // 5. 权限点校验（H2 新增）：路由写 meta.permission 时按权限点判，同样跳回首页。
  //    ★ 与 roles 并存：roles 管"管理端/用户端"这种粗粒度，permission 管"这项能力"。
  //    ★ 前端判断只防误入，真正的拦截在后端（@RequirePermission）。
  if (to.meta.permission && !hasPermission(to.meta.permission)) {
    next(homePathFor(user.role))
    return
  }

  next()
})

export default router