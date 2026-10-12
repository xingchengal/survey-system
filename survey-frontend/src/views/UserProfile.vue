<template>
  <div style="max-width: 1200px; margin: 0 auto;">
    <el-row :gutter="20">
      <!-- 左侧：用户信息卡 -->
      <el-col :span="8">
        <el-card :body-style="{ padding: '24px' }">
          <div style="display: flex; justify-content: center;">
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

          <h2 style="margin: 12px 0 6px 0; text-align: center;">{{ user.nickname || user.username }}</h2>
          <div class="text-center">
            <el-tag type="info" effect="dark">普通用户</el-tag>
          </div>

          <el-divider />

          <!-- 账号是唯一标识、不可修改，页面上用小字单独展示；昵称才是可编辑的显示名。
               ★ 两者并列时若不把账号的视觉权重压下去，很容易被读成同一个东西。 -->
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

          <!-- 账号安全信息展示（最近登录时间 / 登录 IP，数据取自系统登录日志）。
               ★ 如实说明两点：
                 ① 这里展示的是**最近 10 次**登录记录，第一行就是本次登录（登录成功即写日志），
                    所以"上次登录"看第二行更符合直觉 —— 卡片上明写了这句话；
                 ② IP 优先取 X-Forwarded-For，**客户端可伪造**，仅供你自己核对异地登录，
                    不得当安全判决依据（口径见后端 util/WebUtils.getClientIp 的注释）。 -->
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

      <!-- 右侧：用户内容 -->
      <el-col :span="16">
        <!-- 我的数据 -->
        <el-card class="mb-20">
          <template #header>
            <span class="text-bold">📊 我的数据</span>
          </template>
          <el-row :gutter="12">
            <el-col :span="6">
              <div class="mini-card">
                <el-icon :size="22" color="#409eff"><Document /></el-icon>
                <div class="mini-value color-primary">{{ myStats.total }}</div>
                <div class="mini-label">我的问卷</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-card">
                <el-icon :size="22" color="#67c23a"><Promotion /></el-icon>
                <div class="mini-value color-success">{{ myStats.published }}</div>
                <div class="mini-label">已发布</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-card">
                <el-icon :size="22" color="#e6a23c"><EditPen /></el-icon>
                <div class="mini-value color-warning">{{ myStats.draft }}</div>
                <div class="mini-label">草稿</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-card">
                <el-icon :size="22" color="#f56c6c"><ChatDotRound /></el-icon>
                <div class="mini-value color-danger">{{ myStats.responses }}</div>
                <div class="mini-label">收到答卷</div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- 常用功能 -->
        <el-card class="mb-20">
          <template #header>
            <span class="text-bold">⚡ 常用功能</span>
          </template>
          <el-row :gutter="12">
            <el-col :span="8">
              <div class="quick-action" @click="$router.push('/my/surveys')">
                <el-icon :size="26" color="#409eff"><Document /></el-icon>
                <div class="qa-title">我的问卷</div>
                <div class="qa-desc">创建 / 管理</div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="quick-action" @click="$router.push('/my/dashboard')">
                <el-icon :size="26" color="#67c23a"><DataBoard /></el-icon>
                <div class="qa-title">数据看板</div>
                <div class="qa-desc">查看数据</div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="quick-action" @click="$router.push('/my/recycle')">
                <el-icon :size="26" color="#f56c6c"><Delete /></el-icon>
                <div class="qa-title">回收站</div>
                <div class="qa-desc">恢复 / 删除</div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- 账号设置 -->
        <el-card>
          <el-tabs v-model="activeTab">
            <el-tab-pane label="基本资料" name="profile">
              <!-- 表单校验的错误信息在页面内展示，不使用 alert 弹窗（el-form 的 rules + 页内红字，
                   与 Login/Register 两页同一套做法）。
                   ★ 这只是"体验层"：后端 UserService 里同样的规则会再判一次。 -->
              <el-form ref="profileFormRef" class="form-panel" :model="profileForm" :rules="profileRules" label-width="100px">
                <el-form-item label="账号">
                  <!-- 账号只读：用 :model-value 而不是 v-model（不再需要双向绑定），
                       免得将来有人误以为"这个输入框还能改" -->
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
                  <!-- ★ 注意两个数不一样，别抄错：
                       注册页新密码是 6~20（RegisterRequest），
                       而改密码这条路后端是 6~64（UserService.updatePassword 的上限）——
                       两者本来就不同，这里按本页实际调用的接口写 6~64。 -->
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

            <!-- 这个页签只放**不可逆操作**，所以页签名直接叫"账号注销"更准确。 -->
            <el-tab-pane label="账号注销" name="privacy">
              <div class="form-panel">
                <div class="danger-zone">
                  <div class="danger-title">注销账号</div>
                  <!-- ★ 后果要**说全**，包括"不会发生什么"。用户最怕的是"我注销了，
                       别人填过的答卷会不会一起没了" —— 明确告诉他不会。 -->
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

    <!-- ★ 头像裁剪。不裁剪的话原图会被原样转成 base64 存库（约 1.4MB JSON），
         而它每次都随 GET /api/user/profile 返回。 -->
    <avatar-cropper v-model="cropVisible" :src="rawImage" @confirm="onAvatarCropped" />

    <!-- ★ 注销的二次确认弹窗：必须**输入密码**。
         这是不可逆的账号级操作，只凭一枚可能被盗的 token 就能注销的风险太高
         （后端 UserService.deleteAccount 也会再验一次，前端这只是体验层）。 -->
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
// ★ 统一接口层（src/api）：本页所有请求（资料/统计/头像/昵称/联系方式/密码/登录日志）
//   全部走它；头像上传仍是 FileReader 转 base64 得到 data URL 后交给接口层。
import api from '../api'
// 登录态的读/改/清统一走 utils/auth
import { getUser, setUser, clearAuth } from '../utils/auth'
// 自研头像裁剪组件（零新增依赖，见组件内注释）
import AvatarCropper from '../components/AvatarCropper.vue'
// 本页图标均为模板标签形式，已由 main.js 全局注册，无需 import

