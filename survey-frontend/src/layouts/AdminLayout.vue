<template>
  <el-container class="app-shell">
    <!-- 侧边栏（<1100px 收窄成图标条，<768px 变抽屉） -->
    <el-aside width="232px" class="app-sidebar" :class="{ 'is-open': drawerOpen }">
      <!-- Logo -->
      <div class="app-logo">
        <BrandLogo class="logo-mark" :size="34" />
        <span class="logo-text">问卷系统</span>
        <span class="logo-badge">v2.2</span>
      </div>

      <!-- 菜单（可滚动） -->
      <div class="app-menu">
        <el-menu
          :default-active="$route.path"
          router
          background-color="transparent"
          text-color="#b9c7e6"
          active-text-color="#ffffff"
        >
          <!-- ===== 管理端菜单（超管 + 次级管理员；按权限点过滤） ===== -->
          <template v-if="isStaff">
            <el-menu-item index="/dashboard">
              <el-icon><DataBoard /></el-icon>
              <span>系统总览</span>
            </el-menu-item>
            <el-menu-item v-if="can('user:manage')" index="/users">
              <el-icon><UserFilled /></el-icon>
              <span>用户管理</span>
            </el-menu-item>
            <el-menu-item v-if="can('survey:manage')" index="/admin/surveys">
              <el-icon><Document /></el-icon>
              <span>全部问卷</span>
            </el-menu-item>
            <el-menu-item v-if="can('template:manage')" index="/admin/templates">
              <el-icon><DocumentCopy /></el-icon>
              <span>模板管理</span>
            </el-menu-item>
            <el-menu-item index="/statistics">
              <el-icon><PieChart /></el-icon>
              <span>系统统计</span>
            </el-menu-item>
            <el-menu-item v-if="can('survey:manage')" index="/admin/recycle">
              <el-icon><Delete /></el-icon>
              <span>回收站</span>
            </el-menu-item>
            <el-menu-item v-if="can('announcement:manage')" index="/admin/announcements">
              <el-icon><Bell /></el-icon>
              <span>公告管理</span>
            </el-menu-item>
            <el-menu-item v-if="can('feedback:handle')" index="/admin/feedback">
              <el-badge :value="feedbackUnread" :hidden="feedbackUnread === 0" :max="99" class="menu-badge">
                <el-icon><ChatDotRound /></el-icon>
              </el-badge>
              <span>反馈管理</span>
            </el-menu-item>
            <el-menu-item v-if="can('log:view')" index="/admin/logs">
              <el-icon><Memo /></el-icon>
              <span>操作日志</span>
            </el-menu-item>
            <el-menu-item v-if="can('review:handle')" index="/admin/reviews">
              <el-icon><Warning /></el-icon>
              <span>举报审核</span>
            </el-menu-item>
            <el-menu-item v-if="can('wallet:manage')" index="/admin/wallets">
              <el-icon><Wallet /></el-icon>
              <span>钱包管理</span>
            </el-menu-item>
            <!-- 超管专属 -->
            <el-menu-item v-if="isSuper" index="/admin/sub-admins">
              <el-icon><Avatar /></el-icon>
              <span>次级管理员</span>
            </el-menu-item>
            <el-menu-item v-if="isSuper" index="/admin/demo">
              <el-icon><MagicStick /></el-icon>
              <span>演示数据</span>
            </el-menu-item>
          </template>

          <!-- ===== 普通用户菜单 ===== -->
          <template v-else>
            <el-menu-item index="/my/dashboard">
              <el-icon><DataBoard /></el-icon>
              <span>数据看板</span>
            </el-menu-item>
            <el-menu-item index="/my/surveys">
              <el-icon><Document /></el-icon>
              <span>我的问卷</span>
            </el-menu-item>
            <el-menu-item index="/my/answers">
              <el-icon><Memo /></el-icon>
              <span>我的答卷</span>
            </el-menu-item>
            <el-menu-item index="/my/recycle">
              <el-icon><Delete /></el-icon>
              <span>回收站</span>
            </el-menu-item>
            <el-menu-item index="/my/wallet">
              <el-icon><Wallet /></el-icon>
              <span>我的钱包</span>
            </el-menu-item>
            <el-menu-item index="/support">
              <el-icon><Service /></el-icon>
              <span>客服中心</span>
            </el-menu-item>
          </template>
        </el-menu>
      </div>

      <!-- 底部：接口文档按钮（仅管理员） -->
      <div v-if="isSuper" class="api-doc-footer">
        <div class="api-doc-btn" @click="openApiDoc">
          <div class="api-doc-btn-inner">
            <el-icon class="api-doc-icon"><DocumentCopy /></el-icon>
            <div class="api-doc-text">
              <div class="api-doc-title">接口文档</div>
              <div class="api-doc-sub">Knife4j · OpenAPI</div>
            </div>
            <el-icon class="api-doc-arrow"><TopRight /></el-icon>
          </div>
        </div>
      </div>
    </el-aside>

    <!-- 移动端抽屉遮罩：点空白处收起侧栏 -->
    <div v-if="drawerOpen" class="sidebar-mask" @click="drawerOpen = false" />

    <!-- 右侧区域 -->
    <el-container>
      <el-header class="app-header">
        <!-- 顶栏只做"你在哪"的导航：首页 / 当前页；页面大标题由内容区的 .page-title 负责 -->
        <div class="app-header-left">
          <button
            type="button"
            class="hamburger"
            :title="drawerOpen ? '收起菜单' : '展开菜单'"
            @click="drawerOpen = !drawerOpen"
          >
            <el-icon :size="20"><Fold v-if="drawerOpen" /><Expand v-else /></el-icon>
          </button>

          <el-breadcrumb separator="/" class="app-breadcrumb">
            <el-breadcrumb-item :to="{ path: homePath }">
              <span class="crumb-home">
                <el-icon><HomeFilled /></el-icon>
                <span class="crumb-home-text">首页</span>
              </span>
            </el-breadcrumb-item>
            <el-breadcrumb-item>{{ $route.meta.title || '后台管理' }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="app-header-right">
          <!-- 主题切换（浅色 / 深色）：偏好存在 localStorage，刷新后由 main.js 恢复 -->
          <el-tooltip
            :content="isDark ? '切换到浅色' : '切换到深色'"
            placement="bottom"
            :show-after="300"
          >
            <button
              type="button"
              class="theme-toggle"
              :aria-label="isDark ? '切换到浅色' : '切换到深色'"
              @click="handleToggleTheme"
            >
              <el-icon :size="18"><Sunny v-if="isDark" /><Moon v-else /></el-icon>
            </button>
          </el-tooltip>

          <!-- ==================== 通知铃铛 ==================== -->
          <el-popover
            v-model:visible="notifyVisible"
            placement="bottom-end"
            :width="380"
            trigger="click"
            @show="loadNotifications"
          >
            <template #reference>
              <div class="bell-wrapper">
                <el-tooltip content="通知中心" placement="bottom" :show-after="300">
                  <el-badge :value="unreadCount > 0 ? unreadCount : ''" :hidden="unreadCount === 0" :max="99">
                    <el-icon :size="20" class="bell-icon"><Bell /></el-icon>
                  </el-badge>
                </el-tooltip>
              </div>
            </template>

            <div class="notify-panel">
              <div class="notify-head">
                <span class="text-bold">消息中心</span>
                <div>
                  <el-button type="primary" link size="small" @click="markAllRead" :disabled="unreadCount === 0">全部已读</el-button>
                  <el-button type="danger" link size="small" @click="clearAll" :disabled="notifications.length === 0">清空</el-button>
                </div>
              </div>

              <div class="notify-list">
                <div
                  v-for="n in notifications"
                  :key="n.id"
                  class="notify-item"
                  :class="{ 'notify-unread': n.isRead === 0 }"
                  @click="handleNotifyClick(n)"
                >
                  <div class="notify-icon" :class="'notify-' + n.type">
                    <el-icon v-if="n.type === 'answer'"><ChatDotRound /></el-icon>
                    <el-icon v-else-if="n.type === 'announcement'"><BellFilled /></el-icon>
                    <el-icon v-else><InfoFilled /></el-icon>
                  </div>
                  <div class="notify-content">
                    <div class="notify-title">{{ n.title }}</div>
                    <div class="notify-text">{{ n.content }}</div>
                    <div class="notify-time">{{ n.createTime }}</div>
                  </div>
                  <el-icon class="notify-delete" @click.stop="deleteNotify(n)"><Close /></el-icon>
                </div>

                <el-empty v-if="notifications.length === 0" description="暂无消息" :image-size="60" />
              </div>
            </div>
          </el-popover>

          <!-- 意见反馈：常驻图标，入口与客服中心分开 -->
          <el-tooltip content="意见反馈" placement="bottom" :show-after="300">
            <button
              type="button"
              class="header-icon-btn"
              :class="{ 'is-active': $route.path === '/feedback' }"
              aria-label="意见反馈"
              @click="goFeedback"
            >
              <el-icon :size="18"><EditPen /></el-icon>
            </button>
          </el-tooltip>

          <!-- ==================== 用户下拉 ==================== -->
          <el-dropdown @command="handleCommand">
            <span class="user-chip">
              <el-avatar
                :size="34"
                :src="currentUser.avatar"
                class="user-avatar"
              >
                {{ avatarText }}
              </el-avatar>
              <span class="user-name">{{ displayName }}</span>
              <!-- 只给管理员挂角色标签：「用户」这个标签对普通用户没有信息量，只是占位 -->
              <el-tag v-if="isStaff" type="danger" size="small" effect="light" class="user-role">{{ isSuper ? '超级管理员' : '次级管理员' }}</el-tag>
              <el-icon class="user-caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <el-icon><User /></el-icon> 个人中心
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 公告弹窗（仅普通用户，登录后展示一次） -->
    <el-dialog
      v-model="announcementVisible"
      title="📢 系统公告"
      width="520px"
      :close-on-click-modal="false"
    >
      <div v-if="currentAnnouncement">
        <h3 class="announcement-title">{{ currentAnnouncement.title }}</h3>
        <div class="announcement-body">
          {{ currentAnnouncement.content }}
        </div>
        <div class="announcement-time">
          发布于 {{ currentAnnouncement.createTime }}
        </div>
      </div>
      <template #footer>
        <el-button type="primary" @click="announcementVisible = false">我知道了</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script>
import {
  DataBoard, Document, PieChart, ArrowDown, UserFilled, Delete,
  DocumentCopy, TopRight, User, SwitchButton, Memo, MagicStick, Bell,
  ChatDotRound, BellFilled, InfoFilled, Close, Wallet, Avatar, Service, EditPen
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
// ★ 统一走接口层：不要在本布局里自己 fetch —— http.js 统一带 Authorization、
//   统一判成败、统一弹错误提示，页面只管拿数据。
//   路径说明：本文件在 src/layouts/ 下，api 在 src/api/，所以是 '../api'。
import api from '../api'
// ★ 登录态统一从 utils/auth 读：避免裸 JSON.parse 遇到脏数据时抛异常，
//   把整个后台布局（含侧边栏和顶栏）都渲染崩掉。
import { getUser, clearAuth, homePathFor, getRefreshToken, hasPermission } from '../utils/auth'
import { toggleTheme } from '../utils/theme'
// 品牌标识（替换原来的 emoji，与登录/注册页、favicon 同一图形）
import BrandLogo from '../components/BrandLogo.vue'

export default {
  components: {
    BrandLogo,
    DataBoard, Document, PieChart, ArrowDown, UserFilled, Delete,
    DocumentCopy, TopRight, User, SwitchButton, Memo, MagicStick, Bell,
    ChatDotRound, BellFilled, InfoFilled, Close, Wallet, Avatar, Service, EditPen
  },
  data() {
    return {
      // ★ 用 getUser()：直接 JSON.parse(localStorage...) 遇到脏数据会抛异常
      currentUser: getUser() || {},
      /** 当前是否深色主题（只用于切换按钮的图标方向） */
      isDark: document.documentElement.dataset.theme === 'dark',
      /** <768px 时侧栏变抽屉：true = 抽屉展开（桌面端无影响） */
      drawerOpen: false,
      // 公告相关
      announcementVisible: false,
      currentAnnouncement: null,
      // 通知相关
      notifications: [],
      unreadCount: 0,
      notifyTimer: null,
      /** 消息中心浮层：点「意见反馈」后手动收起 */
      notifyVisible: false,
      // 反馈管理菜单红点（60s 轮询）
      feedbackUnread: 0,
      feedbackTimer: null
    }
  },
  computed: {
    /** 管理端人员：超管 + 次级管理员（菜单外壳共用；具体模块看权限点） */
    isStaff() {
      return this.currentUser.role === 'admin' || this.currentUser.role === 'subadmin'
    },
    /** 超级管理员：只有他能进「次级管理员 / 演示数据 / 接口文档」 */
    isSuper() {
      return this.currentUser.role === 'admin'
    },
    /** 面包屑「首页」的落点：管理员去系统总览/工作台，普通用户去数据看板 */
    homePath() {
      return homePathFor(this.currentUser.role)
    },
    displayName() {
      return this.currentUser.nickname || this.currentUser.username || '用户'
    },
    avatarText() {
      const name = this.displayName
      return name ? name.charAt(0) : '用'
    }
  },
  watch: {
    // 监听路由变化，从个人中心返回时刷新用户信息（保证头像、昵称实时更新）
    // ★ 必须用 getUser()：裸 JSON.parse 遇到脏数据会让回调抛异常
    //   （watch 放在 mounted 之前，是 ESLint 的 vue/order-in-components 规则要求）
    $route() {
      this.currentUser = getUser() || {}
      // 移动端：点菜单跳页后把抽屉收起
      this.drawerOpen = false
      // ★ 切页后把内容区滚回顶部：内容区是独立滚动容器（.app-main），
      //   浏览器/热更新会保留上一次的 scrollTop，表现为"新页面标题被顶栏裁掉一截"。
      this.$nextTick(this.resetMainScroll)
    }
  },
  mounted() {
    // 每次进入布局页，重新读取最新的用户信息
    // ★ 用 getUser() 而不是裸 JSON.parse：脏数据不会让布局/守卫抛异常
    this.currentUser = getUser() || {}
    // 只有普通用户才加载公告弹窗
    if (!this.isStaff) {
      this.loadAnnouncements()
    }
    // 加载未读数
    this.loadUnreadCount()
    // 每 30 秒刷新一次未读
    this.notifyTimer = setInterval(this.loadUnreadCount, 30000)
    // 反馈管理红点：60 秒轮询（只有被授予 feedback:handle 才需要）
    if (this.can('feedback:handle')) {
      this.loadFeedbackUnread()
      this.feedbackTimer = setInterval(this.loadFeedbackUnread, 60000)
    }
    // 首次进入也归零：刷新/热更新可能恢复上一次的滚动位置
    this.$nextTick(this.resetMainScroll)
  },
  beforeUnmount() {
    // 组件卸载时清掉 30 秒轮询未读数的定时器，避免离开后台后还在请求
    if (this.notifyTimer) clearInterval(this.notifyTimer)
    if (this.feedbackTimer) clearInterval(this.feedbackTimer)
  },
  methods: {
    /** 菜单/按钮显隐用的权限判断（后端才是防线；超管天然全有） */
    can(permission) {
      return this.isSuper || hasPermission(permission)
    },

    /** 切换浅色 / 深色主题 */
    handleToggleTheme() {
      this.isDark = toggleTheme() === 'dark'
      // 通知图表页重画（canvas 读不到 CSS 变量，必须按新主题重新 init）
      window.dispatchEvent(new CustomEvent('theme-change'))
    },

    /** 把右侧内容区滚回顶部（内容区自己滚动，不是 window） */
    resetMainScroll() {
      const main = this.$el && this.$el.querySelector ? this.$el.querySelector('.app-main') : null
      if (main) main.scrollTop = 0
    },

    async handleCommand(command) {
      if (command === 'logout') {
        // ★ 先调后端接口，清 Cookie。
        //   api.user.logout(refreshToken) 内部是 http.post(..., { silent: true })：
        //   silent 表示「不自动弹错误提示、不自动跳登录」，因为退出登录本身就是离开本页，
        //   而且未登录时调它会 401，绝不能触发「登录已过期 → 跳 /login」那一套。
        //
        // ★ **必须把 refreshToken 传进去**：后端按它精确吊销"这一枚"。
        //   不传的话后端只能走 revokeAllByUser（吊销该用户全部 refresh token）——
        //   结果不算坏，但签名与调用不一致，且将来改成"只吊销本设备"时会变成静默的越权吊销。
        //   （这处原来就是漏传的，B5 已修。）
        try {
          await api.user.logout(getRefreshToken())
        } catch {
          // 忽略错误：退出接口失败也必须让用户走掉，下面照常清本地登录态
        }
        // ★ 清本地登录态必须走 utils/auth 的 clearAuth：它把 token / refreshToken / user
        //   三个 key 一起清 —— 手写 removeItem 很容易漏掉 refreshToken，
        //   漏了就会出现"退出登录后刷新页面又自动登录"。
        clearAuth()
        this.$router.push('/login')
      } else if (command === 'profile') {
        if (this.isStaff) {
          this.$router.push('/admin/profile')
        } else {
          this.$router.push('/my/profile')
        }
      }
    },

    openApiDoc() {
      // ★ 打开 Knife4j 接口文档。
      //   开发环境：前端跑在 5173、后端在 8080，是两个"源"，必须写完整地址；
      //   生产环境：前后端同源（dist 拷进后端 static），用相对路径即可 ——
      //             写死 http://localhost:8080 部署到服务器后就打不开了。
      //   另外文档只有管理员能看：后端 DocAccessFilter 会校验 doc_token Cookie。
      const docUrl = import.meta.env.DEV ? 'http://localhost:8080/doc.html' : '/doc.html'
      window.open(docUrl, '_blank')
    },

    // 加载未过期公告，普通用户登录后弹一次
    // ★ api.announcement.active() 直接把响应体（公告数组）resolve 出来，
    //   res.json() 这一步由 http.js 统一做了；它是 silent 的
    //   （登录页也会调，未登录不该弹红字）。
    async loadAnnouncements() {
      try {
        if (this.isStaff) return

        const list = await api.announcement.active()
        if (!list || list.length === 0) return

        // 按用户ID隔离已读记录
        const userId = this.currentUser.id
        const storageKey = `readAnnouncements_${userId}`
        // ★ 这个键存的是"已读公告 id 数组"，不是 user，所以不能复用 auth.getUser()，
        //   必须在这里单独兜底：**裸 parse 不能省掉 try-catch** —— 值一旦写坏
        //   （手工改过 / 旧版本格式），异常会冒到渲染流程，把整个后台壳打崩。
        let readIds = []
        try {
          const raw = localStorage.getItem(storageKey)
          const parsed = raw ? JSON.parse(raw) : []
          readIds = Array.isArray(parsed) ? parsed : []
        } catch {
          localStorage.removeItem(storageKey)   // 脏值直接清掉，下次当作"都没读过"
        }
        const unread = list.filter(a => !readIds.includes(a.id))
        if (unread.length === 0) return

        this.currentAnnouncement = unread[0]
        this.announcementVisible = true

        const newReadIds = [...readIds, ...unread.map(a => a.id)]
        localStorage.setItem(storageKey, JSON.stringify(newReadIds))
      } catch (e) {
        console.warn('公告加载失败', e)
      }
    },

    // ==================== 通知相关 ====================
    // ★ 下面 6 个通知方法统一走 api.notification.*，方法名与后端一一对应
    //   （unread / list / read / readAll / remove / clear）。
    //   ⚠️ 30 秒轮询的节奏（mounted 里的 setInterval(this.loadUnreadCount, 30000)）和
    //      铃铛红点、列表的 DOM 结构都**不要动**。
    async loadUnreadCount() {
      // api.notification.unread() 内部就是 silent：轮询失败不会弹提示、不会踢去登录页，
      // 所以 catch 里什么都不做。
      try {
        const data = await api.notification.unread()
        this.unreadCount = data.count || 0
      } catch { /* 忽略错误：轮询失败不该打扰用户 */ }
    },

    /** 反馈管理菜单红点：辅助信息，失败静默 */
    async loadFeedbackUnread() {
      try {
        const data = await api.feedback.unread()
        this.feedbackUnread = data.adminUnread || 0
      } catch { /* 红点失败不影响页面 */ }
    },

    async loadNotifications() {
      // 不要在 catch 里再弹 ElMessage.error：http.js 在非 2xx 时已经弹过后端的 message，
      // 再弹一次会变成两条红字。
      try {
        this.notifications = await api.notification.list()
      } catch {
        // 错误提示由 http.js 统一负责，这里只做流程控制（保持列表为上一次的数据）
      }
    },

    async handleNotifyClick(n) {
      if (n.isRead === 0) {
        // ★ 必须 try/catch：标记已读失败会抛异常（后端 NotificationController 失败时
        //   throw BizException → 400 + message，http.js 弹红字并抛 ApiError）。
        //   若不挡，就会变成未捕获 rejection，而且**下面的跳转不会执行**。
        try {
          await api.notification.read(n.id)
          n.isRead = 1
          this.loadUnreadCount()
        } catch {
          // 提示已由接口层弹出；已读状态保持未读，用户仍可以点进详情
        }
      }
      if (n.link) {
        this.notifyVisible = false
        this.$router.push(n.link)
      }
    },

    /** 顶栏图标 → 意见反馈（反馈是意见箱，不占侧栏菜单） */
    goFeedback() {
      this.notifyVisible = false
      if (this.$route.path !== '/feedback') this.$router.push('/feedback')
    },

    async markAllRead() {
      // 对应 PUT /api/notification/read-all（api.notification.readAll() 已封装）。
      try {
        await api.notification.readAll()
        this.notifications.forEach(n => n.isRead = 1)
        this.unreadCount = 0
        ElMessage.success('已全部标记为已读')
      } catch {
        // 失败提示由接口层弹出，本地状态不改（列表仍显示未读）
      }
    },

    async deleteNotify(n) {
      // 对应 DELETE /api/notification/{id}；注意 api 里叫 remove。
      try {
        await api.notification.remove(n.id)
        this.notifications = this.notifications.filter(item => item.id !== n.id)
        this.loadUnreadCount()
      } catch {
        // 删不掉就不动列表（否则会出现"界面上没了、刷新又回来"）
      }
    },

    async clearAll() {
      // 对应 DELETE /api/notification/clear（清空全部）。
      try {
        await api.notification.clear()
        this.notifications = []
        this.unreadCount = 0
        ElMessage.success('已清空所有通知')
      } catch {
        // 失败提示由接口层弹出，列表保持原样
      }
    }
  }
}
</script>

<style>
/* ==================== 外壳 ==================== */
.app-shell {
  height: 100vh;
  background: var(--surface-2);
}

/* ---------- 侧边栏 ---------- */
.app-sidebar {
  position: relative;
  display: flex;
  flex-direction: column;
  color: #fff;
  background:
    radial-gradient(120% 60% at 0% 0%, rgba(86, 204, 242, 0.22) 0%, transparent 60%),
    linear-gradient(180deg, #0b1b3a 0%, #0a2540 55%, #071a2e 100%);
  box-shadow: 4px 0 24px rgba(7, 26, 46, 0.18);
  overflow: hidden;
}
.app-sidebar::after {
  content: '';
  position: absolute;
  left: -40%;
  right: -40%;
  bottom: -40%;
  height: 300px;
  background: radial-gradient(closest-side, rgba(47, 128, 237, 0.28), transparent);
  pointer-events: none;
}

.app-logo {
  height: 68px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  flex-shrink: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.07);
  position: relative;
  z-index: 1;
}
.logo-mark {
  flex-shrink: 0;
  filter: drop-shadow(0 6px 14px rgba(47, 128, 237, 0.45));
}
.logo-text {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.3px;
}
.logo-badge {
  margin-left: auto;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 6px;
  color: #9ed0ff;
  background: rgba(47, 128, 237, 0.18);
  border: 1px solid rgba(158, 208, 255, 0.25);
}

.app-menu {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 10px 0 6px;
  position: relative;
  z-index: 1;
}

/* ===== 侧边栏菜单 ===== */
.el-menu {
  border-right: none !important;
  background: transparent;
}

.el-menu-item {
  position: relative;
  height: 46px;
  line-height: 46px;
  margin: 4px 14px;
  border-radius: 12px;
  transition: all 0.2s var(--ease);
}

.el-menu-item .el-icon {
  font-size: 18px;
  margin-right: 10px;
}

/* 反馈管理菜单红点：贴着图标右上角，不撑高菜单行 */
.menu-badge :deep(.el-badge__content) {
  top: 4px;
  right: 6px;
}

.el-menu-item:hover {
  background: rgba(255, 255, 255, 0.07) !important;
  color: #fff !important;
  transform: translateX(2px);
}

.el-menu-item.is-active {
  /* 侧栏选中也属于"小尺寸蓝色元件"：用弱渐变，避免胶囊左上角出现一条亮青色边 */
  background: var(--btn-gradient) !important;
  color: #fff !important;
  box-shadow: 0 8px 20px rgba(47, 128, 237, 0.42);
}

.el-menu-item.is-active::before {
  content: '';
  position: absolute;
  left: -14px;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 22px;
  border-radius: 0 4px 4px 0;
  background: #9ed0ff;
}

/* ---------- 顶栏 ---------- */
.app-header {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--stroke);
  position: relative;
  z-index: 5;
}
.app-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

/* 顶栏面包屑：字号/颜色都比内容区的 .page-title 弱，形成"导航 vs 标题"的层级 */
.app-breadcrumb {
  display: flex;
  align-items: center;
}
.app-breadcrumb .el-breadcrumb__inner {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  font-weight: 400;
  color: var(--ink-3);
  transition: color 0.18s var(--ease);
}
.app-breadcrumb .el-breadcrumb__inner.is-link:hover {
  color: var(--sky-600);
}
.app-breadcrumb .el-breadcrumb__item:last-child .el-breadcrumb__inner {
  color: var(--ink-2);
  font-weight: 600;
}
.app-breadcrumb .el-breadcrumb__separator {
  color: var(--ink-3);
  font-weight: 400;
}
.crumb-home {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.crumb-home .el-icon {
  font-size: 15px;
}

/* 移动端汉堡按钮（桌面端隐藏） */
.hamburger {
  display: none;
  width: 38px;
  height: 38px;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  transition:
    background 0.18s var(--ease),
    color 0.18s var(--ease);
}
.hamburger:hover {
  background: var(--sky-50);
  color: var(--sky-600);
}
.sidebar-mask {
  display: none;
}
.app-header-right {
  display: flex;
  align-items: center;
  height: 100%;
  gap: 6px;
}
.bell-icon {
  color: var(--ink-2);
  cursor: pointer;
  transition:
    color 0.18s var(--ease),
    transform 0.18s var(--ease);
}
.bell-wrapper:hover .bell-icon {
  color: var(--sky-600);
  transform: rotate(-8deg);
}

.theme-toggle {
  width: 38px;
  height: 38px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    background 0.18s var(--ease),
    color 0.18s var(--ease),
    transform 0.18s var(--ease);
}
.theme-toggle:hover {
  background: var(--sky-50);
  color: var(--sky-600);
  transform: rotate(-12deg);
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 44px;
  padding: 0 12px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.2s var(--ease);
}
.user-chip:hover {
  background: var(--sky-50);
}
.user-avatar {
  background: var(--brand-gradient);
  color: #fff;
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(47, 128, 237, 0.3);
}
.user-name {
  font-size: 14px;
  color: var(--ink);
  font-weight: 500;
}
.user-role {
  margin-left: 2px;
}
.user-caret {
  color: var(--ink-3);
  font-size: 12px;
}

/* ---------- 内容区 ---------- */
.app-main {
  padding: 24px;
  background:
    radial-gradient(1200px 500px at 15% -10%, #eaf4ff 0%, transparent 60%),
    radial-gradient(900px 420px at 100% 0%, #eef2ff 0%, transparent 55%),
    var(--surface-2);
  overflow-y: auto;
}

/* ===== 底部接口文档按钮 ===== */
.api-doc-footer {
  padding: 12px;
  border-top: 1px solid #0a2a3a;
  flex-shrink: 0;
}

.api-doc-btn {
  position: relative;
  padding: 2px;
  border-radius: 12px;
  background: linear-gradient(135deg, #00d2ff 0%, #3a7bd5 50%, #8e44ad 100%);
  background-size: 200% 200%;
  cursor: pointer;
  transition: all 0.35s ease;
  animation: gradientShift 6s ease infinite;
}

@keyframes gradientShift {
  0%, 100% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
}

.api-doc-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(58, 123, 213, 0.5);
}

.api-doc-btn-inner {
  background: #0a1a2a;
  border-radius: 10px;
  padding: 10px 12px;
  display: flex;
  align-items: center;
  transition: background 0.35s ease;
}

.api-doc-btn:hover .api-doc-btn-inner {
  background: #0f2338;
}

.api-doc-icon {
  color: #4fc3f7;
  font-size: 20px;
  margin-right: 10px;
  transition: transform 0.35s ease;
}

.api-doc-btn:hover .api-doc-icon {
  transform: rotate(-8deg) scale(1.1);
  color: #00d2ff;
}

.api-doc-text {
  flex: 1;
  line-height: 1.2;
}

.api-doc-title {
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.api-doc-sub {
  color: #5c7c9a;
  font-size: 10px;
  margin-top: 2px;
  letter-spacing: 0.5px;
}

.api-doc-arrow {
  color: #5c7c9a;
  font-size: 14px;
  transition: all 0.35s ease;
}

.api-doc-btn:hover .api-doc-arrow {
  color: #4fc3f7;
  transform: translate(2px, -2px);
}

/* ===== 通知铃铛 ===== */
.bell-wrapper {
  height: 40px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  cursor: pointer;
  border-radius: 12px;
  transition: background 0.2s var(--ease);
}

.bell-wrapper:hover {
  background: var(--sky-50);
}

.notify-item {
  display: flex;
  align-items: flex-start;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.2s;
  position: relative;
}

.notify-item:hover {
  background: var(--surface-2);
}

.notify-item:hover .notify-delete {
  opacity: 1;
}

.notify-unread {
  background: var(--sky-50);
}

.notify-unread .notify-title {
  font-weight: 600;
}

.notify-icon {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
  margin-right: 10px;
}

.notify-answer { background: #409eff; }
.notify-announcement { background: #e6a23c; }
.notify-system { background: #909399; }

.notify-content {
  flex: 1;
  min-width: 0;
}

.notify-title {
  font-size: 13px;
  color: var(--ink);
  margin-bottom: 2px;
}

.notify-text {
  font-size: 12px;
  color: var(--ink-2);
  line-height: 1.4;
  margin-bottom: 4px;
  word-break: break-all;
}

.notify-time {
  font-size: 11px;
  color: var(--ink-3);
}

.notify-delete {
  position: absolute;
  right: 8px;
  top: 8px;
  opacity: 0;
  color: var(--ink-3);
  transition: opacity 0.2s;
}

.notify-delete:hover {
  color: var(--app-danger);
}

/* 顶栏图标按钮（意见反馈等常驻入口） */
.header-icon-btn {
  width: 38px;
  height: 38px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  color: var(--ink-2);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    background 0.18s var(--ease),
    color 0.18s var(--ease),
    transform 0.18s var(--ease);
}
.header-icon-btn:hover,
.header-icon-btn.is-active {
  background: var(--sky-50);
  color: var(--sky-600);
}
.header-icon-btn:hover {
  transform: translateY(-1px);
}

/* 消息中心浮层 */
.notify-panel {
  padding: 4px 0;
}
.notify-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--stroke);
}
.notify-list {
  max-height: 400px;
  overflow-y: auto;
}

/* 公告弹窗 */
.announcement-title {
  margin: 0 0 12px;
  color: var(--ink);
}
.announcement-body {
  padding: 12px;
  border-radius: 6px;
  background: var(--surface-2);
  color: var(--ink-2);
  line-height: 1.7;
  white-space: pre-wrap;
}
.announcement-time {
  margin-top: 16px;
  text-align: right;
  font-size: 12px;
  color: var(--ink-3);
}

/* 深色：浅蓝 hover / 未读底在深色下会发亮，统一压成深色块 */
html[data-theme="dark"] .header-icon-btn:hover,
html[data-theme="dark"] .header-icon-btn.is-active,
html[data-theme="dark"] .notify-item:hover,
html[data-theme="dark"] .notify-unread {
  background: #16263f;
}

/* ==================== 响应式 ====================
   三档：>1100 完整侧栏；≤1100 收窄成图标条；≤768 变抽屉 + 顶栏汉堡。
   el-aside 的宽度是行内 style，媒体查询里必须 !important 才盖得住。 */
@media (max-width: 1100px) {
  .app-sidebar {
    width: 72px !important;
  }
  .app-logo {
    padding: 0;
    justify-content: center;
  }
  .logo-text,
  .logo-badge {
    display: none;
  }
  .el-menu-item {
    margin: 4px 10px;
    padding-left: 0 !important;
    padding-right: 0 !important;
    justify-content: center;
  }
  .el-menu-item span {
    display: none;
  }
  .el-menu-item .el-icon {
    margin-right: 0;
  }
  .api-doc-footer {
    padding: 8px;
  }
  .api-doc-btn-inner {
    justify-content: center;
    padding: 10px 0;
  }
  .api-doc-text,
  .api-doc-arrow {
    display: none;
  }
  .api-doc-icon {
    margin-right: 0;
  }
  .app-main {
    padding: 16px;
  }
}

@media (max-width: 768px) {
  /* 抽屉：默认移出屏幕外，.is-open 时滑入；抽屉里恢复 232px 与文案 */
  .app-sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    width: 232px !important;
    z-index: 2000;
    transform: translateX(-100%);
    transition: transform 0.28s var(--ease);
  }
  .app-sidebar.is-open {
    transform: translateX(0);
    box-shadow: 0 0 48px rgba(3, 10, 24, 0.5);
  }
  .sidebar-mask {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 1999;
    background: rgba(8, 15, 28, 0.46);
    backdrop-filter: blur(2px);
    -webkit-backdrop-filter: blur(2px);
  }
  .app-logo {
    padding: 0 20px;
    justify-content: flex-start;
  }
  .logo-text,
  .logo-badge {
    display: inline;
  }
  .logo-badge {
    margin-left: auto;
  }
  .el-menu-item {
    margin: 4px 14px;
    padding-left: 20px !important;
    justify-content: flex-start;
  }
  .el-menu-item span {
    display: inline;
  }
  .el-menu-item .el-icon {
    margin-right: 10px;
  }
  .api-doc-footer {
    padding: 12px;
  }
  .api-doc-btn-inner {
    justify-content: flex-start;
    padding: 10px 12px;
  }
  .api-doc-text,
  .api-doc-arrow {
    display: block;
  }
  .api-doc-icon {
    margin-right: 10px;
  }
  .hamburger {
    display: flex;
  }
  .app-header {
    padding: 0 12px;
  }
  .app-main {
    padding: 12px;
  }
  .crumb-home-text,
  .user-role {
    display: none;
  }
  .user-chip {
    padding: 0 6px;
  }
}

</style>