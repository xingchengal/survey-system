<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <h2 class="auth-title">📋 问卷系统</h2>
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
</template>

<script>
import { ElMessage } from 'element-plus'
import api from '../api'
import { setAuth, homePathFor } from '../utils/auth'

/** 账号规则：字母+数字，4~20 位（与后端 UserService.ACCOUNT_PATTERN 一致） */
const ACCOUNT_RE = /^[A-Za-z0-9]{4,20}$/

export default {
  name: 'LoginView',
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
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 24px 0;
}
.auth-card {
  width: 440px;
  padding: 8px 12px;
}
.auth-title {
  text-align: center;
  margin: 0 0 4px 0;
}
.auth-subtitle {
  text-align: center;
  margin: 0 0 20px 0;
  color: #909399;
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
.mb-16 {
  margin-bottom: 16px;
}
</style>
