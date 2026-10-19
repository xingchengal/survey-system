<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <h2 class="auth-title"><BrandLogo :size="26" /> 问卷系统</h2>
      <p class="auth-subtitle">注册新账号</p>

      <!-- 服务端失败文案只落在这个 alert 里，不弹 ElMessage.error -->
      <el-alert
        v-if="serverError"
        :title="serverError"
        type="error"
        show-icon
        :closable="false"
        class="mb-16"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item label="账号" prop="username">
          <el-input
            v-model="form.username"
            placeholder="账号仅支持字母和数字，4~20个字符"
            size="large"
            maxlength="20"
            clearable
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="6~20 位，建议字母+数字+符号"
            size="large"
            maxlength="20"
            show-password
          />
          <div v-if="form.password" class="strength">
            <span class="strength-label">密码强度：</span>
            <div class="strength-bar">
              <i
                v-for="i in 3"
                :key="i"
                class="strength-seg"
                :class="{ on: i <= strength.level }"
                :style="i <= strength.level ? { background: strength.color } : {}"
              />
            </div>
            <span class="strength-text" :style="{ color: strength.color }">{{ strength.text }}</span>
          </div>
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
            size="large"
            maxlength="20"
            show-password
          />
        </el-form-item>

        <el-form-item label="手机号（选填）" prop="phone">
          <el-input
            v-model="form.phone"
            placeholder="仅用于信息采集，暂不支持手机号登录"
            size="large"
            maxlength="11"
            clearable
          />
        </el-form-item>

        <el-form-item>
          <div class="slider-wrap">
            <!-- props 必须用 w/h/l/r（写成 width/height 等于没传，尺寸会与后端画布不一致）；
                 :key="sliderToken" 换图时整体重建，避免"新 offset 配旧底图" -->
            <slide-verify
              ref="slideRef"
              :key="sliderToken || 'captcha'"
              :w="sliderW"
              :h="sliderH"
              :l="sliderL"
              :r="sliderR"
              :imgs="sliderImgs"
              :offset="sliderOffset"
              :accuracy="accuracy"
              slider-text="向右滑动完成验证"
              @success="onSliderSuccess"
              @fail="onSliderFail"
              @again="onSliderAgain"
            />
            <p class="slider-tip">
              <span v-if="sliderPassed" class="ok">✅ 验证通过</span>
              <span v-else-if="sliderLoading" class="text-gray">验证码加载中…</span>
              <span v-else-if="sliderChecking" class="text-gray">正在校验…</span>
              <span v-else class="text-gray">拖动滑块完成验证</span>
            </p>
          </div>
        </el-form-item>

        <el-form-item>
          <!-- 置灰条件：表单未过 / 滑块未通过；提交中 loading 防重复提交 -->
          <el-button
            class="w-full"
            type="primary"
            size="large"
            :disabled="!canSubmit"
            :loading="submitting"
            @click="handleRegister"
          >
            {{ submitting ? '注册中…' : '注 册' }}
          </el-button>
        </el-form-item>

        <div class="auth-footer">
          <span class="text-gray">已有账号？</span>
          <!-- 带上 redirect：从"仅限登录"的问卷跳过来时，登录后还要回那份问卷 -->
          <el-button type="primary" link @click="goLogin">去登录</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import SlideVerify from 'vue3-slide-verify'
// 该库只提供局部引用的方式，样式必须单独引
import 'vue3-slide-verify/dist/style.css'
import api from '../api'
// 品牌标识（与登录页、侧栏 logo 同一图形）
import BrandLogo from '../components/BrandLogo.vue'

/** 账号规则：字母+数字，4~20 位（与后端 RegisterRequest 的 @Pattern **必须**一致） */
const ACCOUNT_RE = /^[A-Za-z0-9]{4,20}$/
/** 国内手机号：11 位、1 开头、第二位 3~9（与后端 @Pattern 一致；空值合法） */
const PHONE_RE = /^1[3-9]\d{9}$/

