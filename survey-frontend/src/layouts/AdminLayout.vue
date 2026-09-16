<template>
  <el-container style="height: 100vh;">
    <!-- 侧边栏 -->
    <el-aside width="220px" style="background: #001529; color: #fff; display: flex; flex-direction: column;">
      <!-- Logo -->
      <div style="height: 60px; display: flex; align-items: center; justify-content: center; font-size: 18px; font-weight: bold; border-bottom: 1px solid #0a2a3a; flex-shrink: 0;">
        📊 问卷系统
      </div>

      <!-- 菜单（可滚动） -->
      <div style="flex: 1; overflow-y: auto; overflow-x: hidden;">
        <el-menu
          :default-active="$route.path"
          router
          background-color="#001529"
          text-color="#b0b8c4"
          active-text-color="#fff"
          style="border: none;"
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
            <el-menu-item index="/statistics">
              <el-icon><PieChart /></el-icon>
              <span>系统统计</span>
            </el-menu-item>
            <el-menu-item index="/admin/recycle">
              <el-icon><Delete /></el-icon>
              <span>违规问卷管理</span>
            </el-menu-item>
            <el-menu-item index="/admin/announcements">
              <el-icon><Bell /></el-icon>
              <span>公告管理</span>
            </el-menu-item>
            <el-menu-item index="/admin/logs">
              <el-icon><Memo /></el-icon>
              <span>操作日志</span>
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

    <!-- 右侧区域 -->
    <el-container>
      <el-header style="background: #fff; border-bottom: 1px solid #e8e8e8; display: flex; align-items: center; justify-content: space-between; padding: 0 20px;">
        <div style="font-size: 16px; font-weight: 500;">{{ $route.meta.title || '后台管理' }}</div>

        <div style="display: flex; align-items: center; height: 100%;">
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
                  <el-icon :size="20" style="color: #606266; cursor: pointer;"><Bell /></el-icon>
                </el-badge>
              </div>
            </template>

            <div style="padding: 4px 0;">
              <div style="display: flex; justify-content: space-between; align-items: center; padding: 8px 0; border-bottom: 1px solid #f0f2f5;">
                <span style="font-weight: bold;">消息中心</span>
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
            <span style="cursor: pointer; display: flex; align-items: center; padding: 0 10px; height: 100%;">
              <el-avatar
                :size="32"
                :src="currentUser.avatar"
                style="background: #409eff; color: #fff; display: flex; align-items: center; justify-content: center;"
              >
                {{ avatarText }}
              </el-avatar>
              <span style="margin-left: 8px;">{{ displayName }}</span>
              <el-tag v-if="isAdmin" type="danger" size="small" style="margin-left: 6px;">管理员</el-tag>
              <el-tag v-else type="info" size="small" style="margin-left: 6px;">用户</el-tag>
              <el-icon style="margin-left: 4px;"><ArrowDown /></el-icon>
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

      <el-main style="background: #f5f7fa; padding: 20px;">
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
  ChatDotRound, BellFilled, InfoFilled, Close
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

export default {
  components: {
    DataBoard, Document, PieChart, ArrowDown, UserFilled, Delete,
    DocumentCopy, TopRight, User, SwitchButton, Memo, MagicStick, Bell,
    ChatDotRound, BellFilled, InfoFilled, Close
  },
  data() {
    return {
      currentUser: JSON.parse(localStorage.getItem('user') || '{}'),
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
    displayName() {
      return this.currentUser.nickname || this.currentUser.username || '用户'
    },
    avatarText() {
      const name = this.displayName
      return name ? name.charAt(0) : '用'
    }
  },
  mounted() {
    // 每次进入布局页，重新读取最新的用户信息
    this.currentUser = JSON.parse(localStorage.getItem('user') || '{}')
    // 只有普通用户才加载公告弹窗
    if (!this.isAdmin) {
      this.loadAnnouncements()
    }
    // 加载未读数
    this.loadUnreadCount()
    // 每 30 秒刷新一次未读
    this.notifyTimer = setInterval(this.loadUnreadCount, 30000)
  },
  beforeUnmount() {
    if (this.notifyTimer) clearInterval(this.notifyTimer)
  },
  watch: {
    // 监听路由变化，从个人中心返回时刷新用户信息（保证头像、昵称实时更新）
    $route() {
      this.currentUser = JSON.parse(localStorage.getItem('user') || '{}')
    }
  },
  methods: {
    async handleCommand(command) {
      if (command === 'logout') {
        // ★ 先调后端接口，清 Cookie
        try {
          await fetch('/api/user/logout', { method: 'POST' })
        } catch (e) {
          // 忽略错误
        }
        localStorage.removeItem('user')
        localStorage.removeItem('token')
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
      window.open('http://localhost:8080/doc.html', '_blank')
    },

    // 加载未过期公告，普通用户登录后弹一次
    async loadAnnouncements() {
      try {
        if (this.isAdmin) return

        const res = await fetch('/api/announcement/active')
        const list = await res.json()
        if (!list || list.length === 0) return

        // 按用户ID隔离已读记录
        const userId = this.currentUser.id
        const storageKey = `readAnnouncements_${userId}`
        const readIds = JSON.parse(localStorage.getItem(storageKey) || '[]')
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
    async loadUnreadCount() {
      try {
        const res = await fetch('/api/notification/unread')
        const data = await res.json()
        this.unreadCount = data.count || 0
      } catch (e) { /* 忽略错误 */ }
    },

    async loadNotifications() {
      try {
        const res = await fetch('/api/notification/list')
        this.notifications = await res.json()
      } catch {
        ElMessage.error('加载通知失败')
      }
    },

    async handleNotifyClick(n) {
      if (n.isRead === 0) {
        await fetch(`/api/notification/read/${n.id}`, { method: 'PUT' })
        n.isRead = 1
        this.loadUnreadCount()
      }
      if (n.link) {
        this.$router.push(n.link)
      }
    },

    async markAllRead() {
      await fetch('/api/notification/read-all', { method: 'PUT' })
      this.notifications.forEach(n => n.isRead = 1)
      this.unreadCount = 0
      ElMessage.success('已全部标记为已读')
    },

    async deleteNotify(n) {
      await fetch(`/api/notification/${n.id}`, { method: 'DELETE' })
      this.notifications = this.notifications.filter(item => item.id !== n.id)
      this.loadUnreadCount()
    },

    async clearAll() {
      await fetch('/api/notification/clear', { method: 'DELETE' })
      this.notifications = []
      this.unreadCount = 0
      ElMessage.success('已清空所有通知')
    }
  }
}
</script>

<style>
/* ===== 侧边栏菜单 ===== */
.el-menu {
  border-right: none !important;
}

.el-menu-item {
  position: relative;
  transition: all 0.2s ease;
  border-radius: 0 8px 8px 0;
  margin: 4px 8px 4px 0;
  height: 46px;
  line-height: 46px;
}

.el-menu-item:hover {
  background-color: rgba(255, 255, 255, 0.08) !important;
}

.el-menu-item.is-active {
  background: linear-gradient(90deg, #1677ff 0%, #409eff 100%) !important;
  color: #fff !important;
  box-shadow: 0 2px 8px rgba(22, 119, 255, 0.4);
}

.el-menu-item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 60%;
  background: #fff;
  border-radius: 0 4px 4px 0;
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
  padding: 0 16px;
  height: 100%;
  display: flex;
  align-items: center;
  cursor: pointer;
  transition: background 0.2s;
}

.bell-wrapper:hover {
  background: #f5f7fa;
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
</style>