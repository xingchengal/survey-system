<template>
  <div class="profile-page">
    <!-- 骨架屏：资料回来前先占位，避免左侧卡片"空一下再跳出来" -->
    <div v-if="loading" class="profile-skeleton">
      <el-row :gutter="20">
        <el-col :span="8" :xs="24" :sm="24" :md="8"><div class="sk-block" style="height: 680px;"></div></el-col>
        <el-col :span="16" :xs="24" :sm="24" :md="16">
          <div class="sk-block" style="height: 196px; margin-bottom: 20px;"></div>
          <div class="sk-block" style="height: 172px; margin-bottom: 20px;"></div>
          <div class="sk-block" style="height: 300px;"></div>
        </el-col>
      </el-row>
    </div>

    <template v-else>
    <el-row :gutter="20">
      <!-- 左侧：用户信息卡 -->
      <el-col :span="8" :xs="24" :sm="24" :md="8">
        <el-card :body-style="{ padding: '24px' }" class="profile-card">
          <div class="profile-cover"></div>
          <div class="avatar-wrap">
            <div class="avatar-container" @click="triggerUpload" @mouseenter="avatarHover = true" @mouseleave="avatarHover = false">
              <el-avatar :size="110" :src="user.avatar" class="avatar-img">
                {{ avatarText }}
              </el-avatar>
              <div v-if="avatarHover" class="avatar-overlay">
                <el-icon :size="26"><Camera /></el-icon>
                <span style="font-size: 12px; margin-top: 4px;">更换头像</span>
              </div>
            </div>
            <input ref="fileInput" type="file" accept="image/*" style="display: none;" @change="handleFileChange" />
          </div>

          <h2 class="profile-name">{{ user.nickname || user.username }}</h2>
          <div class="text-center">
            <el-tag type="primary" effect="light" round>普通用户</el-tag>
          </div>

          <el-divider />

          <!-- 账号是唯一标识、不可修改，用小字单独展示；昵称才是可编辑的显示名。
               两者并列时不压低账号的视觉权重，很容易被读成同一个东西。 -->
          <div class="info-row">
            <span class="info-label">账号<el-tag size="small" type="info" effect="plain" class="ml-8">不可修改</el-tag></span>
            <span class="info-value info-account">{{ user.username }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">用户ID</span>
            <span class="info-value">#{{ user.id }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">手机号</span>
            <span class="info-value">{{ user.phone || '未绑定' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">邮箱</span>
            <span class="info-value">{{ user.email || '未绑定' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">注册时间</span>
            <span class="info-value" :title="user.createdTime">{{ formatDateTime(user.createdTime) }}</span>
          </div>

          <!-- 使用足迹：只做展示，不做成就/积分 -->
          <div class="footprint">
            <div class="footprint-item">
              <b>{{ joinedDays }}</b><span>加入天数</span>
            </div>
            <div class="footprint-item">
              <b>{{ myStats.total }}</b><span>创建问卷</span>
            </div>
            <div class="footprint-item">
              <b>{{ myStats.responses }}</b><span>收到答卷</span>
            </div>
          </div>

          <!-- 账号安全信息（取自系统登录日志）：只展示最近 10 条登录记录，第一行就是本次登录。
               IP 优先取 X-Forwarded-For，客户端可伪造，仅供自查异地登录，不作安全判决依据。 -->
          <el-divider />
          <div class="login-log-head">
            <span class="text-bold">🔐 最近登录</span>
            <el-button link type="primary" size="small" :loading="loginLogLoading" @click="loadLoginLogs">刷新</el-button>
          </div>
          <div v-if="!loginLogs.length" class="login-log-empty">暂无登录记录</div>
          <div
            v-for="(log, idx) in loginLogs"
            :key="log.id"
            class="login-log-row"
            :title="log.createTime || ''"
          >
            <span class="login-log-time">
              {{ relativeTime(log.createTime) }}
              <el-tag v-if="idx === 0" size="small" type="success" effect="plain">本次</el-tag>
            </span>
            <span class="login-log-ip">{{ formatIp(log.ip) }}</span>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧：用户内容 -->
      <el-col :span="16" :xs="24" :sm="24" :md="16">
        <!-- 我的待办：回答"下一步做什么"，点一下直接到对应页面 -->
        <el-card class="mb-20 todo-card">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">✅ 我的待办</span>
              <el-button link type="primary" @click="startTour">新手引导</el-button>
            </div>
          </template>
          <div class="todo-grid">
            <div class="todo-item" @click="$router.push('/my/surveys')">
              <div class="todo-num">{{ todo.draft }}</div>
              <div class="todo-text">
                <div class="todo-label">草稿问卷</div>
                <div class="todo-hint">继续编辑</div>
              </div>
            </div>
            <div class="todo-item" @click="openNotify">
              <div class="todo-num">{{ todo.unread }}</div>
              <div class="todo-text">
                <div class="todo-label">未读通知</div>
                <div class="todo-hint">打开消息中心</div>
              </div>
            </div>
            <div class="todo-item" @click="openNotify">
              <div class="todo-num">{{ todo.feedback }}</div>
              <div class="todo-text">
                <div class="todo-label">反馈回复</div>
                <div class="todo-hint">管理员回复</div>
              </div>
            </div>
            <div class="todo-item" @click="$router.push('/my/answers')">
              <div class="todo-num">{{ todo.answers }}</div>
              <div class="todo-text">
                <div class="todo-label">我答过的</div>
                <div class="todo-hint">查看答卷</div>
              </div>
            </div>
          </div>
        </el-card>

        <!-- 我的数据 -->
        <el-card class="mb-20">
          <template #header>
            <span class="text-bold">📊 我的数据</span>
          </template>
          <el-row :gutter="12">
            <el-col :span="6" :xs="12" :sm="12" :md="6">
              <div class="mini-card mini-blue">
                <el-icon :size="22" color="#2f80ed"><Document /></el-icon>
                <div class="mini-value color-primary">{{ myStats.total }}</div>
                <div class="mini-label">我的问卷</div>
              </div>
            </el-col>
            <el-col :span="6" :xs="12" :sm="12" :md="6">
              <div class="mini-card mini-green">
                <el-icon :size="22" color="#16a34a"><Promotion /></el-icon>
                <div class="mini-value color-success">{{ myStats.published }}</div>
                <div class="mini-label">已发布</div>
              </div>
            </el-col>
            <el-col :span="6" :xs="12" :sm="12" :md="6">
              <div class="mini-card mini-amber">
                <el-icon :size="22" color="#d97706"><EditPen /></el-icon>
                <div class="mini-value color-warning">{{ myStats.draft }}</div>
                <div class="mini-label">草稿</div>
              </div>
            </el-col>
            <el-col :span="6" :xs="12" :sm="12" :md="6">
              <div class="mini-card mini-violet">
                <el-icon :size="22" color="#7c3aed"><ChatDotRound /></el-icon>
                <div class="mini-value color-danger">{{ myStats.responses }}</div>
                <div class="mini-label">收到答卷</div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- 常用功能 -->
        <el-card class="mb-20 quick-actions">
          <template #header>
            <span class="text-bold">⚡ 常用功能</span>
          </template>
          <el-row :gutter="12">
            <el-col :span="8" :xs="24" :sm="24" :md="8">
              <div class="quick-action qa-blue" @click="$router.push('/my/surveys')">
                <el-icon :size="26" color="#409eff"><Document /></el-icon>
                <div class="qa-title">我的问卷</div>
                <div class="qa-desc">创建 / 管理</div>
              </div>
            </el-col>
            <el-col :span="8" :xs="24" :sm="24" :md="8">
              <div class="quick-action qa-green" @click="$router.push('/my/dashboard')">
                <el-icon :size="26" color="#67c23a"><DataBoard /></el-icon>
                <div class="qa-title">数据看板</div>
                <div class="qa-desc">查看数据</div>
              </div>
            </el-col>
            <el-col :span="8" :xs="24" :sm="24" :md="8">
              <div class="quick-action qa-red" @click="$router.push('/my/recycle')">
                <el-icon :size="26" color="#f56c6c"><Delete /></el-icon>
                <div class="qa-title">回收站</div>
                <div class="qa-desc">恢复 / 删除</div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <el-card>
          <el-tabs v-model="activeTab">
            <el-tab-pane label="基本资料" name="profile">
              <!-- 表单校验错误在页内 el-form 红字展示，不弹 alert（与 Login/Register 同口径）。
                   这只是体验层：后端 UserService 会再判一次。 -->
              <el-form ref="profileFormRef" class="form-panel" :model="profileForm" :rules="profileRules" label-width="100px">
                <el-form-item label="账号">
                  <!-- 账号只读：用 :model-value 而非 v-model，免得被误以为这个输入框还能改 -->
                  <el-input :model-value="user.username" disabled />
                  <div class="form-hint">账号是唯一标识，注册后不可修改</div>
                </el-form-item>
                <el-form-item label="昵称" prop="nickname">
                  <el-input v-model="profileForm.nickname" placeholder="请输入昵称" maxlength="50" show-word-limit />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="savingNickname" @click="saveNickname">保存昵称</el-button>
                </el-form-item>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="联系方式" name="contact">
              <el-form ref="contactFormRef" class="form-panel" :model="contactForm" :rules="contactRules" label-width="100px">
                <el-form-item label="手机号" prop="phone">
                  <el-input v-model="contactForm.phone" placeholder="请输入手机号（可留空）" maxlength="11" />
                </el-form-item>
                <el-form-item label="邮箱" prop="email">
                  <el-input v-model="contactForm.email" placeholder="请输入邮箱（可留空）" maxlength="100" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="savingContact" @click="saveContact">保存联系方式</el-button>
                </el-form-item>
                <el-alert type="info" :closable="false" style="max-width: 500px;">
                  绑定联系方式后，可接收问卷相关通知、找回账号密码
                </el-alert>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="修改密码" name="password">
              <el-form ref="pwdFormRef" class="form-panel" :model="pwdForm" :rules="pwdRules" label-width="100px">
                <el-form-item label="原密码" prop="oldPassword">
                  <el-input v-model="pwdForm.oldPassword" type="password" show-password maxlength="64" />
                </el-form-item>
                <el-form-item label="新密码" prop="newPassword">
                  <!-- 6~64：本页改密码接口的上限（UserService.updatePassword），不是注册页的 6~20 -->
                  <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少6位（最多64位）" maxlength="64" />
                </el-form-item>
                <el-form-item label="确认新密码" prop="confirmPassword">
                  <el-input v-model="pwdForm.confirmPassword" type="password" show-password maxlength="64" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="savingPassword" @click="savePassword">修改密码</el-button>
                </el-form-item>
                <el-alert type="warning" :closable="false" style="max-width: 500px;">
                  改密码成功后需要重新登录（当前登录凭证会立即失效）
                </el-alert>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="账号注销" name="privacy">
              <div class="form-panel">
                <div class="danger-zone">
                  <div class="danger-title">注销账号</div>
                  <!-- 后果要说全，包括"不会发生什么"：用户最怕注销把别人填过的答卷一起删了 -->
                  <div class="danger-desc">
                    注销后你将无法再登录本系统，账号会立即失效；<br />
                    <b>你不会被删除任何数据</b>：你建的问卷、以及别人在这些问卷上填过的答卷**都会保留**，
                    问卷也继续可以填写（只是不再展示发布者资料）。<br />
                    如需恢复，请联系管理员。
                  </div>
                  <el-button type="danger" plain :disabled="!user.id" @click="openDeleteDialog">
                    注销我的账号
                  </el-button>
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
    </el-row>
    </template>

    <!-- 头像先裁剪再上传：原图会原样转 base64 存库（约 1.4MB JSON），且每次 profile 都返回 -->
    <avatar-cropper v-model="cropVisible" :src="rawImage" @confirm="onAvatarCropped" />

    <!-- ★ 注销必须输入密码二次确认：不可逆的账号级操作，只凭一枚可能被盗的 token 就能注销风险太高
         （后端 deleteAccount 也会再验一次，前端只是体验层）。 -->
    <el-dialog v-model="deleteDialogVisible" title="确认注销账号" width="440px" :close-on-click-modal="false">
      <el-alert type="error" :closable="false" style="margin-bottom: 14px;">
        注销后账号立即失效、且**无法自行恢复**（管理员可帮你恢复）。
        你的问卷与已收到的答卷不会被删除。
      </el-alert>
      <el-form ref="deleteFormRef" :model="deleteForm" :rules="deleteRules" label-width="80px">
        <el-form-item label="账号">
          <el-input :model-value="user.username" disabled />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="deleteForm.password"
            type="password"
            show-password
            maxlength="64"
            placeholder="请输入当前密码以确认"
            @keyup.enter="confirmDeleteAccount"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="deletingAccount" @click="closeDeleteDialog">取消</el-button>
        <el-button type="danger" :loading="deletingAccount" @click="confirmDeleteAccount">确认注销</el-button>
      </template>
    </el-dialog>

    <guide-tour
      v-model="tourVisible"
      :steps="tourSteps"
    />
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
// 本页请求统一走 src/api/；头像上传仍是 FileReader 转 base64 后交给接口层。
// 本页与 AdminProfile.vue 是复制品，改一边必须同步另一边。
import api from '../api'
// 登录态的读/改/清统一走 utils/auth
import { getUser, setUser, clearAuth } from '../utils/auth'
// 自研头像裁剪组件（零新增依赖，见组件内注释）
import AvatarCropper from '../components/AvatarCropper.vue'
// 新手引导浮层（localStorage 记录完成状态，可重播）
import GuideTour from '../components/GuideTour.vue'

/**
 * 联系方式校验：与后端 dto/ContactRequest 的 @Pattern 逐字一致，改这里必须同批核后端。
 * 空值合法（允许清空该项），所以验证器先判空再判格式 —— 后端对空白的放行分支是同一口径。
 */
const PHONE_RE = /^1[3-9]\d{9}$/
const EMAIL_RE = /^[\w.-]+@[\w.-]+\.[A-Za-z]{2,}$/

// 图标已在 main.js 全局注册，不再重复注册（Camera 是 HTML 保留标签名）
export default {
  components: { AvatarCropper, GuideTour },
  data() {
    const validatePhone = (rule, value, callback) => {
      if (!value) return callback()
      return PHONE_RE.test(value.trim()) ? callback() : callback(new Error('手机号格式不正确（应为 11 位国内手机号）'))
    }
    const validateEmail = (rule, value, callback) => {
      if (!value) return callback()
      return EMAIL_RE.test(value.trim()) ? callback() : callback(new Error('邮箱格式不正确'))
    }
    return {
      user: {},
      activeTab: 'profile',
      profileForm: { nickname: '' },
      profileFormRef: null,
      profileRules: {
        nickname: [
          { required: true, message: '昵称不能为空', trigger: 'blur' },
          { max: 50, message: '昵称不能超过 50 个字符', trigger: 'blur' }
        ]
      },
      contactForm: { phone: '', email: '' },
      contactFormRef: null,
      contactRules: {
        phone: [{ validator: validatePhone, trigger: 'blur' }],
        email: [{ validator: validateEmail, trigger: 'blur' }]
      },
      pwdForm: { oldPassword: '', newPassword: '', confirmPassword: '' },
      pwdFormRef: null,
      pwdRules: {
        oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
        newPassword: [
          { required: true, message: '请输入新密码', trigger: 'blur' },
          { min: 6, max: 64, message: '新密码长度需在 6~64 位之间', trigger: 'blur' }
        ],
        confirmPassword: [
          { required: true, message: '请再次输入新密码', trigger: 'blur' },
          {
            validator: (rule, value, callback) => {
              if (value !== this.pwdForm.newPassword) return callback(new Error('两次输入的新密码不一致'))
              return callback()
            },
            trigger: 'blur'
          }
        ]
      },
      avatarHover: false,
      /** 首次进入的骨架屏：资料回来即关（失败也关，避免一直闪） */
      loading: true,
      cropVisible: false,
      rawImage: '',
      savingNickname: false,
      savingContact: false,
      savingPassword: false,
      loginLogs: [],
      loginLogLoading: false,
      deleteDialogVisible: false,
      deleteForm: { password: '' },
      deleteFormRef: null,
      deleteRules: {
        password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
      },
      deletingAccount: false,
      myStats: { total: 0, published: 0, draft: 0, responses: 0 },
      todo: { draft: 0, unread: 0, feedback: 0, answers: 0 },
      tourVisible: false
    }
  },
  computed: {
    avatarText() {
      const name = this.user.nickname || this.user.username || '用'
      return name.charAt(0).toUpperCase()
    },
    /** 注册天数，最少按 1 天算：当天注册显示 0 天不好看 */
    joinedDays() {
      const ts = new Date(String(this.user.createdTime || '').replace(' ', 'T')).getTime()
      if (!ts) return 0
      return Math.max(1, Math.floor((Date.now() - ts) / 86400000))
    },
    /** 步骤只依赖布局常驻元素与本页卡片，跨页引导不做 */
    tourSteps() {
      return [
        { target: '.app-menu', title: '功能都在左侧', text: '问卷、答卷、钱包、客服中心都在左侧菜单，当前所在页会高亮。' },
        { target: '.bell-wrapper', title: '通知中心', text: '答卷回收、公告、反馈回复都会在这里提醒，点铃铛查看。' },
        { target: '.header-icon-btn', title: '意见反馈', text: '对功能有意见或想投诉，点这里一次性提交，管理员会逐条查看。' },
        { target: '.profile-card', title: '你的资料卡', text: '头像、昵称、账号、最近登录都在这里；改密码与注销在下方页面。' },
        { target: '.todo-card', title: '先看待办', text: '这里汇总草稿、未读通知和待查看的回复，点一下直接去处理。' },
        { target: '.quick-actions', title: '常用入口', text: '最常用的几个页面放在这里，少点两下。' }
      ]
    }
  },
  mounted() {
    this.loadProfile()
  },
  methods: {
    async loadProfile() {
      try {
        this.user = await api.user.profile()
        if (this.user.role === 'admin') {
          ElMessage.info('管理员请访问管理员个人中心')
          this.$router.push('/admin/profile')
          return
        }
        this.profileForm.nickname = this.user.nickname || ''
        this.contactForm.phone = this.user.phone || ''
        this.contactForm.email = this.user.email || ''
        this.loadMyStats()
        this.loadLoginLogs()
        this.loadTodo()
      } catch {
        // 保留 catch 只为挡住 rejection（提示已由接口层弹出），后面的表单初始化不再执行
      } finally {
        this.loading = false
      }
    },

    async loadMyStats() {
      try {
        this.myStats = await api.survey.myStatistics()
        this.todo.draft = this.myStats.draft || 0
      } catch (e) {
        // 统计只是附带信息，失败不影响主卡片；提示已由接口层弹出，这里仅留个控制台线索
        console.error(e)
      }
    },

    /** 最近登录记录。失败不打扰用户：这只是辅助信息，接口层已弹过提示 */
    async loadLoginLogs() {
      this.loginLogLoading = true
      try {
        this.loginLogs = await api.user.loginLogs()
      } catch (e) {
        console.error(e)
      } finally {
        this.loginLogLoading = false
      }
    },

    /** 注册时间：后端可能给 ISO（带 T）或 `yyyy-MM-dd HH:mm:ss`，统一到分钟 */
    formatDateTime(text) {
      const s = String(text || '').replace('T', ' ')
      return s ? s.slice(0, 16) : '—'
    },

    /** 登录记录用相对时间；超过一天退回日期，完整时间在 title 上 */
    relativeTime(text) {
      const ts = new Date(String(text || '').replace(' ', 'T')).getTime()
      if (!ts) return '—'
      const diff = Date.now() - ts
      if (diff < 60000) return '刚刚'
      if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`
      if (diff < 86400000) return `${Math.floor(diff / 3600000)} 小时前`
      return this.formatDateTime(text)
    },

    /** IPv6 回环显示为「本机」，IPv4-mapped 去掉前缀 */
    formatIp(ip) {
      if (!ip) return '未知'
      if (ip === '::1' || ip === '0:0:0:0:0:0:0:1') return '本机'
      if (ip.startsWith('::ffff:')) return ip.slice(7)
      return ip
    },

    /** 待办数字：三项各自失败互不影响，提示由接口层负责 */
    loadTodo() {
      api.notification.unread()
        .then(d => { this.todo.unread = d.count || 0 })
        .catch(() => {})
      api.feedback.unread()
        .then(d => { this.todo.feedback = d.userUnread || 0 })
        .catch(() => {})
      api.answer.my(1, 1)
        .then(d => { this.todo.answers = d.total || 0 })
        .catch(() => {})
    },

    /** 待办里的「未读通知 / 反馈回复」都落到顶栏消息中心 */
    openNotify() {
      window.dispatchEvent(new CustomEvent('dsh:open-notify'))
    },

    startTour() {
      this.tourVisible = true
    },

    triggerUpload() { this.$refs.fileInput.click() },

    /**
     * 选图 → 校验 → 交给裁剪组件。
     * 三处口径与后端对齐：按原始文件大小拦 2MB（后端按解码后字节数判定）、
     *   格式白名单 PNG/JPG/GIF，且校验失败必须重置 input.value。
     */
    handleFileChange(e) {
      const file = e.target.files[0]
      // 先清空，再判断 —— 否则校验失败时 input 的 value 留在原地（见上方说明）
      e.target.value = ''
      if (!file) return
      const allowed = ['image/png', 'image/jpeg', 'image/gif']
      if (!allowed.includes(file.type)) {
        return ElMessage.warning('仅支持 PNG / JPG / GIF 格式的图片')
      }
      if (file.size > 2 * 1024 * 1024) {
        return ElMessage.warning('图片不能超过 2MB')
      }
      const reader = new FileReader()
      reader.onload = (ev) => {
        this.rawImage = ev.target.result
        this.cropVisible = true
      }
      reader.readAsDataURL(file)
    },

    /** 裁剪组件回调：拿到的是 320×320 的 PNG data URL（见组件里 crop() 的说明） */
    async onAvatarCropped(dataUrl) {
      try {
        await api.user.updateAvatar(dataUrl)
        ElMessage.success('头像修改成功')
        this.user.avatar = dataUrl
        this.rawImage = ''
        // 同步 localStorage 里的登录用户，否则刷新后顶栏还是旧头像（读改写统一走 utils/auth）
        const localUser = getUser() || {}
        localUser.avatar = dataUrl
        setUser(localUser)
      } catch {
        // 组件已自己关闭；这里只吞异常，提示已由接口层弹出，用户重新点头像即可
      }
    },

    async saveNickname() {
      // 校验交给 el-form 的 rules（页内红字）；这里只在"校验通过"后才发请求
      if (!(await this.validateForm(this.$refs.profileFormRef))) return
      this.savingNickname = true
      try {
        await api.user.updateNickname(this.profileForm.nickname.trim())
        ElMessage.success('昵称修改成功')
        this.user.nickname = this.profileForm.nickname.trim()
        const localUser = getUser() || {}
        localUser.nickname = this.user.nickname
        setUser(localUser)
      } catch {
        // 失败提示由接口层弹出；后端对昵称只校验非空 / ≤50 字 / 用户存在，没有重名校验
      } finally {
        this.savingNickname = false
      }
    },

    async saveContact() {
      if (!(await this.validateForm(this.$refs.contactFormRef))) return
      this.savingContact = true
      try {
        // 传 trim 后的值：后端也会 trim 并归一 NULL，本地再 trim 免得输入框留着尾空格
        const phone = this.contactForm.phone.trim()
        const email = this.contactForm.email.trim()
        await api.user.updateContact(phone, email)
        ElMessage.success('联系方式保存成功')
        this.user.phone = phone
        this.user.email = email
        this.contactForm.phone = phone
        this.contactForm.email = email
      } catch {
        // 失败提示由接口层弹出（格式前后端各验一次）
      } finally {
        this.savingContact = false
      }
    },

    async savePassword() {
      if (!(await this.validateForm(this.$refs.pwdFormRef))) return
      this.savingPassword = true
      try {
        await api.user.updatePassword(this.pwdForm.oldPassword, this.pwdForm.newPassword)
        // 文案必须如实：改密码不会吊销 refresh token，不能说"所有设备已退出"
        ElMessage.success('密码修改成功，请重新登录')
        this.pwdForm = { oldPassword: '', newPassword: '', confirmPassword: '' }
        if (this.$refs.pwdFormRef) this.$refs.pwdFormRef.clearValidate()
        setTimeout(() => {
          // 清本地登录态并回登录页（清除走 utils/auth.clearAuth，统一 key 且成对清除）
          clearAuth()
          this.$router.push('/login')
        }, 1500)
      } catch {
        // 失败提示由接口层统一弹出（如"原密码不正确"），表单内容保留不动，方便重试
      } finally {
        this.savingPassword = false
      }
    },

    /** 统一触发表单校验：通过 true / 失败 false（错误文案由 el-form 显示在字段下方） */
    validateForm(formRef) {
      if (!formRef) return Promise.resolve(true)
      return new Promise((resolve) => {
        formRef.validate((valid) => resolve(Boolean(valid)))
      })
    },


    openDeleteDialog() {
      this.deleteForm.password = ''
      this.deleteDialogVisible = true
      // 清掉上一次可能残留的校验红字（弹窗是复用的 DOM）
      this.$nextTick(() => {
        if (this.$refs.deleteFormRef) this.$refs.deleteFormRef.clearValidate()
      })
    },

    closeDeleteDialog() {
      if (this.deletingAccount) return   // 请求进行中不让关，避免"以为没注销成功"却其实成功了
      this.deleteDialogVisible = false
      this.deleteForm.password = ''
    },

    /**
     * ★ 成功后必须清本地登录态并回登录页：账号已软删，此刻 access token 每次请求都 401，
     *   refresh token 也被吊销，不清就会停在一个"点哪都报错"的页面上。
     */
    async confirmDeleteAccount() {
      if (!(await this.validateForm(this.$refs.deleteFormRef))) return
      this.deletingAccount = true
      try {
        await api.user.deleteAccount(this.deleteForm.password)
        this.deleteDialogVisible = false
        ElMessage.success('账号已注销，即将返回登录页')
        setTimeout(() => {
          clearAuth()
          this.$router.push('/login')
        }, 1200)
      } catch {
        // 失败提示（密码错误 / 账号不存在或已注销）由接口层统一弹出，表单内容保留便于重试
      } finally {
        this.deletingAccount = false
      }
    }
  }
}
</script>

<style scoped>
.profile-page {
  max-width: 1200px;
  margin: 0 auto;
}

.profile-card {
  overflow: hidden;
}
.profile-cover {
  height: 104px;
  margin: -24px -24px 0;
  background:
    radial-gradient(140% 180% at 85% -30%, rgba(255, 255, 255, 0.4) 0%, transparent 55%),
    var(--brand-gradient);
  position: relative;
  overflow: hidden;
}
.profile-cover::after {
  content: '';
  position: absolute;
  width: 180px;
  height: 180px;
  right: -60px;
  bottom: -110px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.18);
}
.avatar-wrap {
  display: flex;
  justify-content: center;
  margin-top: -62px;
  position: relative;
  z-index: 1;
}
.avatar-container {
  position: relative;
  cursor: pointer;
  border-radius: 50%;
  overflow: hidden;
  transition: transform 0.25s var(--ease);
}
.avatar-container:hover { transform: scale(1.03); }
.avatar-img {
  background: var(--brand-gradient);
  font-size: 44px;
  color: #fff;
  display: block;
  object-fit: cover;
  border: 4px solid #fff;
  box-shadow: 0 10px 24px rgba(15, 61, 145, 0.2);
}
.avatar-overlay {
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.55);
  color: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}
.profile-name {
  margin: 14px 0 8px;
  text-align: center;
  font-size: 20px;
  font-weight: 700;
  color: var(--ink);
}
.info-row { display: flex; justify-content: space-between; padding: 11px 0; border-bottom: 1px dashed #eef3fb; font-size: 14px; }
.info-row:last-child { border-bottom: none; }
.info-label { color: var(--ink-3); display: flex; align-items: center; }
.info-value { color: var(--ink); font-weight: 500; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 账号用等宽小字（"页面内账号采用小字展示"），与昵称的视觉权重区分开 */
.info-account { font-size: 13px; font-weight: 400; color: var(--ink-2); font-family: Consolas, Monaco, monospace; }

/* ---- 表单页内提示（错误信息页内展示，不用弹窗） ---- */
.form-hint { font-size: 12px; color: var(--ink-3); line-height: 1.6; }

/* ---- 危险操作区（账号注销）：用红边框让"这是不可逆操作"在视觉上先说出来 ---- */
.danger-zone {
  max-width: 500px;
  margin-top: 4px;
  padding: 14px 16px;
  border: 1px solid #fbc4c4;
  border-radius: var(--r-md);
  background: #fef8f8;
}
.danger-title { font-size: 14px; font-weight: 600; color: #ef4444; }
.danger-desc { font-size: 12px; color: var(--ink-3); line-height: 1.8; margin: 6px 0 12px 0; }
html[data-theme="dark"] .danger-zone {
  background: #2b1718;
  border-color: #4a2425;
}

/* ---- 最近登录记录 ---- */
.login-log-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.login-log-empty { font-size: 13px; color: var(--ink-3); }
.login-log-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--stroke);
}
.login-log-row:last-child { border-bottom: none; }
.login-log-time { font-size: 13px; color: var(--ink); display: flex; align-items: center; gap: 6px; }
.login-log-ip { font-size: 12px; color: var(--ink-3); text-align: right; word-break: break-all; }

/* ---- 右上数据小卡 ---- */
.mini-card {
  text-align: center;
  padding: 16px 6px;
  border-radius: var(--r-md);
  background: var(--surface-2);
  border: 1px solid var(--stroke);
  transition:
    transform 0.22s var(--ease),
    box-shadow 0.22s var(--ease);
}
.mini-card:hover { transform: translateY(-3px); box-shadow: var(--shadow-sm); }
.mini-blue { background: linear-gradient(160deg, #f2f8ff, #e6f1ff); }
.mini-green { background: linear-gradient(160deg, #f2fdf6, #e4f8ec); }
.mini-amber { background: linear-gradient(160deg, #fffaf0, #fff2db); }
.mini-violet { background: linear-gradient(160deg, #f8f5ff, #efe8ff); }
.mini-value { font-size: 24px; font-weight: 700; line-height: 1.2; margin-top: 6px; }
.mini-label { font-size: 12px; color: var(--ink-3); margin-top: 2px; }

/* ---- 常用功能入口 ---- */
.quick-action {
  text-align: center;
  padding: 20px 10px;
  border-radius: var(--r-md);
  border: 1px solid var(--stroke);
  background: var(--surface);
  cursor: pointer;
  transition:
    transform 0.22s var(--ease),
    box-shadow 0.22s var(--ease),
    border-color 0.22s var(--ease);
}
.quick-action:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-md);
}
.qa-blue:hover { border-color: var(--sky-300); }
.qa-green:hover { border-color: #bbf7d0; }
.qa-red:hover { border-color: #fecaca; }
.qa-title { font-size: 13px; font-weight: 600; color: var(--ink); margin-top: 8px; }
.qa-desc { font-size: 11px; color: var(--ink-3); margin-top: 2px; }
/* 我的待办 */
.todo-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.todo-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--stroke);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  cursor: pointer;
  transition:
    border-color 0.18s var(--ease),
    transform 0.18s var(--ease);
}
.todo-item:hover {
  border-color: var(--sky-300);
  transform: translateY(-1px);
}
.todo-num {
  min-width: 34px;
  text-align: center;
  font-size: 24px;
  font-weight: 700;
  color: var(--sky-600);
}
.todo-text {
  min-width: 0;
}
.todo-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.todo-hint {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-3);
}

/* 使用足迹 */
.footprint {
  display: flex;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--stroke);
}
.footprint-item {
  flex: 1;
  text-align: center;
}
.footprint-item b {
  display: block;
  font-size: 16px;
  color: var(--ink);
}
.footprint-item span {
  font-size: 11px;
  color: var(--ink-3);
}

@media (max-width: 1200px) {
  .todo-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .todo-grid {
    grid-template-columns: 1fr;
  }
}
</style>