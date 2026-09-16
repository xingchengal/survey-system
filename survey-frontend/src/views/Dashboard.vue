<template>
  <div>
    <!-- 顶部 6 个数据卡片 -->
    <el-row :gutter="16">
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#ecf5ff;color:#409eff;">
            <el-icon :size="26"><UserFilled /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">总用户</div>
            <div class="stat-value" style="color:#409eff;">{{ stats.users }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#f0f9eb;color:#67c23a;">
            <el-icon :size="26"><Document /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">总问卷</div>
            <div class="stat-value" style="color:#67c23a;">{{ stats.total }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#fdf6ec;color:#e6a23c;">
            <el-icon :size="26"><Promotion /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">已发布</div>
            <div class="stat-value" style="color:#e6a23c;">{{ stats.published }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#fef0f0;color:#f56c6c;">
            <el-icon :size="26"><ChatDotRound /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">总答卷</div>
            <div class="stat-value" style="color:#f56c6c;">{{ stats.responses }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#f4f0ff;color:#8e44ad;">
            <el-icon :size="26"><View /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">访客次数</div>
            <div class="stat-value" style="color:#8e44ad;">{{ stats.views }}</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="4">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background:#e8f6ff;color:#12b0e8;">
            <el-icon :size="26"><PieChart /></el-icon>
          </div>
          <div class="stat-content">
            <div class="stat-label">已结束</div>
            <div class="stat-value" style="color:#12b0e8;">{{ stats.ended }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 中间：状态分布图 + 系统信息 -->
    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col :span="10">
        <el-card style="height: 500px;">
          <template #header>
            <span style="font-weight: bold;">📊 问卷状态分布</span>
          </template>
          <div v-if="stats.total > 0" ref="pieRef" style="width: 100%; height: 400px;"></div>
          <el-empty v-else description="暂无问卷数据" :image-size="80" />
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card style="height: 500px;">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span style="font-weight: bold;">📌 系统信息</span>
              <el-tag :type="healthTagType" size="small">
                <span :style="{
                  display: 'inline-block',
                  width: '6px',
                  height: '6px',
                  borderRadius: '50%',
                  background: healthDotColor,
                  marginRight: '4px',
                  verticalAlign: 'middle'
                }"></span>
                {{ healthText }}
              </el-tag>
            </div>
          </template>

          <!-- 基础信息 -->
          <el-descriptions :column="2" border size="small" style="margin-bottom: 12px;">
            <el-descriptions-item label="系统名称">问卷调查系统</el-descriptions-item>
            <el-descriptions-item label="系统版本">v2.1.0</el-descriptions-item>
            <el-descriptions-item label="当前用户">{{ currentUser.nickname || currentUser.username }}</el-descriptions-item>
            <el-descriptions-item label="登录时间">{{ loginTime }}</el-descriptions-item>
          </el-descriptions>

          <!-- 技术栈 -->
          <div style="font-weight: bold; font-size: 13px; color: #606266; margin-bottom: 8px;">
            🔧 技术栈
          </div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="前端">
              Vue 3 · Vite · Element Plus · ECharts · qrcode.js · Vue Router
            </el-descriptions-item>
            <el-descriptions-item label="后端">
              Spring Boot 4 · MyBatis · PageHelper · OkHttp · JJWT
            </el-descriptions-item>
            <el-descriptions-item label="数据库">
              MySQL 8.x（survey_db · 10 张表）
            </el-descriptions-item>
            <el-descriptions-item label="安全机制">
              JWT 认证 · RBAC 权限 · MD5 加密 · HttpOnly Cookie · 路由守卫
            </el-descriptions-item>
            <el-descriptions-item label="进阶技术">
              Spring AOP · @Scheduled 定时任务 · @Async 异步处理 · 全局异常
            </el-descriptions-item>
            <el-descriptions-item label="AI 能力">
              Qwen2.5-7B-Instruct（SiliconFlow 硅基流动）
            </el-descriptions-item>
            <el-descriptions-item label="工具库">
              Knife4j（OpenAPI 3）· EasyExcel
            </el-descriptions-item>
          </el-descriptions>

          <!-- 核心功能 -->
          <div style="font-weight: bold; font-size: 13px; color: #606266; margin-top: 12px; margin-bottom: 8px;">
            ✨ 核心功能
          </div>
          <div style="display: flex; flex-wrap: wrap; gap: 6px;">
            <el-tag size="small" effect="plain">问卷管理</el-tag>
            <el-tag size="small" effect="plain">逻辑跳转</el-tag>
            <el-tag size="small" effect="plain">模板广场</el-tag>
            <el-tag size="small" effect="plain">AI 生成</el-tag>
            <el-tag size="small" effect="plain">匿名填写</el-tag>
            <el-tag size="small" effect="plain">二维码分享</el-tag>
            <el-tag size="small" effect="plain">定时发布</el-tag>
            <el-tag size="small" effect="plain">数据可视化</el-tag>
            <el-tag size="small" effect="plain">Excel 导出</el-tag>
            <el-tag size="small" effect="plain">回收站</el-tag>
            <el-tag size="small" effect="plain">站内通知</el-tag>
            <el-tag size="small" effect="plain">操作日志</el-tag>
            <el-tag size="small" effect="plain">异常日志</el-tag>
            <el-tag size="small" effect="plain">系统公告</el-tag>
            <el-tag size="small" effect="plain">演示数据</el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 底部：快捷操作 -->
    <el-card style="margin-top: 20px;">
      <template #header>
        <span style="font-weight: bold;">⚡ 快捷操作</span>
      </template>
      <el-row :gutter="16">
        <el-col :span="6">
          <div class="quick-action" @click="$router.push('/users')">
            <el-icon :size="32" color="#409eff"><UserFilled /></el-icon>
            <div style="margin-top: 8px; font-weight: 500;">用户管理</div>
            <div style="font-size: 12px; color: #999; margin-top: 4px;">查看 / 删除用户</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="quick-action" @click="$router.push('/admin/surveys')">
            <el-icon :size="32" color="#67c23a"><Document /></el-icon>
            <div style="margin-top: 8px; font-weight: 500;">全部问卷</div>
            <div style="font-size: 12px; color: #999; margin-top: 4px;">查看 / 删除违规</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="quick-action" @click="$router.push('/statistics')">
            <el-icon :size="32" color="#e6a23c"><PieChart /></el-icon>
            <div style="margin-top: 8px; font-weight: 500;">系统统计</div>
            <div style="font-size: 12px; color: #999; margin-top: 4px;">图表 / 排行</div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="quick-action" @click="$router.push('/admin/logs')">
            <el-icon :size="32" color="#f56c6c"><Memo /></el-icon>
            <div style="margin-top: 8px; font-weight: 500;">系统监控</div>
            <div style="font-size: 12px; color: #999; margin-top: 4px;">操作 / 异常日志</div>
          </div>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { UserFilled, Document, Promotion, ChatDotRound, View, PieChart, Memo } from '@element-plus/icons-vue'

export default {
  components: { UserFilled, Document, Promotion, ChatDotRound, View, PieChart, Memo },
  data() {
    return {
      currentUser: JSON.parse(localStorage.getItem('user') || '{}'),
      loginTime: '',
      stats: {
        users: 0, total: 0, published: 0, draft: 0, ended: 0, responses: 0, views: 0
      },
      pieChart: null,
      serverHealthy: true,
      healthTimer: null,
      // ★ 模板里 :icon 用到的图标要 expose 出来
      Memo: markRaw(Memo)
    }
  },
  computed: {
    healthTagType() {
      return this.serverHealthy ? 'success' : 'danger'
    },
    healthDotColor() {
      return this.serverHealthy ? '#67c23a' : '#f56c6c'
    },
    healthText() {
      return this.serverHealthy ? '服务运行正常' : '服务已离线'
    }
  },
  mounted() {
    this.loginTime = this.formatNow()
    this.loadStats()
    this.checkHealth()
    this.healthTimer = setInterval(this.checkHealth, 5000)
  },
  beforeUnmount() {
    if (this.pieChart) this.pieChart.dispose()
    if (this.healthTimer) clearInterval(this.healthTimer)
  },
  methods: {
    formatNow() {
      const d = new Date()
      const pad = n => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
    },

    async checkHealth() {
      try {
        const res = await fetch('/api/health', { method: 'GET' })
        this.serverHealthy = res.ok
      } catch {
        this.serverHealthy = false
      }
    },

    async loadStats() {
      try {
        const res = await fetch('/api/survey/system-overview')
        this.stats = await res.json()
        this.$nextTick(() => this.initPie())
      } catch {
        ElMessage.error('加载统计数据失败')
      }
    },

    initPie() {
      if (this.stats.total === 0 || !this.$refs.pieRef) return
      if (this.pieChart) this.pieChart.dispose()

      this.pieChart = echarts.init(this.$refs.pieRef)

      const data = []
      if (this.stats.published > 0) data.push({ name: '已发布', value: this.stats.published, itemStyle: { color: '#67c23a' } })
      if (this.stats.draft > 0) data.push({ name: '草稿', value: this.stats.draft, itemStyle: { color: '#e6a23c' } })
      if (this.stats.ended > 0) data.push({ name: '已结束', value: this.stats.ended, itemStyle: { color: '#f56c6c' } })

      this.pieChart.setOption({
        tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
        legend: { bottom: 0 },
        series: [
          {
            type: 'pie',
            radius: ['45%', '70%'],
            center: ['50%', '45%'],
            label: { formatter: '{b}\n{c}份' },
            data
          }
        ]
      })
    }
  }
}
</script>

<style scoped>
.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  padding: 16px;
}
.stat-icon {
  width: 50px;
  height: 50px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 12px;
  flex-shrink: 0;
}
.stat-content { flex: 1; min-width: 0; }
.stat-label {
  font-size: 12px;
  color: #999;
  margin-bottom: 4px;
}
.stat-value {
  font-size: 22px;
  font-weight: bold;
  line-height: 1;
}

.quick-action {
  text-align: center;
  padding: 24px 16px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  border: 1px solid #f0f2f5;
}
.quick-action:hover {
  background: #f8fafc;
  border-color: #409eff;
  transform: translateY(-2px);
}
</style>