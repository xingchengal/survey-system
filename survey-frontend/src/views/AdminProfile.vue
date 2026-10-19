<template>
  <div class="profile-page">
    <el-row :gutter="20">
      <!-- 左侧：管理员信息卡 -->
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
            <el-tag type="danger" effect="light" round>{{ isSuper ? '超级管理员' : '次级管理员' }}</el-tag>
          </div>

          <el-divider />

          <!-- 账号是唯一标识、不可修改，用**小字**单独展示；与昵称不分开就会被读成同一个东西 -->
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
            <span class="info-value">{{ user.createdTime }}</span>
          </div>

          <!-- 账号安全信息（取自 t_login_log）：只展示最近 10 条；IP 可被 X-Forwarded-For 伪造，
               界面不宣称它是可信证据。 -->
          <el-divider />
          <div class="login-log-head">
            <span class="text-bold">🔐 最近登录</span>
            <el-button link type="primary" size="small" :loading="loginLogLoading" @click="loadLoginLogs">刷新</el-button>
          </div>
          <div v-if="!loginLogs.length" class="login-log-empty">暂无登录记录</div>
          <div v-for="(log, idx) in loginLogs" :key="log.id" class="login-log-row">
            <div class="login-log-time">
              {{ log.createdTime }}
              <el-tag v-if="idx === 0" size="small" type="success" effect="plain">本次</el-tag>
            </div>
            <div class="login-log-ip">IP：{{ log.ip || '未知' }}</div>
          </div>
        </el-card>
      </el-col>

      <!-- 右侧：管理员内容 -->
      <el-col :span="16" :xs="24" :sm="24" :md="16">
        <!-- 系统管理视图：只显示已授权模块的数字（没权限的卡不渲染） -->
        <el-card v-if="canUserManage || canSurveyManage" class="mb-20">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">🛡️ 系统管理视图</span>
              <el-tag type="danger" size="small">仅管理员可见</el-tag>
            </div>
          </template>
          <el-row :gutter="12">
            <el-col v-if="canUserManage" :span="6" :xs="12" :sm="12" :md="6">
              <div class="mini-card mini-blue">
                <el-icon :size="22" color="#2f80ed"><UserFilled /></el-icon>
                <div class="mini-value color-primary">{{ sysStats.users }}</div>
                <div class="mini-label">普通用户</div>
              </div>
            </el-col>
            <el-col v-if="canSurveyManage" :span="6" :xs="12" :sm="12" :md="6">
              <div class="mini-card mini-green">
                <el-icon :size="22" color="#16a34a"><Document /></el-icon>
                <div class="mini-value color-success">{{ sysStats.total }}</div>
                <div class="mini-label">总问卷数</div>
              </div>
            </el-col>
            <el-col v-if="canSurveyManage" :span="6" :xs="12" :sm="12" :md="6">
              <div class="mini-card mini-amber">
                <el-icon :size="22" color="#d97706"><ChatDotRound /></el-icon>
                <div class="mini-value color-danger">{{ sysStats.responses }}</div>
                <div class="mini-label">总答卷数</div>
              </div>
            </el-col>
            <el-col v-if="canSurveyManage" :span="6" :xs="12" :sm="12" :md="6">
              <div class="mini-card mini-violet">
                <el-icon :size="22" color="#7c3aed"><View /></el-icon>
                <div class="mini-value color-purple">{{ sysStats.views }}</div>
                <div class="mini-label">访客次数</div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- 管理快捷入口：只显示已有权限的入口，避免点进去被守卫踢回 -->
        <el-card v-if="visibleQuickActions.length" class="mb-20">
          <template #header>
            <span class="text-bold">⚡ 管理快捷入口</span>
          </template>
          <el-row :gutter="12">
            <el-col
              v-for="action in visibleQuickActions"
              :key="action.path"
              :span="6"
              :xs="12"
              :sm="12"
              :md="6"
            >
              <div class="quick-action" @click="$router.push(action.path)">
                <el-icon :size="26" :color="action.color"><component :is="action.icon" /></el-icon>
                <div class="qa-title">{{ action.title }}</div>
                <div class="qa-desc">{{ action.desc }}</div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- 最近操作（管理员向：看最近谁动了什么） -->
        <el-card class="mb-20">
          <template #header>
            <div class="flex-between">
              <span class="text-bold">📋 最近操作</span>
              <el-button link type="primary" size="small" @click="$router.push('/admin/logs')">
                查看全部 →
              </el-button>
            </div>
          </template>
          <div v-if="recentLogs.length" class="op-list">
            <div v-for="log in recentLogs" :key="log.id" class="op-row">
              <el-tag size="small" effect="light" round :type="log.role === 'admin' ? 'danger' : 'info'">
                {{ log.username || '匿名' }}
              </el-tag>
              <span class="op-action">{{ log.operation }}</span>
              <span class="op-time">{{ log.createTime }}</span>
            </div>
          </div>
          <el-empty v-else description="暂无操作记录" :image-size="70" />
        </el-card>

        <el-card>
          <el-tabs v-model="activeTab">
            <el-tab-pane label="基本资料" name="profile">
              <!-- 错误信息**页内展示**，不用 alert 弹窗。
                   ★ 与 UserProfile.vue 是一份复制品，改一边必须同步另一边。 -->
              <el-form ref="profileFormRef" class="form-panel" :model="profileForm" :rules="profileRules" label-width="100px">
                <el-form-item label="账号">
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
                  系统告警与重要通知将发送至此联系方式
                </el-alert>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="修改密码" name="password">
              <el-form ref="pwdFormRef" class="form-panel" :model="pwdForm" :rules="pwdRules" label-width="100px">
                <el-form-item label="原密码" prop="oldPassword">
                  <el-input v-model="pwdForm.oldPassword" type="password" show-password maxlength="64" />
                </el-form-item>
                <el-form-item label="新密码" prop="newPassword">
                  <!-- 6~64（改密码接口的上限），**不是**注册页的 6~20 -->
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

            <!-- ★ 超级管理员不显示注销（超管不能删）；次级管理员可正常注销。 -->
            <el-tab-pane v-if="!isSuper" label="账号注销" name="privacy">
              <div class="form-panel">
                <div class="danger-zone">
                  <div class="danger-title">注销账号</div>
                  <div class="danger-desc">
                    注销后你将无法再登录本系统，账号会立即失效；<br />
                    <b>你不会被删除任何数据</b>：你建的问卷、以及别人在这些问卷上填过的答卷**都会保留**，
                    问卷也继续可以填写（只是不再展示发布者资料）。<br />
                    如需恢复，请联系超级管理员。
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

    <avatar-cropper v-model="cropVisible" :src="rawImage" @confirm="onAvatarCropped" />

    <!-- 注销的二次确认弹窗（与 UserProfile.vue 同一份实现，改一边必须同步另一边） -->
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
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
// 本页请求全部走 src/api/；/nickname、/avatar、/contact、/password 的业务失败都是 400 + message，
//   由 http.js 弹一次红字并抛 ApiError —— 页面 catch 只做流程控制，不要再补 ElMessage.error。
import api from '../api'
// 登录态的读/改/清统一走 utils/auth
import { getUser, setUser, clearAuth, hasPermission } from '../utils/auth'
// 自研头像裁剪组件（与 UserProfile.vue 用的是同一个）
import AvatarCropper from '../components/AvatarCropper.vue'
// 本页图标全部是模板标签形式（如 <Camera />），已由 main.js 全局注册，无需再 import