/**
 * 联系方式校验规则 —— ★ 与后端保持一致，改这里必须同批核后端。
 *
 * 后端口径（集中在 dto/ContactRequest 的注解上）：
 *   · phone：`^\s*$|^1[3-9]\d{9}$`  手机号格式不正确（应为 11 位国内手机号）/ ≤20 字
 *   · email：`^\s*$|^[\w.-]+@[\w.-]+\.[A-Za-z]{2,}$`  邮箱格式不正确 / ≤100 字
 * ★ 两处正则**逐字相同**是有意的：否则前端提示一套、后端拒绝另一套，
 *   用户看到的现象会是"提示我改对了却还是提交失败"。
 * ★ 空值合法：需求允许"清空该项"，所以两条规则都是 `if (!value) return callback()`；
 *   后端正则里的 `\s*` 分支是同一个意思的**服务端版本**（见 ContactRequest 的类注释：
 *   只写 `^$` 时，用户输入一个空格去"清空"会拿到 400「格式不正确」）。
 */
const PHONE_RE = /^1[3-9]\d{9}$/
const EMAIL_RE = /^[\w.-]+@[\w.-]+\.[A-Za-z]{2,}$/

// 图标已在 main.js 全局注册，不再重复注册（Camera 是 HTML 保留标签名）
export default {
  components: { AvatarCropper },
  data() {
    // ★ 每个 validator 都写成"先判空、再判格式"，空值一律放行（= 清空该项）
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
          // ★ 6~64：与后端 UserService.updatePassword 的上限**一致**（不是注册页的 6~20，见模板里的说明）
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
      // rawImage 是原图 data URL，cropVisible 控制裁剪组件显隐
      cropVisible: false,
      rawImage: '',
      // 各按钮的"防重复提交"状态
      savingNickname: false,
      savingContact: false,
      savingPassword: false,
      // 最近登录记录
      loginLogs: [],
      loginLogLoading: false,
      // ★ 账号注销：二次确认弹窗 + 密码确认
      deleteDialogVisible: false,
      deleteForm: { password: '' },
      deleteFormRef: null,
      deleteRules: {
        password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
      },
      deletingAccount: false,
      myStats: { total: 0, published: 0, draft: 0, responses: 0 }
    }
  },
  computed: {
    avatarText() {
      const name = this.user.nickname || this.user.username || '用'
      return name.charAt(0).toUpperCase()
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
      } catch {
        // ★ 这里保留 catch 只为挡住 rejection（提示已由接口层统一弹出），
        //   后面的表单初始化不再执行
      }
    },

    async loadMyStats() {
      try {
        this.myStats = await api.survey.myStatistics()
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

    triggerUpload() { this.$refs.fileInput.click() },

    /**
     * 选图 → 校验 → 交给裁剪组件。
     *
     * ★ 三处口径必须与后端对齐，否则会出现"前端拦下的和后端说的不一样"：
     *   ① **大小**：这里按**原始文件大小**拦 2MB（与后端 AVATAR_MAX_BYTES 同值）；
     *      后端那条界算的是**解码后**的字节数，所以真正的判定仍在后端。
     *   ② **校验失败时 input 必须被重置**：`e.target.value = ''` 要写在两个 return 之前，
     *      否则"选了一张 5MB 的图被拒"后再选**同一个文件**，change 事件不会触发
     *      （value 没变），用户会以为点了没反应。
     *   ③ **格式白名单只认 PNG/JPG/GIF**：它拦不住"把 a.exe 改名成 a.png"，
     *      所以这里仍只是**体验层**（真正的判定在后端：解码后比对图片魔数），
     *      只为"更早给出提示"，白名单与后端保持一致。
     */
    handleFileChange(e) {
      const file = e.target.files[0]
      // ★ 先清空，再判断 —— 否则校验失败时 input 的 value 留在原地（见上面 ②）
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
        // 同步 localStorage 里的登录用户，否则刷新后顶栏还是旧头像
        // 读-改-写本地缓存统一走 utils/auth（getUser 自带脏值兜底、setUser 统一 key）
        const localUser = getUser() || {}
        localUser.avatar = dataUrl
        setUser(localUser)
      } catch {
        // ★ 失败时**保留** rawImage 与裁剪窗口是没用的（组件已自己关闭），
        //   所以这里只吞掉异常；提示已由接口层统一弹出，用户重新点头像即可
      }
    },

    async saveNickname() {
      // ★ 校验交给 el-form 的 rules（页内红字）；这里只在"校验通过"后才发请求
      if (!(await this.validateForm(this.$refs.profileFormRef))) return
      this.savingNickname = true
      try {
        // 后端失败抛 BizException → 400 + message，提示由 http.js 统一弹出
        await api.user.updateNickname(this.profileForm.nickname.trim())
        ElMessage.success('昵称修改成功')
        this.user.nickname = this.profileForm.nickname.trim()
        // 读-改-写本地缓存统一走 utils/auth（getUser 自带脏值兜底、setUser 统一 key）
        const localUser = getUser() || {}
        localUser.nickname = this.user.nickname
        setUser(localUser)
      } catch {
        // 失败提示由接口层统一弹出（后端对昵称只校验"非空 / ≤50 字 / 用户存在"，
        // 没有重名校验），这里只做流程控制
      } finally {
        this.savingNickname = false
      }
    },

    async saveContact() {
      if (!(await this.validateForm(this.$refs.contactFormRef))) return
      this.savingContact = true
      try {
        // ★ 传 trim 后的值：后端也会 trim 并把空白归一成 NULL，
        //   但本地也 trim 一次，避免"保存成功后输入框里还留着一个尾空格"的错觉
        const phone = this.contactForm.phone.trim()
        const email = this.contactForm.email.trim()
        await api.user.updateContact(phone, email)
        ElMessage.success('联系方式保存成功')
        this.user.phone = phone
        this.user.email = email
        this.contactForm.phone = phone
        this.contactForm.email = email
      } catch {
        // 失败提示由接口层统一弹出，这里不再重复提示
      } finally {
        this.savingContact = false
      }
    },

    async savePassword() {
      if (!(await this.validateForm(this.$refs.pwdFormRef))) return
      this.savingPassword = true
      try {
        await api.user.updatePassword(this.pwdForm.oldPassword, this.pwdForm.newPassword)
        // ★ 文案必须如实：改密码**不会**在服务端吊销 refresh token，
        //   所以这里只能说"请重新登录"，**不能说**"所有设备已退出"那一类话。
        ElMessage.success('密码修改成功，请重新登录')
        this.pwdForm = { oldPassword: '', newPassword: '', confirmPassword: '' }
        if (this.$refs.pwdFormRef) this.$refs.pwdFormRef.clearValidate()
        setTimeout(() => {
          // 清本地登录态并回登录页（token 失效后接口层也会兜底跳转，这里是为了立刻生效；
          // 清除走 utils/auth.clearAuth，统一 key + 一定成对清除）
          clearAuth()
          this.$router.push('/login')
        }, 1500)
      } catch {
        // 失败提示由接口层统一弹出（如"原密码不正确"），表单内容保留不动，方便重试
      } finally {
        this.savingPassword = false
      }
    },

    /**
     * 统一触发表单校验：通过返回 true，失败返回 false（错误文案由 el-form 显示在各自字段下方）。
     * ★ 抽出来的理由：三个保存方法要写同样的 `validate((ok) => ...)` 回调，
     *   写成 Promise 后调用方是清爽的 `if (!(await ...)) return`。
     */
    validateForm(formRef) {
      if (!formRef) return Promise.resolve(true)
      return new Promise((resolve) => {
        formRef.validate((valid) => resolve(Boolean(valid)))
      })
    },

    // ==================== 账号注销 ====================

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
     * 真正执行注销。
     * ★ 成功之后**必须**清本地登录态并回登录页：
     *   后端把账号软删了，此刻手里那枚 access token 已经无效（JwtAuthFilter 每个请求都会
     *   查到"账号不存在或已注销"→ 401），而 refresh token 也被后台吊销了 ——
     *   所以这里不清的话，用户会停留在一个"每点一下都报错"的页面上。
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
.avatar-container {
  position: relative;
  cursor: pointer;
  border-radius: 50%;
  overflow: hidden;
  transition: transform 0.25s ease;
}
.avatar-container:hover { transform: scale(1.03); }
.avatar-img {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
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
.info-row { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px dashed #f0f2f5; font-size: 14px; }
.info-row:last-child { border-bottom: none; }
.info-label { color: #909399; display: flex; align-items: center; }
.info-value { color: #303133; font-weight: 500; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* 账号用等宽小字（"页面内账号采用小字展示"），与昵称的视觉权重区分开 */
.info-account { font-size: 13px; font-weight: 400; color: #606266; font-family: Consolas, Monaco, monospace; }

/* ---- 表单页内提示（错误信息页内展示，不用弹窗） ---- */
.form-hint { font-size: 12px; color: #909399; line-height: 1.6; }

/* ---- 危险操作区（账号注销）：用红边框与上面的普通设置拉开距离，
        让"这是不可逆操作"在视觉上先说出来 ---- */
.danger-zone {
  max-width: 500px;
  margin-top: 4px;
  padding: 14px 16px;
  border: 1px solid #fbc4c4;
  border-radius: 8px;
  background: #fef8f8;
}
.danger-title { font-size: 14px; font-weight: 600; color: #f56c6c; }
.danger-desc { font-size: 12px; color: #909399; line-height: 1.8; margin: 6px 0 12px 0; }

/* ---- 最近登录记录 ---- */
.login-log-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.login-log-empty { font-size: 13px; color: #909399; }
.login-log-row { padding: 6px 0; border-bottom: 1px dashed #f0f2f5; }
.login-log-row:last-child { border-bottom: none; }
.login-log-time { font-size: 13px; color: #303133; display: flex; align-items: center; gap: 6px; }
.login-log-ip { font-size: 12px; color: #909399; margin-top: 2px; }

.mini-card { text-align: center; padding: 14px 6px; border-radius: 10px; background: #fafbfc; transition: all 0.2s ease; }
.mini-card:hover { background: #f0f7ff; transform: translateY(-2px); }
.mini-value { font-size: 22px; font-weight: bold; line-height: 1.2; margin-top: 6px; }
.mini-label { font-size: 12px; color: #909399; margin-top: 2px; }

.quick-action { text-align: center; padding: 18px 10px; border-radius: 10px; border: 1px solid #f0f2f5; cursor: pointer; transition: all 0.25s ease; }
.quick-action:hover { background: #f0f7ff; border-color: #409eff; transform: translateY(-2px); }
.qa-title { font-size: 13px; font-weight: 500; color: #303133; margin-top: 8px; }
.qa-desc { font-size: 11px; color: #909399; margin-top: 2px; }
</style>