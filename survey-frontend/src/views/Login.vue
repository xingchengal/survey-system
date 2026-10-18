<template>
  <div class="auth-page">
    <div class="auth-layout">
      <!-- 装饰插画（纯 CSS，无图片请求）：≥1100px 才显示，窄屏隐藏只留表单卡 -->
      <div class="auth-illustration" aria-hidden="true">
        <div class="ill-stage">
          <div class="ill-card">
            <div class="ill-card-title"></div>
            <div class="ill-row">
              <span class="ill-check on"></span>
              <span class="ill-line w70"></span>
            </div>
            <div class="ill-row">
              <span class="ill-check"></span>
              <span class="ill-line w50"></span>
            </div>
            <div class="ill-row">
              <span class="ill-check"></span>
              <span class="ill-line w62"></span>
            </div>
            <div class="ill-submit"></div>
          </div>
          <div class="ill-stat">
            <div class="ill-stat-num">98%</div>
            <div class="ill-stat-label">回收率</div>
          </div>
          <span class="ill-bubbles b1"></span>
          <span class="ill-bubbles b2"></span>
          <span class="ill-bubbles b3"></span>
        </div>
        <h3 class="ill-title">更懂你的每一次提问</h3>
        <p class="ill-desc">创建 · 发布 · 回收 · 分析，一站式问卷系统</p>
      </div>

      <el-card class="auth-card">
        <h2 class="auth-title">
          <BrandLogo :size="28" />
          <span>问卷系统</span>
        </h2>
        <p class="auth-subtitle">登录你的账号</p>

      <!-- ★ 与注册页同一套口径：错误信息在表单区域展示，不使用弹窗 alert。
           登录失败（账号或密码错误）就落在这里，**不弹 ElMessage.error**。 -->
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
            placeholder="请输入账号"
            size="large"
            maxlength="20"
            clearable
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <!-- 密码框的显示/隐藏切换用 Element Plus 的 show-password -->
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            maxlength="64"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <div class="login-row">
          <!-- 「记住我」用**双 token** 实现：勾选后额外下发 7 天的 refresh token（可吊销、一次性轮换），
               access token 本身仍是 12 小时 —— 理由见 RefreshTokenService 的类注释。 -->
          <el-checkbox v-model="form.rememberMe">记住我（7 天内免登录）</el-checkbox>
          <!-- "忘记密码"未实现（后端没有重置端点），所以这里刻意不放一个点了没反应的链接 ——
               那比没有更糟。 -->
        </div>

        <el-form-item>
          <!-- 表单校验不通过时按钮置灰，提交时显示加载状态 -->
          <el-button
            class="w-full"
            type="primary"
            size="large"
            :disabled="!canSubmit"
            :loading="submitting"
            @click="handleLogin"
          >
            {{ submitting ? '登录中…' : '登 录' }}
          </el-button>
        </el-form-item>

        <div class="auth-footer">
          <span class="text-gray">没有账号？</span>
          <el-button type="primary" link @click="$router.push('/register')">去注册</el-button>
        </div>
      </el-form>
    </el-card>
    </div>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import api from '../api'
import { setAuth, homePathFor } from '../utils/auth'
// 品牌标识（与侧栏 logo、favicon 同一图形，替换原来的 📋 emoji）
import BrandLogo from '../components/BrandLogo.vue'

/** 账号规则：字母+数字，4~20 位（与后端 UserService.ACCOUNT_PATTERN 一致） */
const ACCOUNT_RE = /^[A-Za-z0-9]{4,20}$/