/**
 * 联系方式校验：与 UserProfile.vue、后端 dto/ContactRequest 三处逐字一致。
 * 刻意各写一份而不跨页面 import；改任意一处时同批核本文件、UserProfile.vue、ContactRequest.java。
 */
const PHONE_RE = /^1[3-9]\d{9}$/
const EMAIL_RE = /^[\w.-]+@[\w.-]+\.[A-Za-z]{2,}$/

export default {
  components: { AvatarCropper },
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
      sysStats: { users: 0, total: 0, responses: 0, views: 0 },
      recentLogs: []
    }
  },
  computed: {
    /** 超级管理员：不显示「账号注销」（超管不能删） */
    isSuper() {
      return this.user.role === 'admin'
    },
    /** 系统管理视图按模块权限显隐：没权限的卡不渲染，也不发请求 */
    canUserManage() {
      return this.isSuper || hasPermission('user:manage')
    },
    canSurveyManage() {
      return this.isSuper || hasPermission('survey:manage')
    },
    /** 管理快捷入口：按权限过滤；演示数据只给超管（路由 roles=['admin']） */
    visibleQuickActions() {
      const actions = [
        { permission: 'user:manage', title: '用户管理', desc: '查看 / 删除', path: '/users', icon: 'UserFilled', color: '#409eff' },
        { permission: 'survey:manage', title: '全部问卷', desc: '管理 / 模板', path: '/admin/surveys', icon: 'Document', color: '#67c23a' },
        { permission: null, title: '系统统计', desc: '图表 / 排行', path: '/statistics', icon: 'PieChart', color: '#e6a23c' },
        { permission: 'log:view', title: '操作日志', desc: '操作追溯', path: '/admin/logs', icon: 'Memo', color: '#f56c6c' },
        { permission: 'survey:manage', title: '回收站', desc: '全部用户的删除记录', path: '/admin/recycle', icon: 'Delete', color: '#ef4444' },
        { permission: 'announcement:manage', title: '公告管理', desc: '发布 / 下架', path: '/admin/announcements', icon: 'Bell', color: '#e6a23c' },
        { permission: 'superOnly', title: '演示数据', desc: '生成 / 重置', path: '/admin/demo', icon: 'MagicStick', color: '#8e44ad' }
      ]
      return actions.filter(a => {
        if (a.permission === 'superOnly') return this.isSuper
        if (!a.permission) return true
        return this.isSuper || hasPermission(a.permission)
      })
    },
    avatarText() {
      const name = this.user.nickname || this.user.username || '管'
      return name.charAt(0).toUpperCase()
    }
  },
  mounted() {
    this.loadProfile()
  },
  methods: {
    // 非 admin/subadmin 请回普通用户个人中心；次级管理员没有对应权限就不请求（否则弹 403）
    async loadProfile() {
      try {
        this.user = await api.user.profile()
        if (this.user.role !== 'admin' && this.user.role !== 'subadmin') {
          ElMessage.error('无权访问管理员个人中心')
          this.$router.push('/my/profile')
          return
        }
        this.profileForm.nickname = this.user.nickname || ''
        this.contactForm.phone = this.user.phone || ''
        this.contactForm.email = this.user.email || ''
        if (this.canUserManage || this.canSurveyManage) this.loadSysStats()
        this.loadLoginLogs()
        if (hasPermission('log:view')) this.loadRecentLogs()
      } catch {
        // 请求失败的提示（含 401 自动清登录态并跳 /login）已由接口层统一处理，这里不再重复弹
      }
    },

    /** 最近登录记录。失败只留控制台线索：这是辅助信息，接口层已弹过提示 */
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

    /** 最近操作日志（管理员）：失败静默，它只是辅助信息 */
    async loadRecentLogs() {
      try {
        const res = await api.operationLog.page({ pageNum: 1, pageSize: 6 })
        this.recentLogs = (res && res.list) || []
      } catch {
        this.recentLogs = []
      }
    },

    // 系统总览与 Dashboard.vue 用的是同一个接口（api.survey.systemOverview）
    async loadSysStats() {
      try {
        this.sysStats = await api.survey.systemOverview()
      } catch (e) {
        // 这是次要信息，失败就保持 0；提示已由 http.js 弹过，这里只留一行日志便于排查
        console.error(e)
      }
    },

    triggerUpload() { this.$refs.fileInput.click() },

    // 与 UserProfile.vue 同一份逻辑，改动必须同步两边；三处口径（2MB / 先清 input / 格式白名单）
    //   的完整理由写在 UserProfile.vue 的 handleFileChange 上方，这里不重复。
    handleFileChange(e) {
      const file = e.target.files[0]
      // 先清空再判断：否则校验失败时 input.value 留在原地，再选同一个文件不触发 change
      e.target.value = ''
      if (!file) return
      const allowed = ['image/png', 'image/jpeg', 'image/gif']
      if (!allowed.includes(file.type)) {
        return ElMessage.warning('仅支持 PNG / JPG / GIF 格式的图片')
      }
      // 与后端同值的 2MB（AVATAR_MAX_BYTES），这里按**原始文件大小**拦（裁剪前的字节数）
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

    // 裁剪完成 → 上传（组件给的是 320×320 的 PNG data URL）
    async onAvatarCropped(dataUrl) {
      try {
        // await 成功即成功：失败由 HTTP 状态码判定，提示由 http.js 弹出
        await api.user.updateAvatar(dataUrl)
        ElMessage.success('头像修改成功')
        // 同步更新 localStorage 里的登录态，避免刷新前导航栏头像仍是旧图
        this.user.avatar = dataUrl
        this.rawImage = ''
        const localUser = getUser() || {}
        localUser.avatar = dataUrl
        setUser(localUser)
      } catch {
        // 失败提示由接口层弹出，用户重新点头像即可
      }
    },

    async saveNickname() {
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
        // 失败提示由接口层弹出，昵称规则前端已拦一次
      } finally {
        this.savingNickname = false
      }
    },

    async saveContact() {
      if (!(await this.validateForm(this.$refs.contactFormRef))) return
      this.savingContact = true
      try {
        const phone = this.contactForm.phone.trim()
        const email = this.contactForm.email.trim()
        await api.user.updateContact(phone, email)
        ElMessage.success('联系方式保存成功')
        this.user.phone = phone
        this.user.email = email
        this.contactForm.phone = phone
        this.contactForm.email = email
      } catch {
        // 失败提示由 http.js 弹出（格式前后端各验一次：前端 rules 红字，后端 ContactRequest 注解兜底）
      } finally {
        this.savingContact = false
      }
    },

    // 成功后清本地登录态回登录页：密码变了，让用户重新登录更安全
    async savePassword() {
      if (!(await this.validateForm(this.$refs.pwdFormRef))) return
      this.savingPassword = true
      try {
        // 原密码错误是 400 + message，http.js 会弹红字并抛错，走不到成功分支
        await api.user.updatePassword(this.pwdForm.oldPassword, this.pwdForm.newPassword)
        // 文案必须如实：改密码不会吊销 refresh token，不能说"所有设备已退出"
        ElMessage.success('密码修改成功，请重新登录')
        this.pwdForm = { oldPassword: '', newPassword: '', confirmPassword: '' }
        if (this.$refs.pwdFormRef) this.$refs.pwdFormRef.clearValidate()
        setTimeout(() => {
          // 清除走 utils/auth.clearAuth（统一 key + 一定成对清除）
          clearAuth()
          this.$router.push('/login')
        }, 1500)
      } catch {
        // 失败提示由接口层弹出（原密码错误 → 400 + message）
      } finally {
        this.savingPassword = false
      }
    },

    /** 统一触发表单校验（通过 true / 否则 false，错误文案由 el-form 显示在字段下方） */
    validateForm(formRef) {
      if (!formRef) return Promise.resolve(true)
      return new Promise((resolve) => {
        formRef.validate((valid) => resolve(Boolean(valid)))
      })
    },


    openDeleteDialog() {
      this.deleteForm.password = ''
      this.deleteDialogVisible = true
      this.$nextTick(() => {
        if (this.$refs.deleteFormRef) this.$refs.deleteFormRef.clearValidate()
      })
    },

    closeDeleteDialog() {
      if (this.deletingAccount) return
      this.deleteDialogVisible = false
      this.deleteForm.password = ''
    },

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
        // 失败提示由接口层弹出（密码错误 / 账号不存在或已注销）
      } finally {
        this.deletingAccount = false
      }
    }
  }
}
</script>