export default {
  name: 'RegisterView',
  components: { SlideVerify, BrandLogo },
  data() {
    // 确认密码的校验依赖 form.password，所以写成函数形式（每次校验都读最新值）
    const validateConfirm = (rule, value, callback) => {
      if (!value) return callback(new Error('请再次输入密码'))
      if (value !== this.form.password) return callback(new Error('两次输入的密码不一致'))
      callback()
    }
    const validatePhone = (rule, raw, callback) => {
      // 先 trim 再判：只打一个空格等于"没填"（选填），与后端 ContactRequest 的 ^\s*$ 放行口径一致
      const value = String(raw == null ? '' : raw).trim()
      if (!value) return callback()
      if (!PHONE_RE.test(value)) return callback(new Error('请输入 11 位国内手机号'))
      callback()
    }

    return {
      form: { username: '', password: '', confirmPassword: '', phone: '' },
      rules: {
        username: [
          { required: true, message: '请输入账号', trigger: 'blur' },
          { pattern: ACCOUNT_RE, message: '账号仅支持字母和数字，4~20个字符', trigger: 'blur' }
        ],
        password: [
          { required: true, message: '请输入密码', trigger: 'blur' },
          { min: 6, max: 20, message: '密码长度需在 6~20 位之间', trigger: 'blur' }
        ],
        confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }],
        phone: [{ validator: validatePhone, trigger: 'blur' }]      },

      submitting: false,
      /** 服务端返回的错误文案（页内展示，不弹窗） */
      serverError: '',

      // w/h/l/r 必须与后端同一口径：后端按 [L+10, w−(L+10)]（L = l + 2r + 3）生成并校验 offset，
      //   所以取图时把实际尺寸带上（见 loadCaptcha）。
      sliderW: 320,
      sliderH: 160,
      sliderL: 46,
      // 方块圆角 r 必须与后端 COMPONENT_R 一致，否则 offset 合法区间算出来就不对
      sliderR: 10,
      /** 后端下发的缺口位置（组件的 offset 属性） */
      sliderOffset: 0,
      /** 一次性凭证（后端签发，60 秒有效、注册时消费） */
      sliderToken: '',
      /** 只有**后端**判定通过才置 true（组件只按它自己的缺口判定，不能代表后端认定了"对准"） */
      sliderPassed: false,
      sliderLoading: false,
      sliderChecking: false,
      /** 组件自带的"非人为操作"检测精度（默认 5；用 3 更灵敏，触发 again 后自动换图） */
      accuracy: 3,
      // ★ 必须填后端下发的底图（loadCaptcha 里 sliderImgs = [data.bgImage]）：留空组件会去第三方图床取图，
      //   断网就永远停在"加载中"，而且页面看到的图必须与服务端判定用的是同一张。
      sliderImgs: []
    }
  },
  computed: {
    /** 表单规则不通过或滑块未通过都置灰；这里用与 rules 同源的正则做轻量判断 */
    canSubmit() {
      if (this.submitting) return false
      if (!this.sliderPassed) return false
      const f = this.form
      if (!ACCOUNT_RE.test(f.username)) return false
      if (!f.password || f.password.length < 6 || f.password.length > 20) return false
      if (f.password !== f.confirmPassword) return false
      if (f.phone && !PHONE_RE.test(f.phone)) return false
      return true
    },
    /** 密码强度（弱/中/强）：按字符种类与长度分档，仅作提示，真正的门槛是后端的 6~20 位 */
    strength() {
      const p = this.form.password || ''
      if (!p) return { level: 0, text: '', color: '#909399' }

      let kinds = 0
      if (/[a-z]/.test(p)) kinds++
      if (/[A-Z]/.test(p)) kinds++
      if (/\d/.test(p)) kinds++
      if (/[^A-Za-z0-9]/.test(p)) kinds++

      let level = 1
      if (p.length >= 8 && kinds >= 2) level = 2
      if (p.length >= 10 && kinds >= 3) level = 3

      const map = {
        1: { level: 1, text: '弱', color: '#f56c6c' },
        2: { level: 2, text: '中', color: '#e6a23c' },
        3: { level: 3, text: '强', color: '#67c23a' }
      }
      return map[level]
    }
  },
  mounted() {
    this.loadCaptcha()
  },
  methods: {
    /** 去登录：把 redirect 透传过去（注册完 → 登录 → 回原问卷） */
    goLogin() {
      const redirect = this.$route.query.redirect
      this.$router.push({
        path: '/login',
        query: typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
          ? { redirect } : {}
      })
    },
    /**
     * 取一张新的滑块验证码：mounted、拖动失败、判定失败、提交报错后都要换图。
     * 不监听组件的 @refresh：它只复位滑块、不换图，换图靠 :key 重建 + 新 bgImage。
     */
    async loadCaptcha() {
      this.sliderLoading = true
      this.sliderPassed = false
      this.sliderToken = ''
      try {
        // 带上组件实际用的 w/l，后端用同一套坐标判定（不带会退到后端默认值）
        const data = await api.captcha.slider({ w: this.sliderW, l: this.sliderL })
        // 先填底图与 offset，最后再改 sliderToken：:key 重建时组件读到的必须已是新图、新缺口
        this.sliderImgs = [data.bgImage]
        this.sliderOffset = data.offset
        this.sliderToken = data.token
        // refresh() 让组件按新 offset 重新摆位
        this.$nextTick(() => {
          if (this.$refs.slideRef) this.$refs.slideRef.refresh()
        })
      } catch {
        // 取图失败保持"未通过"，按钮继续置灰，免得提交一个必然被拒的表单
        this.serverError = '验证码加载失败，请点击刷新或稍后重试'
      } finally {
        this.sliderLoading = false
      }
    },

    /**
     * 拖动结束（组件回调）：是否对齐由后端判定，前端只上报 left。
     * 组件只按它自己画的缺口判定，与后端下发的 offset 无关，不能拿它当"通过"。
     * @param {{timestamp:number, left:number}} detail left 即方块位置
     */
    async onSliderSuccess(detail) {
      // ★ 必须用 Number.isFinite：组件给的是 parseFloat(style.left)，时序不巧时为空串 → NaN，
      //   而 typeof NaN === 'number' 为真，会把它序列化成 "NaN" 让后端报参数错误。
      const rawLeft = detail && detail.left
      const left = Number.isFinite(rawLeft) ? rawLeft : null
      if (left === null) {
        // 拿不到有效 left 就换图，否则用户会卡在"拖了但按钮一直灰"且毫无提示
        console.warn('[滑块] 组件回调未给出有效 left，已重新加载验证码。detail =', detail)
        this.serverError = '这次拖动没被正确识别，已刷新验证码，请重新拖动'
        this.sliderPassed = false
        await this.loadCaptcha()
        return
      }
      this.sliderChecking = true
      try {
        const r = await api.captcha.verify({ token: this.sliderToken, left })
        if (r && r.passed) {
          this.sliderPassed = true
          this.serverError = ''
          // 通过后立刻校验一遍表单，让"哪一项不合法"的红字马上显出来
          if (this.$refs.formRef) this.$refs.formRef.validate().catch(() => {})
        } else if (r && r.invalidLeft) {
          // 后端明确说"收到的 left 不合法"（NaN/缺失这类）→ 换题重来
          this.serverError = '这次拖动没被正确识别，已刷新验证码，请重新拖动'
          this.sliderPassed = false
          await this.loadCaptcha()
        } else {
          // 后端说没对上：立刻换一张图让用户重试
          this.sliderPassed = false
          await this.loadCaptcha()
        }
      } catch (e) {
        // 按状态码分流：429（限流）明确提示且不自动换图（换图本身又是一次请求），
        //   其余保留后端 message 并换图重来；verify 传了 silent，文案只能由这里给。
        const status = e && e.status
        if (status === 429) {
          this.serverError = '操作过于频繁，请稍等几秒再拖动滑块'
        } else {
          this.serverError = (e && e.message) || '校验失败，已重新加载验证码，请再拖一次'
          await this.loadCaptcha()   // 凭证这类问题（失效/过期）→ 换题重来才有意义
        }
        this.sliderPassed = false
      } finally {
        this.sliderChecking = false
      }
    },

    /** 组件自己判定没对上 → 换图重来（不计数：每次失败都已换图） */
    onSliderFail() {
      this.sliderPassed = false
      this.loadCaptcha()
    },

    /** 组件判定为"非人为操作"（脚本/匀速拖动）→ 换题重来。 */
    onSliderAgain() {
      this.sliderPassed = false
      this.loadCaptcha()
    },

    async handleRegister() {
      this.serverError = ''
      // 1) 表单校验（按钮虽然置灰了，但键盘回车等路径仍可能进来，所以再挡一次）
      try {
        await this.$refs.formRef.validate()
      } catch {
        return
      }
      if (!this.sliderPassed || !this.sliderToken) {
        this.serverError = '请先完成滑块验证'
        return
      }

      this.submitting = true
      try {
        // 只带凭证 token：判定已在 /api/captcha/verify 做完，注册接口只认"已通过"并消费它
        await api.user.register(
          {
            username: this.form.username,
            password: this.form.password,
            nickname: this.form.username, // 昵称留空时后端会用账号兜底；这里显式传显得更明确
            // trim 后再发，与 validatePhone 及后端 phone.trim() 同一口径
            phone: this.form.phone.trim()
          },
          { token: this.sliderToken }
        )
        ElMessage.success('注册成功，即将跳转到登录页')
        // 透传 redirect：注册完 → 登录 → 回原问卷
        setTimeout(() => this.goLogin(), 900)
      } catch (e) {
        // silent:true → http.js 不弹窗，但错误信息仍在 e.message 上，直接渲染到页内 alert
        this.serverError = (e && e.message) || '注册失败，请稍后重试'
        // 提交返回业务报错后，滑块验证状态失效，需要重新完成验证
        await this.loadCaptcha()
      } finally {
        this.submitting = false
      }
    }
  }
}
</script>

