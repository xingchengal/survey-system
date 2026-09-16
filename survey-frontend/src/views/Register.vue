<template>
  <div style="height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);">
    <el-card style="width: 420px; padding: 20px;">
      <h2 style="text-align: center; margin-bottom: 30px;">📝 用户注册</h2>
      <el-form :model="form">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.nickname" placeholder="昵称（选填）" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码（至少6位）" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.confirmPassword" type="password" placeholder="确认密码" size="large" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" style="width: 100%;" @click="handleRegister">注 册</el-button>
        </el-form-item>
        <div style="text-align: center; font-size: 14px;">
          <span style="color: #999;">已有账号？</span>
          <el-button type="primary" link @click="$router.push('/login')">返回登录</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'

export default {
  data() {
    return {
      form: { username: '', nickname: '', password: '', confirmPassword: '' }
    }
  },
  methods: {
    async handleRegister() {
      if (!this.form.username || !this.form.password) {
        ElMessage.warning('用户名和密码不能为空')
        return
      }
      if (this.form.password !== this.form.confirmPassword) {
        ElMessage.warning('两次密码输入不一致')
        return
      }
      try {
        const res = await fetch('/api/user/register', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            username: this.form.username,
            password: this.form.password,
            nickname: this.form.nickname
          })
        })
        const msg = await res.text()
        if (msg === '注册成功') {
          ElMessage.success('注册成功，即将跳转登录页')
          setTimeout(() => this.$router.push('/login'), 1000)
        } else {
          ElMessage.error(msg)
        }
      } catch {
        ElMessage.error('请求异常')
      }
    }
  }
}
</script>