import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../layouts/AdminLayout.vue'

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

  // ===== 后台页面 =====
  {
    path: '/',
    component: AdminLayout,
    redirect: () => {
      const userStr = localStorage.getItem('user')
      if (!userStr) return '/login'
      const user = JSON.parse(userStr)
      return user.role === 'admin' ? '/dashboard' : '/my/dashboard'
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
        path: 'my/surveys/:id/edit',
        name: 'SurveyEdit',
        component: () => import('../views/SurveyEdit.vue'),
        meta: { title: '编辑问卷', roles: ['user', 'admin'] }
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
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
  const userStr = localStorage.getItem('user')
  const user = userStr ? JSON.parse(userStr) : null

  // 公开页直接放行
  if (to.meta.public) {
    next()
    return
  }

  // 未登录 → 跳登录
  if (!user) {
    next('/login')
    return
  }

  // 权限校验
  const allowedRoles = to.meta.roles
  if (allowedRoles && !allowedRoles.includes(user.role)) {
    // 没权限 → 跳回各自首页
    next(user.role === 'admin' ? '/dashboard' : '/my/dashboard')
    return
  }

  next()
})

export default router