<template>
  <div style="height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);">
    <el-card style="width: 420px; padding: 20px;">
      <h2 style="text-align: center; margin-bottom: 30px;">📊 问卷调查系统</h2>
      <el-form :model="form">
        <el-form-item>
          <el-input v-model="form.username" placeholder="请输入用户名" size="large" :prefix-icon="User" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="请输入密码" size="large" :prefix-icon="Lock" @keyup.enter="handleLogin" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" style="width: 100%;" :loading="loading" @click="handleLogin">登 录</el-button>
        </el-form-item>
        <div style="text-align: center; font-size: 14px;">
          <span style="color: #999;">还没有账号？</span>
          <el-button type="primary" link @click="$router.push('/register')">立即注册</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'

export default {
  data() {
    return {
      form: { username: '', password: '' },
      loading: false,
      User: markRaw(User),
      Lock: markRaw(Lock)
    }
  },
  methods: {
    async handleLogin() {
      if (!this.form.username || !this.form.password) {
        ElMessage.warning('请输入用户名和密码')
        return
      }
      this.loading = true
      try {
        const res = await fetch('/api/user/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(this.form)
        })
        const data = await res.json()
        if (data.success) {
          ElMessage.success('登录成功')
          // ★ 保存 user 和 token
          localStorage.setItem('user', JSON.stringify(data.user))
          localStorage.setItem('token', data.token)
          // 按角色跳转
          if (data.user.role === 'admin') {
            this.$router.push('/dashboard')
          } else {
            this.$router.push('/my/dashboard')
          }
        } else {
          ElMessage.error(data.message || '登录失败')
        }
      } catch {
        ElMessage.error('请求异常')
      } finally {
        this.loading = false
      }
    }
  }
}
</script>