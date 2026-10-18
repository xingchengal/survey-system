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
          <!-- ===== 管理员菜单 ===== -->
          <template v-if="isAdmin">
            <el-menu-item index="/dashboard">
              <el-icon><DataBoard /></el-icon>
              <span>系统总览</span>
            </el-menu-item>
            <el-menu-item index="/users">
              <el-icon><UserFilled /></el-icon>
              <span>用户管理</span>
            </el-menu-item>
            <el-menu-item index="/admin/surveys">
              <el-icon><Document /></el-icon>
              <span>全部问卷</span>
            </el-menu-item>
            <el-menu-item index="/admin/templates">
              <el-icon><DocumentCopy /></el-icon>
              <span>模板管理</span>
            </el-menu-item>
            <el-menu-item index="/statistics">
              <el-icon><PieChart /></el-icon>
              <span>系统统计</span>
            </el-menu-item>
            <el-menu-item index="/admin/recycle">
              <el-icon><Delete /></el-icon>
              <span>回收站</span>
            </el-menu-item>
            <el-menu-item index="/admin/announcements">
              <el-icon><Bell /></el-icon>
              <span>公告管理</span>
            </el-menu-item>
            <el-menu-item index="/admin/logs">
              <el-icon><Memo /></el-icon>
              <span>操作日志</span>
            </el-menu-item>
            <el-menu-item index="/admin/reviews">
              <el-icon><Warning /></el-icon>
              <span>举报审核</span>
            </el-menu-item>
            <el-menu-item index="/admin/wallets">
              <el-icon><Wallet /></el-icon>
              <span>钱包管理</span>
            </el-menu-item>
            <el-menu-item index="/admin/demo">
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
            <el-menu-item index="/my/recycle">
              <el-icon><Delete /></el-icon>
              <span>回收站</span>
            </el-menu-item>
            <el-menu-item index="/my/wallet">
              <el-icon><Wallet /></el-icon>
              <span>我的钱包</span>
            </el-menu-item>
          </template>
        </el-menu>
      </div>

      <!-- 底部：接口文档按钮（仅管理员） -->
      <div v-if="isAdmin" class="api-doc-footer">
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
          <button
            type="button"
            class="theme-toggle"
            :title="isDark ? '切换到浅色' : '切换到深色'"
            @click="handleToggleTheme"
          >
            <el-icon :size="18"><Sunny v-if="isDark" /><Moon v-else /></el-icon>
          </button>

          <!-- ==================== 通知铃铛 ==================== -->
          <el-popover
            placement="bottom-end"
            :width="380"
            trigger="click"
            @show="loadNotifications"
          >
            <template #reference>
              <div class="bell-wrapper">
                <el-badge :value="unreadCount > 0 ? unreadCount : ''" :hidden="unreadCount === 0" :max="99">
                  <el-icon :size="20" class="bell-icon"><Bell /></el-icon>
                </el-badge>
              </div>
            </template>

            <div style="padding: 4px 0;">
              <div style="display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid #f0f2f5;">
                <span class="text-bold">消息中心</span>
                <div>
                  <el-button type="primary" link size="small" @click="markAllRead" :disabled="unreadCount === 0">全部已读</el-button>
                  <el-button type="danger" link size="small" @click="clearAll" :disabled="notifications.length === 0">清空</el-button>
                </div>
              </div>

              <div style="max-height: 400px; overflow-y: auto;">
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
              <el-tag v-if="isAdmin" type="danger" size="small" effect="light" class="user-role">管理员</el-tag>
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
        <h3 style="margin: 0 0 12px 0; color: #303133;">{{ currentAnnouncement.title }}</h3>
        <div style="color: #606266; line-height: 1.7; white-space: pre-wrap; padding: 12px; background: #fafbfc; border-radius: 6px;">
          {{ currentAnnouncement.content }}
        </div>
        <div style="color: #999; font-size: 12px; margin-top: 16px; text-align: right;">
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
  ChatDotRound, BellFilled, InfoFilled, Close, Wallet
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
// ★ 统一走接口层：不要在本布局里自己 fetch —— http.js 统一带 Authorization、
//   统一判成败、统一弹错误提示，页面只管拿数据。
//   路径说明：本文件在 src/layouts/ 下，api 在 src/api/，所以是 '../api'。
import api from '../api'
// ★ 登录态统一从 utils/auth 读：避免裸 JSON.parse 遇到脏数据时抛异常，
//   把整个后台布局（含侧边栏和顶栏）都渲染崩掉。
import { getUser, clearAuth, homePathFor } from '../utils/auth'
import { toggleTheme } from '../utils/theme'
// 品牌标识（替换原来的 emoji，与登录/注册页、favicon 同一图形）
import BrandLogo from '../components/BrandLogo.vue'

export default {
  components: {
    BrandLogo,
    DataBoard, Document, PieChart, ArrowDown, UserFilled, Delete,
    DocumentCopy, TopRight, User, SwitchButton, Memo, MagicStick, Bell,
    ChatDotRound, BellFilled, InfoFilled, Close, Wallet
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
      notifyTimer: null
    }
  },
  computed: {
    isAdmin() {
      return this.currentUser.role === 'admin'
    },
    /** 面包屑「首页」的落点：管理员去系统总览，普通用户去数据看板（与路由守卫同一口径） */
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
    if (!this.isAdmin) {
      this.loadAnnouncements()
    }
    // 加载未读数
    this.loadUnreadCount()
    // 每 30 秒刷新一次未读
    this.notifyTimer = setInterval(this.loadUnreadCount, 30000)
    // 首次进入也归零：刷新/热更新可能恢复上一次的滚动位置
    this.$nextTick(this.resetMainScroll)
  },
  beforeUnmount() {
    // 组件卸载时清掉 30 秒轮询未读数的定时器，避免离开后台后还在请求
    if (this.notifyTimer) clearInterval(this.notifyTimer)
  },
  methods: {
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
        //   api.user.logout() 内部是 http.post(..., { silent: true })：
        //   silent 表示「不自动弹错误提示、不自动跳登录」，因为退出登录本身就是离开本页，
        //   而且未登录时调它会 401，绝不能触发「登录已过期 → 跳 /login」那一套。
        try {
          await api.user.logout()
        } catch {
          // 忽略错误：退出接口失败也必须让用户走掉，下面照常清本地登录态
        }
        // ★ 清本地登录态必须走 utils/auth 的 clearAuth：它把 token / refreshToken / user
        //   三个 key 一起清 —— 手写 removeItem 很容易漏掉 refreshToken，
        //   漏了就会出现"退出登录后刷新页面又自动登录"。
        clearAuth()
        this.$router.push('/login')
      } else if (command === 'profile') {
        if (this.isAdmin) {
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
        if (this.isAdmin) return

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
        this.$router.push(n.link)
      }
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
  background: #f5f7fa;
}

.notify-item:hover .notify-delete {
  opacity: 1;
}

.notify-unread {
  background: #f0f7ff;
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
  color: #303133;
  margin-bottom: 2px;
}

.notify-text {
  font-size: 12px;
  color: #606266;
  line-height: 1.4;
  margin-bottom: 4px;
  word-break: break-all;
}

.notify-time {
  font-size: 11px;
  color: #c0c4cc;
}

.notify-delete {
  position: absolute;
  right: 8px;
  top: 8px;
  opacity: 0;
  color: #909399;
  transition: opacity 0.2s;
}

.notify-delete:hover {
  color: #f56c6c;
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