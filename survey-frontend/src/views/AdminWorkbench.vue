<template>
  <div class="workbench">
    <div class="page-head">
      <div>
        <h2 class="page-title">管理工作台</h2>
        <p class="page-subtitle">
          {{ displayName }}，这里只显示超级管理员分配给你的模块。
        </p>
      </div>
      <el-tag type="warning" effect="light" round>次级管理员</el-tag>
    </div>

    <el-empty
      v-if="myModules.length === 0"
      description="还没有被分配任何模块，请联系超级管理员授权"
    />

    <el-row v-else :gutter="16">
      <el-col v-for="m in myModules" :key="m.path" :xs="24" :sm="12" :md="8" class="mb-16">
        <div class="module-card" @click="$router.push(m.path)">
          <div class="module-icon" :style="{ background: m.bg, color: m.color }">
            <el-icon :size="24"><component :is="m.icon" /></el-icon>
          </div>
          <div class="module-text">
            <div class="module-title">{{ m.title }}</div>
            <div class="module-desc">{{ m.desc }}</div>
          </div>
          <el-icon class="module-arrow"><ArrowRight /></el-icon>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import {
  DataBoard, Document, DocumentCopy, UserFilled, Warning,
  Wallet, Bell, Memo, ArrowRight
} from '@element-plus/icons-vue'
import { getUser, hasPermission } from '../utils/auth'

/**
 * 次级管理员工作台。
 *
 * ★ 为什么不复用 /dashboard：超管的系统总览里有用户数、系统指标等，
 *   次级管理员没有 system:view 时进去只会看到 403/空白；工作台按权限列出可去的地方。
 * ★ 卡片只做“导航 + 说明”，真正的权限仍在后端；前端隐藏不代表授权。
 */
export default {
  name: 'AdminWorkbench',
  components: { ArrowRight },
  data() {
    return {
      user: getUser() || {},
      modules: [
        { permission: 'system:view', title: '系统总览', desc: '查看全站运营数据', path: '/dashboard', icon: markRaw(DataBoard), color: '#2f80ed', bg: '#e8f3ff' },
        { permission: 'survey:manage', title: '全部问卷', desc: '管理问卷、回收站与导出', path: '/admin/surveys', icon: markRaw(Document), color: '#0284c7', bg: '#e0f2fe' },
        { permission: 'template:manage', title: '模板管理', desc: '维护模板广场与分类', path: '/admin/templates', icon: markRaw(DocumentCopy), color: '#7c3aed', bg: '#f3ecff' },
        { permission: 'user:manage', title: '用户管理', desc: '管理普通用户账号', path: '/users', icon: markRaw(UserFilled), color: '#16a34a', bg: '#e9f9ef' },
        { permission: 'review:handle', title: '举报审核', desc: '处理举报与下架', path: '/admin/reviews', icon: markRaw(Warning), color: '#d97706', bg: '#fff5e6' },
        { permission: 'wallet:manage', title: '钱包管理', desc: '调整额度与对账', path: '/admin/wallets', icon: markRaw(Wallet), color: '#0891b2', bg: '#ecfeff' },
        { permission: 'announcement:manage', title: '公告管理', desc: '发布与上下架公告', path: '/admin/announcements', icon: markRaw(Bell), color: '#b45309', bg: '#fff6e8' },
        { permission: 'log:view', title: '日志查看', desc: '操作日志与异常日志', path: '/admin/logs', icon: markRaw(Memo), color: '#64748b', bg: '#f4f7fc' }
      ]
    }
  },
  computed: {
    displayName() {
      return this.user.nickname || this.user.username || '管理员'
    },
    myModules() {
      return this.modules.filter(m => hasPermission(m.permission))
    }
  }
}
</script>

<style scoped>
.workbench {
  max-width: 1080px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}
.page-title {
  margin: 0 0 6px;
  font-size: 22px;
  color: var(--ink);
}
.page-subtitle {
  margin: 0;
  font-size: 13px;
  color: var(--ink-3);
}
.module-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  border: 1px solid var(--stroke);
  border-radius: var(--r-md);
  background: var(--surface);
  cursor: pointer;
  transition: transform 0.18s var(--ease), box-shadow 0.18s var(--ease), border-color 0.18s var(--ease);
}
.module-card:hover {
  transform: translateY(-2px);
  border-color: var(--sky-300, #93c5fd);
  box-shadow: var(--shadow-md, 0 10px 24px rgba(15, 23, 42, 0.08));
}
.module-icon {
  width: 46px;
  height: 46px;
  flex: 0 0 46px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.module-text {
  min-width: 0;
  flex: 1;
}
.module-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 4px;
}
.module-desc {
  font-size: 12px;
  color: var(--ink-3);
  line-height: 1.5;
}
.module-arrow {
  color: var(--ink-3);
}
</style>
