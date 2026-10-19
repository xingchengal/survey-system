/**
 * 路由表与全局守卫：29 条具名路由。
 * ★ 守卫顺序固定：meta.public → 登录态 → meta.roles + meta.permission，顺序错了会放错人。
 */

import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../layouts/AdminLayout.vue'
// 登录态统一从 utils/auth 读，不要直接 JSON.parse localStorage（坏值会让守卫抛异常白屏）
import { getUser, isLoggedIn, homePathFor, hasPermission } from '../utils/auth'

/** 管理端人员（超管 + 次级管理员）；具体模块还要看 meta.permission */
const STAFF = ['admin', 'subadmin']

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
    // 公开结果页：作者开启「公开结果」后匿名可访问。
    // 漏加 public 会先被守卫踢去登录页，而结果页本来就不需要账号。
    path: '/r/:shareCode',
    name: 'PublicResult',
    component: () => import('../views/PublicResult.vue'),
    meta: { title: '公开结果', public: true }
  },

  // ===== 编辑器：独立全屏布局（不套 AdminLayout）=====
  // 题型面板要占整个网页最左，套进 AdminLayout 的 el-main 就做不到；
  // 这两个路由放在顶层，由 SurveyEdit 自己渲染左右两栏。
  {
    path: '/my/surveys/:id/edit',
    name: 'SurveyEdit',
    component: () => import('../views/SurveyEdit.vue'),
    meta: { title: '编辑问卷', roles: ['user', 'admin', 'subadmin'] }
  },
  // 模板编辑：除 STAFF 外还要 template:manage
  {
    path: '/admin/templates/:id/edit',
    name: 'TemplateEdit',
    component: () => import('../views/SurveyEdit.vue'),
    meta: { title: '编辑模板', roles: STAFF, permission: 'template:manage', fromTemplate: true }
  },

  // ===== 后台页面 =====
  {
    path: '/',
    component: AdminLayout,
    // 访问根路径时按角色分流；★ 必须走 utils/auth.getUser()：裸 JSON.parse 遇坏值会让访问 / 直接白屏
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
        meta: { title: '个人中心', roles: STAFF }
      },
      {
        // 超管专属：选定/授权/降级次级管理员
        path: 'admin/sub-admins',
        name: 'SubAdminManage',
        component: () => import('../views/SubAdminManage.vue'),
        meta: { title: '次级管理员', roles: ['admin'] }
      },
      {
        // 总览对 staff 全开：次级管理员没有 system:view 也能进，字段由后端按模块权限裁剪
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '系统总览', roles: STAFF }
      },
      {
        path: 'users',
        name: 'UserManage',
        component: () => import('../views/UserManage.vue'),
        meta: { title: '用户管理', roles: STAFF, permission: 'user:manage' }
      },
      {
        path: 'admin/surveys',
        name: 'AdminSurveyList',
        component: () => import('../views/AdminSurveyList.vue'),
        meta: { title: '全部问卷', roles: STAFF, permission: 'survey:manage' }
      },
      {
        // 模板管理：维护 is_template = 1 的问卷
        path: 'admin/templates',
        name: 'TemplateManage',
        component: () => import('../views/TemplateManage.vue'),
        meta: { title: '模板管理', roles: STAFF, permission: 'template:manage' }
      },
      {
        // 统计页对 staff 全开：没有 system:view 也能进，模块由后端按权限裁剪
        path: 'statistics',
        name: 'SystemStatistics',
        component: () => import('../views/SystemStatistics.vue'),
        meta: { title: '系统统计', roles: STAFF }
      },
      {
        path: 'admin/recycle',
        name: 'AdminRecycle',
        component: () => import('../views/Recycle.vue'),
        meta: { title: '回收站', roles: STAFF, permission: 'survey:manage' }
      },
      {
        path: 'admin/logs',
        name: 'OperationLog',
        component: () => import('../views/OperationLog.vue'),
        meta: { title: '操作日志', roles: STAFF, permission: 'log:view' }
      },
      {
        path: 'admin/reviews',
        name: 'ReviewManage',
        component: () => import('../views/ReviewManage.vue'),
        meta: { title: '举报审核', roles: STAFF, permission: 'review:handle' }
      },
      {
        // 钱包管理：余额、手动调整、对账
        path: 'admin/wallets',
        name: 'WalletManage',
        component: () => import('../views/WalletManage.vue'),
        meta: { title: '钱包管理', roles: STAFF, permission: 'wallet:manage' }
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
        meta: { title: '公告管理', roles: STAFF, permission: 'announcement:manage' }
      },
      {
        // 反馈 / 客服：管理侧只认 feedback:handle（用户侧页面不在这里）
        path: 'admin/feedback',
        name: 'FeedbackManage',
        component: () => import('../views/FeedbackManage.vue'),
        meta: { title: '反馈管理', roles: STAFF, permission: 'feedback:handle' }
      },

      // ---- 普通用户专属 ----
      {
        // 意见反馈：所有登录用户都能提交与查看自己的 FEEDBACK（客服在 /support）
        path: 'feedback',
        name: 'Feedback',
        component: () => import('../views/Feedback.vue'),
        meta: { title: '意见反馈', roles: ['user', 'admin', 'subadmin'] }
      },
      {
        // 客服中心：帮助中心 + 在线客服，只列 SERVICE；与反馈共用同一套工单线程
        path: 'support',
        name: 'Support',
        component: () => import('../views/Support.vue'),
        meta: { title: '客服中心', roles: ['user', 'admin', 'subadmin'] }
      },
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
        // 我的答卷只列登录态提交过的问卷（匿名答卷没有身份可回溯）
        path: 'my/answers',
        name: 'MyAnswers',
        component: () => import('../views/MyAnswers.vue'),
        meta: { title: '我的答卷', roles: ['user', 'admin'] }
      },
      {
        // 我的钱包：余额/流水/充值，管理员也可看自己的
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
 * 全局守卫 —— 前端权限只做体验层（localStorage 可改），真正防线在后端。
 * 无权限统一 next(homePathFor(role))；登录态用 isLoggedIn()（token 与 user 必须同时存在）。
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

  // 5. 权限点校验：meta.permission 不通过同样回首页（roles 管粗粒度，permission 管具体能力）
  if (to.meta.permission && !hasPermission(to.meta.permission)) {
    next(homePathFor(user.role))
    return
  }

  next()
})

export default router