export default {
  name: 'LoginView',
  components: { BrandLogo },
  data() {
    return {
      form: { username: '', password: '', rememberMe: false },
      rules: {
        username: [
          { required: true, message: '请输入账号', trigger: 'blur' },
          // ★★ 这条规则**只对符合新规的账号成立**：本项目库里存有历史账号（含中文、2 位长），
          //    后端默认开启严格校验（app.strict-username-check），所以前端与后端保持一致。
          //    这带来一个**有意的取舍**：若后端把开关关掉而前端仍拦，旧账号用户会被前端挡住；
          //    相比之下，"放过一个后端必然拒绝的请求"更糟，所以选择对齐后端默认值。
          { pattern: ACCOUNT_RE, message: '账号仅支持字母和数字，4~20个字符', trigger: 'blur' }
        ],
        password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
      },
      submitting: false,
      /** 服务端返回的错误文案（页内展示，不弹窗） */
      serverError: ''
    }
  },
  computed: {
    canSubmit() {
      if (this.submitting) return false
      return ACCOUNT_RE.test(this.form.username) && this.form.password.length > 0
    }
  },
  methods: {
    async handleLogin() {
      this.serverError = ''
      try {
        await this.$refs.formRef.validate()
      } catch {
        return
      }

      this.submitting = true
      try {
        const res = await api.user.login({
          username: this.form.username,
          password: this.form.password,
          rememberMe: this.form.rememberMe
        })
        // ★ 一次性写入：access token、用户信息、以及（勾了记住我才有的）refresh token。
        //   走 utils/auth.setAuth 而不是直接写 localStorage —— 它会保证"**没勾**记住我时主动清掉旧的那枚"，
        //   否则上次登录残留的 refresh token 会让"这次没勾"也依然能续期 7 天（与用户选择不符）。
        setAuth(res.token, res.user, res.refreshToken)

        // ★ 首页按角色区分（管理员 → 系统总览，普通用户 → 个人看板），统一由 homePathFor 决定。
        //   ★ 注意：这里不判断 data.success —— 后端失败是 400（非 2xx），
        //     http.js 会抛 ApiError，根本走不到这一行。
        ElMessage.success('登录成功')
        this.$router.push(homePathFor(res.user && res.user.role))
      } catch (e) {
        // 后端对"账号不存在"与"密码错误"返回**同一句**「账号或密码错误」，
        //   ★ 前端**不要**改写文案、也不要根据任何差异给出不同提示，否则等于帮人做账号探测。
        this.serverError = (e && e.message) || '登录失败，请稍后重试'
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
  overflow: hidden;
  background:
    radial-gradient(900px 480px at 12% 8%, #eaf5ff 0%, transparent 60%),
    radial-gradient(760px 420px at 92% 22%, #e7edff 0%, transparent 55%),
    linear-gradient(160deg, #f7fbff 0%, #eef5ff 55%, #e9f1ff 100%);
}

/* 插画 + 表单卡的双栏容器（窄屏自动只剩表单卡并居中） */
.auth-layout {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 72px;
  width: 100%;
  max-width: 1160px;
  padding: 24px;
}
/* 两个柔光球：只做氛围，不参与点击 */
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
  left: -120px;
  top: -140px;
  background: radial-gradient(closest-side, rgba(86, 204, 242, 0.35), transparent);
}
.auth-page::after {
  width: 380px;
  height: 380px;
  right: -100px;
  bottom: -160px;
  background: radial-gradient(closest-side, rgba(99, 102, 241, 0.24), transparent);
}
.auth-card {
  position: relative;
  z-index: 1;
  width: 440px;
  flex-shrink: 0;
  padding: 14px 18px;
  border-radius: 24px;
  border: 1px solid rgba(255, 255, 255, 0.85);
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  box-shadow: 0 24px 60px rgba(15, 61, 145, 0.14);
}
.auth-title {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
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
.login-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

/* ==================== 左侧装饰插画（纯 CSS） ==================== */
.auth-illustration {
  display: none;
  flex: 1;
  max-width: 480px;
}

@media (min-width: 1100px) {
  .auth-illustration {
    display: block;
  }
}

.ill-stage {
  position: relative;
  height: 320px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 28px;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.88), rgba(231, 243, 255, 0.68));
  box-shadow: 0 22px 50px rgba(15, 61, 145, 0.12);
}
.ill-card {
  position: absolute;
  left: 46px;
  top: 42px;
  width: 240px;
  padding: 20px 22px 24px;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 16px 36px rgba(15, 61, 145, 0.14);
  transform: rotate(-3deg);
}
.ill-card-title {
  width: 96px;
  height: 12px;
  margin-bottom: 18px;
  border-radius: 6px;
  background: var(--brand-gradient);
}
.ill-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.ill-check {
  width: 16px;
  height: 16px;
  border: 2px solid #cfe2fb;
  border-radius: 5px;
  flex-shrink: 0;
}
.ill-check.on {
  border-color: transparent;
  background: var(--brand-gradient);
}
.ill-line {
  height: 8px;
  border-radius: 4px;
  background: #e8f0fb;
}
.ill-line.w70 {
  width: 70%;
}
.ill-line.w50 {
  width: 50%;
}
.ill-line.w62 {
  width: 62%;
}
.ill-submit {
  width: 100%;
  height: 34px;
  margin-top: 18px;
  border-radius: 10px;
  background: var(--brand-gradient);
  opacity: 0.9;
}
.ill-stat {
  position: absolute;
  right: 34px;
  bottom: 54px;
  width: 118px;
  padding: 14px 0;
  text-align: center;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 14px 30px rgba(15, 61, 145, 0.16);
  animation: ill-float 4s ease-in-out infinite;
}
.ill-stat-num {
  font-size: 24px;
  font-weight: 800;
  background: var(--brand-gradient);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.ill-stat-label {
  margin-top: 2px;
  font-size: 12px;
  color: var(--ink-3);
}
.ill-bubbles {
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}
.b1 {
  width: 120px;
  height: 120px;
  right: -20px;
  top: -30px;
  background: radial-gradient(closest-side, rgba(86, 204, 242, 0.45), transparent);
}
.b2 {
  width: 80px;
  height: 80px;
  left: -20px;
  bottom: 30px;
  background: radial-gradient(closest-side, rgba(99, 102, 241, 0.28), transparent);
}
.b3 {
  width: 34px;
  height: 34px;
  left: 210px;
  top: 30px;
  background: rgba(47, 128, 237, 0.18);
}
@keyframes ill-float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-8px);
  }
}
.ill-title {
  margin: 26px 0 0;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.3px;
  color: var(--ink);
}
.ill-desc {
  margin: 8px 0 0;
  font-size: 14px;
  color: var(--ink-2);
}
</style>