<style scoped>
.auth-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 0;
  overflow: hidden;
  background:
    radial-gradient(900px 480px at 88% 6%, #eaf5ff 0%, transparent 60%),
    radial-gradient(760px 420px at 8% 30%, #e7edff 0%, transparent 55%),
    linear-gradient(160deg, #f7fbff 0%, #eef5ff 55%, #e9f1ff 100%);
}
.auth-page::before,
.auth-page::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  filter: blur(10px);
  pointer-events: none;
}
.auth-page::before {
  width: 420px;
  height: 420px;
  right: -120px;
  top: -140px;
  background: radial-gradient(closest-side, rgba(86, 204, 242, 0.35), transparent);
}
.auth-page::after {
  width: 380px;
  height: 380px;
  left: -100px;
  bottom: -160px;
  background: radial-gradient(closest-side, rgba(99, 102, 241, 0.24), transparent);
}
.auth-card {
  position: relative;
  z-index: 1;
  width: 440px;
  padding: 14px 18px;
  border-radius: 24px;
  border: 1px solid rgba(255, 255, 255, 0.85);
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  box-shadow: 0 24px 60px rgba(15, 61, 145, 0.14);
}
.auth-title {
  text-align: center;
  margin: 0 0 6px 0;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 0.2px;
  color: var(--ink);
}
.auth-subtitle {
  text-align: center;
  margin: 0 0 22px 0;
  color: var(--ink-3);
  font-size: 14px;
}
.auth-footer {
  text-align: center;
  font-size: 14px;
}
.strength {
  display: flex;
  align-items: center;
  margin-top: 6px;
  font-size: 12px;
}
.strength-label {
  color: var(--ink-3);
}
.strength-bar {
  display: flex;
  gap: 4px;
  margin: 0 8px;
}
.strength-seg {
  display: block;
  width: 28px;
  height: 6px;
  border-radius: 3px;
  background: #e4ecf9;
  transition: background 0.2s var(--ease);
}
.strength-text {
  font-weight: 600;
}
.slider-wrap {
  width: 100%;
}
.slider-tip {
  margin: 8px 0 0 0;
  font-size: 12px;
  color: var(--ink-3);
}
.slider-tip .ok {
  color: #16a34a;
  font-weight: 600;
}
</style>
