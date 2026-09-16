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
          <div style="text-align: center;">
            <el-tag type="info" effect="dark">普通用户</el-tag>
          </div>

          <el-divider />

          <div class="info-row">
            <span class="info-label">用户名</span>
            <span class="info-value">{{ user.username }}</span>
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
        </el-card>
      </el-col>

      <!-- 右侧：用户内容 -->
      <el-col :span="16">
        <!-- 我的数据 -->
        <el-card style="margin-bottom: 20px;">
          <template #header>
            <span style="font-weight: bold;">📊 我的数据</span>
          </template>
          <el-row :gutter="12">
            <el-col :span="6">
              <div class="mini-card">
                <el-icon :size="22" color="#409eff"><Document /></el-icon>
                <div class="mini-value" style="color:#409eff;">{{ myStats.total }}</div>
                <div class="mini-label">我的问卷</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-card">
                <el-icon :size="22" color="#67c23a"><Promotion /></el-icon>
                <div class="mini-value" style="color:#67c23a;">{{ myStats.published }}</div>
                <div class="mini-label">已发布</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-card">
                <el-icon :size="22" color="#e6a23c"><EditPen /></el-icon>
                <div class="mini-value" style="color:#e6a23c;">{{ myStats.draft }}</div>
                <div class="mini-label">草稿</div>
              </div>
            </el-col>
            <el-col :span="6">
              <div class="mini-card">
                <el-icon :size="22" color="#f56c6c"><ChatDotRound /></el-icon>
                <div class="mini-value" style="color:#f56c6c;">{{ myStats.responses }}</div>
                <div class="mini-label">收到答卷</div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- 常用功能 -->
        <el-card style="margin-bottom: 20px;">
          <template #header>
            <span style="font-weight: bold;">⚡ 常用功能</span>
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
              <el-form :model="profileForm" label-width="100px" style="margin-top: 20px; max-width: 500px;">
                <el-form-item label="用户名">
                  <el-input v-model="user.username" disabled />
                </el-form-item>
                <el-form-item label="昵称">
                  <el-input v-model="profileForm.nickname" placeholder="请输入昵称" maxlength="20" show-word-limit />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="saveNickname">保存昵称</el-button>
                </el-form-item>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="联系方式" name="contact">
              <el-form :model="contactForm" label-width="100px" style="margin-top: 20px; max-width: 500px;">
                <el-form-item label="手机号">
                  <el-input v-model="contactForm.phone" placeholder="请输入手机号" maxlength="11" />
                </el-form-item>
                <el-form-item label="邮箱">
                  <el-input v-model="contactForm.email" placeholder="请输入邮箱" maxlength="100" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="saveContact">保存联系方式</el-button>
                </el-form-item>
                <el-alert type="info" :closable="false" style="max-width: 500px;">
                  绑定联系方式后，可接收问卷相关通知、找回账号密码
                </el-alert>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="修改密码" name="password">
              <el-form :model="pwdForm" label-width="100px" style="margin-top: 20px; max-width: 500px;">
                <el-form-item label="原密码">
                  <el-input v-model="pwdForm.oldPassword" type="password" show-password />
                </el-form-item>
                <el-form-item label="新密码">
                  <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少6位" />
                </el-form-item>
                <el-form-item label="确认新密码">
                  <el-input v-model="pwdForm.confirmPassword" type="password" show-password />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="savePassword">修改密码</el-button>
                </el-form-item>
              </el-form>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="previewVisible" title="头像预览" width="420px">
      <div style="text-align: center;">
        <img :src="previewImage" style="max-width: 100%; max-height: 400px; border-radius: 8px;" />
      </div>
      <template #footer>
        <el-button @click="previewVisible = false; previewImage = ''">取消</el-button>
        <el-button type="primary" @click="confirmAvatar">使用该头像</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ElMessage } from 'element-plus'
import { Camera, Document, ChatDotRound, Promotion, EditPen, DataBoard, Delete } from '@element-plus/icons-vue'