<style scoped>
.avatar-container {
  position: relative;
  cursor: pointer;
  border-radius: 50%;
  overflow: hidden;
  transition: transform 0.25s ease;
}
.avatar-container:hover { transform: scale(1.03); }
.avatar-img {
  background: linear-gradient(135deg, #f56c6c 0%, #c0392b 100%);
  font-size: 44px;
  color: #fff;
  display: block;
  object-fit: cover;
}
.avatar-overlay {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}
.info-row {
  display: flex;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px dashed var(--stroke);
  font-size: 14px;
}
.info-row:last-child { border-bottom: none; }
.info-label { color: var(--ink-3); display: flex; align-items: center; }
.info-value { color: var(--ink); font-weight: 500; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 账号用等宽小字（"页面内账号采用小字展示"），与昵称的视觉权重区分开 */
.info-account { font-size: 13px; font-weight: 400; color: var(--ink-2); font-family: Consolas, Monaco, monospace; }

/* ---- 表单页内提示（错误信息页内展示，不用弹窗） ---- */
.form-hint { font-size: 12px; color: var(--ink-3); line-height: 1.6; }

/* ---- 危险操作区（账号注销）：与 UserProfile.vue 同步，只把强调色换成管理员的红 ---- */
.danger-zone {
  max-width: 500px;
  margin-top: 4px;
  padding: 14px 16px;
  border: 1px solid #fbc4c4;
  border-radius: 8px;
  background: #fef8f8;
}
.danger-title { font-size: 14px; font-weight: 600; color: #f56c6c; }
.danger-desc { font-size: 12px; color: var(--ink-3); line-height: 1.8; margin: 6px 0 12px 0; }
html[data-theme="dark"] .danger-zone {
  background: #2b1718;
  border-color: #4a2425;
}

/* ---- 最近登录记录 ---- */
.login-log-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.login-log-empty { font-size: 13px; color: var(--ink-3); }
.login-log-row { padding: 6px 0; border-bottom: 1px dashed var(--stroke); }
.login-log-row:last-child { border-bottom: none; }
.login-log-time { font-size: 13px; color: var(--ink); display: flex; align-items: center; gap: 6px; }
.login-log-ip { font-size: 12px; color: var(--ink-3); margin-top: 2px; }

.mini-card { text-align: center; padding: 14px 6px; border-radius: 10px; background: var(--surface-2); transition: all 0.2s ease; }
.mini-card:hover { background: #fef0f0; transform: translateY(-2px); }
.mini-value { font-size: 22px; font-weight: bold; line-height: 1.2; margin-top: 6px; }
.mini-label { font-size: 12px; color: var(--ink-3); margin-top: 2px; }

.quick-action { text-align: center; padding: 18px 10px; border-radius: 10px; border: 1px solid var(--stroke); cursor: pointer; transition: all 0.25s ease; }
.quick-action:hover { background: #fef0f0; border-color: #f56c6c; transform: translateY(-2px); }
.qa-title { font-size: 13px; font-weight: 500; color: var(--ink); margin-top: 8px; }
.qa-desc { font-size: 11px; color: var(--ink-3); margin-top: 2px; }

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
    radial-gradient(140% 180% at 85% -30%, rgba(255, 255, 255, 0.35) 0%, transparent 55%),
    linear-gradient(135deg, #ff8a7a 0%, #e0473f 55%, #b91c1c 100%);
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
.avatar-img {
  border: 4px solid #fff;
  box-shadow: 0 10px 24px rgba(15, 61, 145, 0.2);
}
.profile-name {
  margin: 14px 0 8px;
  text-align: center;
  font-size: 20px;
  font-weight: 700;
  color: var(--ink);
}

.mini-card {
  text-align: center;
  padding: 16px 6px;
  border-radius: var(--r-md);
  border: 1px solid var(--stroke);
  transition:
    transform 0.22s var(--ease),
    box-shadow 0.22s var(--ease);
}
.mini-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-sm);
}
.mini-blue { background: linear-gradient(160deg, #f2f8ff, #e6f1ff); }
.mini-green { background: linear-gradient(160deg, #f2fdf6, #e4f8ec); }
.mini-amber { background: linear-gradient(160deg, #fffaf0, #fff2db); }
.mini-violet { background: linear-gradient(160deg, #f8f5ff, #efe8ff); }
.mini-value { font-size: 24px; font-weight: 700; line-height: 1.2; margin-top: 6px; }
.mini-label { font-size: 12px; color: var(--ink-3); margin-top: 2px; }

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
  border-color: var(--sky-200);
}
.qa-title { font-size: 13px; font-weight: 600; color: var(--ink); margin-top: 8px; }
.qa-desc { font-size: 11px; color: var(--ink-3); margin-top: 2px; }

/* 最近操作 */
.op-list { display: flex; flex-direction: column; }
.op-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 6px;
  border-bottom: 1px dashed #eef3fb;
  border-radius: 8px;
  transition: background 0.18s var(--ease);
}
.op-row:last-child { border-bottom: none; }
.op-row:hover { background: var(--sky-50); }
.op-action {
  flex: 1;
  min-width: 0;
  color: var(--ink-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.op-time { font-size: 12px; color: var(--ink-3); flex-shrink: 0; }
</style>