export default {
  components: { Camera, Document, ChatDotRound, Promotion, EditPen, DataBoard, Delete },
  data() {
    return {
      user: {},
      activeTab: 'profile',
      profileForm: { nickname: '' },
      contactForm: { phone: '', email: '' },
      pwdForm: { oldPassword: '', newPassword: '', confirmPassword: '' },
      avatarHover: false,
      previewVisible: false,
      previewImage: '',
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
        const res = await fetch('/api/user/profile')
        this.user = await res.json()
        if (this.user.role === 'admin') {
          ElMessage.info('管理员请访问管理员个人中心')
          this.$router.push('/admin/profile')
          return
        }
        this.profileForm.nickname = this.user.nickname || ''
        this.contactForm.phone = this.user.phone || ''
        this.contactForm.email = this.user.email || ''
        this.loadMyStats()
      } catch {
        ElMessage.error('加载失败')
      }
    },

    async loadMyStats() {
      try {
        const res = await fetch('/api/survey/my-statistics')
        this.myStats = await res.json()
      } catch (e) { console.error(e) }
    },

    triggerUpload() { this.$refs.fileInput.click() },

    handleFileChange(e) {
      const file = e.target.files[0]
      if (!file) return
      if (file.size > 1024 * 1024) return ElMessage.warning('图片不能超过 1MB')
      if (!file.type.startsWith('image/')) return ElMessage.warning('只能上传图片')
      const reader = new FileReader()
      reader.onload = (ev) => { this.previewImage = ev.target.result; this.previewVisible = true }
      reader.readAsDataURL(file)
      e.target.value = ''
    },

    async confirmAvatar() {
      const res = await fetch('/api/user/avatar', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ avatar: this.previewImage })
      })
      const msg = await res.text()
      if (msg === '修改成功') {
        ElMessage.success('头像修改成功')
        this.user.avatar = this.previewImage
        const localUser = JSON.parse(localStorage.getItem('user') || '{}')
        localUser.avatar = this.previewImage
        localStorage.setItem('user', JSON.stringify(localUser))
        this.previewVisible = false
        this.previewImage = ''
      } else ElMessage.error(msg)
    },

    async saveNickname() {
      if (!this.profileForm.nickname.trim()) return ElMessage.warning('昵称不能为空')
      const res = await fetch('/api/user/nickname', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ nickname: this.profileForm.nickname.trim() })
      })
      const msg = await res.text()
      if (msg === '修改成功') {
        ElMessage.success('昵称修改成功')
        this.user.nickname = this.profileForm.nickname.trim()
        const localUser = JSON.parse(localStorage.getItem('user') || '{}')
        localUser.nickname = this.user.nickname
        localStorage.setItem('user', JSON.stringify(localUser))
      } else ElMessage.error(msg)
    },

    async saveContact() {
      const res = await fetch('/api/user/contact', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(this.contactForm)
      })
      const msg = await res.text()
      if (msg === '修改成功') {
        ElMessage.success('联系方式保存成功')
        this.user.phone = this.contactForm.phone
        this.user.email = this.contactForm.email
      } else ElMessage.error(msg)
    },

    async savePassword() {
      if (!this.pwdForm.oldPassword) return ElMessage.warning('请输入原密码')
      if (!this.pwdForm.newPassword || this.pwdForm.newPassword.length < 6) return ElMessage.warning('新密码至少6位')
      if (this.pwdForm.newPassword !== this.pwdForm.confirmPassword) return ElMessage.warning('两次新密码不一致')
      const res = await fetch('/api/user/password', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          oldPassword: this.pwdForm.oldPassword,
          newPassword: this.pwdForm.newPassword
        })
      })
      const msg = await res.text()
      if (msg === '修改成功') {
        ElMessage.success('密码修改成功，请重新登录')
        this.pwdForm = { oldPassword: '', newPassword: '', confirmPassword: '' }
        setTimeout(() => {
          localStorage.removeItem('user')
          localStorage.removeItem('token') 
          this.$router.push('/login')
        }, 1500)
      } else ElMessage.error(msg)
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
.info-label { color: #909399; }
.info-value { color: #303133; font-weight: 500; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.mini-card { text-align: center; padding: 14px 6px; border-radius: 10px; background: #fafbfc; transition: all 0.2s ease; }
.mini-card:hover { background: #f0f7ff; transform: translateY(-2px); }
.mini-value { font-size: 22px; font-weight: bold; line-height: 1.2; margin-top: 6px; }
.mini-label { font-size: 12px; color: #909399; margin-top: 2px; }

.quick-action { text-align: center; padding: 18px 10px; border-radius: 10px; border: 1px solid #f0f2f5; cursor: pointer; transition: all 0.25s ease; }
.quick-action:hover { background: #f0f7ff; border-color: #409eff; transform: translateY(-2px); }
.qa-title { font-size: 13px; font-weight: 500; color: #303133; margin-top: 8px; }
.qa-desc { font-size: 11px; color: #909399; margin-top: 2px; }
</style>